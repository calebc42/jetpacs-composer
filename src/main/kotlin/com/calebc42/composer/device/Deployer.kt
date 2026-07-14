// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.device

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.PackManifest
import com.calebc42.composer.model.requiredDepends
import java.io.File

/**
 * The two ways a bundle reaches the device (jetpacs deploy.ps1's exact
 * mechanics, reused):
 *
 * - STAGING: `adb push` to /sdcard/Download; the foundation's managed
 *   init (Phase G: ~/.emacs.d/jetpacs/apps.el lists the bundle,
 *   `jetpacs-config-adopt` copies + byte-compiles + requires it) installs
 *   it on the next Emacs (re)start. Needs nothing but adb, plus the
 *   one-time apps.el line from [installSnippet].
 * - LIVE: scp straight into ~/.emacs.d/jetpacs/lib/ (the foundation's
 *   installed-bundle dir) over Termux sshd (adb-forwarded port 8022),
 *   then `emacsclient -e '(load …)'` — the running Emacs re-registers
 *   the app and the phone updates in seconds. Needs `sshd` running
 *   inside Termux and an Emacs server; persistence across restarts
 *   still comes from the apps.el line.
 *
 * Engine dependencies are NOT part of either path anymore: the bundle
 * runtime installs its own engine pair on first load and offers an
 * Install button on the degraded view (jetpacs-crud-vulpea.el, engine
 * self-provisioning). [bootstrapDeps] remains as an optional ssh
 * pre-provisioning shortcut, and it is the only path that also installs
 * an app's extra declared depends (app data must never trigger an
 * install from the wire — the Stage 4 trust rule).
 */
object Deployer {

    const val SSH_PORT = 8022
    const val SSH_TARGET = "termux@127.0.0.1"

    data class Step(val label: String, val result: CmdResult)

    fun stagingDeploy(serial: String, bundle: File): List<Step> = listOf(
        Step("adb push → /sdcard/Download/${bundle.name}",
             Adb.push(serial, bundle, "/sdcard/Download/${bundle.name}")),
    )

    fun liveDeploy(serial: String, bundle: File): List<Step> {
        val steps = mutableListOf<Step>()
        val forward = Adb.forward(serial, SSH_PORT, SSH_PORT)
        steps += Step("adb forward tcp:$SSH_PORT", forward)
        if (!forward.ok) return steps

        // Create the destination before scp — on a first-ever deploy to a
        // fresh device the foundation's lib dir doesn't exist yet.  This is
        // Phase G's installed-bundle location (~/.emacs.d/jetpacs/lib/), the
        // same place `jetpacs-config-adopt` installs to, so the boot path and
        // the live path can never disagree about where the app lives; the
        // next restart re-byte-compiles it there (apps.el listing).
        val mkdir = Adb.exec(
            listOf("ssh", "-p", SSH_PORT.toString(),
                   "-o", "StrictHostKeyChecking=accept-new",
                   SSH_TARGET, "mkdir -p .emacs.d/jetpacs/lib"),
            timeoutSeconds = 30,
        )
        steps += Step("ssh mkdir -p .emacs.d/jetpacs/lib", mkdir)
        if (!mkdir.ok) return steps

        val scp = Adb.exec(
            listOf("scp", "-P", SSH_PORT.toString(),
                   "-o", "StrictHostKeyChecking=accept-new",
                   bundle.absolutePath,
                   "$SSH_TARGET:.emacs.d/jetpacs/lib/${bundle.name}"),
            timeoutSeconds = 120,
        )
        steps += Step("scp → ~/.emacs.d/jetpacs/lib/${bundle.name}", scp)
        if (!scp.ok) return steps

        steps += Step(
            "emacsclient load (live reload)",
            Adb.exec(
                listOf("ssh", "-p", SSH_PORT.toString(),
                       "-o", "StrictHostKeyChecking=accept-new",
                       SSH_TARGET,
                       "emacsclient -e '(load \"~/.emacs.d/jetpacs/lib/${bundle.name}\")'"),
                timeoutSeconds = 60,
            ),
        )
        return steps
    }

    /** The engines every non-pack composer app leans on. */
    val DEFAULT_ENGINES = listOf("org-ql", "vulpea")

    /**
     * Dependency names that are Termux binaries, not Emacs packages —
     * the engines survey flags rg/fd-class tools. These are surfaced as
     * "install via Termux" warnings ([binaryWarnings]) and NEVER handed
     * to package-install.
     */
    val BINARY_DEPS = setOf("rg", "ripgrep", "fd", "fd-find", "git", "sqlite3")

    /**
     * Every package a deploy declares for [spec], as one deduped set. The
     * classic engine pair ([DEFAULT_ENGINES]) is always present — the
     * composer runtime reads records/notes through vulpea and rich filters
     * through org-ql regardless of any pack, and the device-init snippet
     * that installs these is pasted once and shared by every later app, so
     * the substrate can never be dropped just because one app's manifest
     * omits it. A pack-backed app UNIONS its selected [manifest]'s `depends`
     * on top (never replaces the baseline, so its own non-pack views' and
     * `#+JETPACS_DEPENDS:` engines still install). Both [installList] and
     * [binaryWarnings] partition this one set — never recompute it apart.
     */
    private fun declaredDepends(spec: AppSpec, manifest: PackManifest?): List<String> =
        (DEFAULT_ENGINES +
            (manifest?.depends?.map { it.name } ?: emptyList()) +
            spec.depends + spec.requiredDepends()).distinct()

    /**
     * The packages a deploy installs: [declaredDepends] minus the Termux
     * binaries (surfaced by [binaryWarnings]). Built-ins like org and
     * cl-lib pass through — `package-installed-p` makes them no-ops.
     */
    fun installList(spec: AppSpec, manifest: PackManifest?): List<String> =
        declaredDepends(spec, manifest).filterNot { it in BINARY_DEPS }

    /** Human-readable warnings for deps a MELPA install can never satisfy. */
    fun binaryWarnings(spec: AppSpec, manifest: PackManifest?): List<String> =
        declaredDepends(spec, manifest).filter { it in BINARY_DEPS }.map {
            "\"$it\" is not installable from MELPA — install it in Termux " +
                "(pkg install $it)"
        }

    /**
     * The engine-bootstrap forms: install [depends] from MELPA and wire
     * vulpea's autosync over the org vault AND the installed-apps
     * directory (composer apps keep their inline sources there).
     * Since the runtime learned to self-provision its engine pair
     * (jetpacs-crud-vulpea.el), these forms are no longer part of the
     * pasted [installSnippet]; they remain the [bootstrapDeps] ssh
     * pre-provisioning payload — the one path that also installs an
     * app's EXTRA declared depends (org-super-agenda-class packages the
     * runtime deliberately never installs from the wire). Every step is
     * wrapped so an offline run never breaks anything — installs are
     * retried on the next run. Idempotent (package-installed-p, a
     * once-only full-scan marker, and autosync-mode being a no-op when
     * on). The vulpea wiring stays even when vulpea isn't in [depends] —
     * `(require 'vulpea nil t)` makes it a no-op then.
     */
    fun depBootstrapForms(depends: List<String> = DEFAULT_ENGINES): String = """
        ;; jetpacs-composer engines for this app's views and packs.
        (require 'package)
        (add-to-list 'package-archives '("melpa" . "https://melpa.org/packages/") t)
        (package-initialize)
        (dolist (pkg '(${depends.joinToString(" ")}))
          (unless (package-installed-p pkg)
            (condition-case err
                (progn
                  (unless package-archive-contents (package-refresh-contents))
                  (package-install pkg))
              (error (message "jetpacs-composer: %s install deferred (%s)"
                              pkg (error-message-string err))))))
        (when (require 'vulpea nil t)
          (setq vulpea-db-sync-directories
                (list org-directory
                      (expand-file-name "jetpacs-crud/" user-emacs-directory)))
          (vulpea-db-autosync-mode 1)
          ;; One full scan on first run so an existing vault is indexed before
          ;; the first query; later runs rely on autosync.
          (let ((marker (expand-file-name "jetpacs-crud/.vulpea-scanned"
                                          user-emacs-directory)))
            (unless (file-exists-p marker)
              (ignore-errors (vulpea-db-sync-full-scan))
              (make-directory (file-name-directory marker) t)
              (write-region "" nil marker))))
    """.trimIndent()

    /**
     * The once-per-app install line for the device: list the bundle in the
     * foundation's create-once ~/.emacs.d/jetpacs/apps.el. Every restart
     * then adopts the newest staged copy from /sdcard (Download or
     * Documents), byte-compiles it, and requires it — re-deploys of the
     * same app need no further edits. This replaced the old
     * paste-a-whole-adopt-loop snippet: one explicit line per app keeps
     * the user consenting to each bundle that gets to run (the same
     * allowlist instinct as SPEC §5 — no wildcard auto-loading of
     * whatever lands in shared storage), and the legacy
     * ~/.emacs.d/elisp/ layout it targeted predated Phase G. Engine
     * installs are gone from the snippet too: the bundle runtime
     * self-provisions org-ql/vulpea on first load and offers an Install
     * button on the degraded view.
     */
    fun installSnippet(bundleName: String): String = """
        ;; In ~/.emacs.d/jetpacs/apps.el, add this app to the installed list:
        (add-to-list 'jetpacs-installed-bundles "$bundleName")

        ;; (No managed apps.el — a BYO init?  Use the foundation seam
        ;;  directly, after (require 'jetpacs-core):
        ;;    (require (jetpacs-config-adopt "$bundleName")))
    """.trimIndent()

    /**
     * Provision an already-running device without editing its init: run
     * [depBootstrapForms] (over [depends]) via the live emacsclient
     * channel. The forms contain apostrophe-quoted elisp, which does not
     * survive ssh single-quote wrapping, so we stage them as a file and
     * `load` it (a double-quoted remote command with no apostrophes).
     * Idempotent — safe to run on every deploy.
     */
    fun bootstrapDeps(serial: String,
                      depends: List<String> = DEFAULT_ENGINES): List<Step> {
        val steps = mutableListOf<Step>()
        val forward = Adb.forward(serial, SSH_PORT, SSH_PORT)
        steps += Step("adb forward tcp:$SSH_PORT", forward)
        if (!forward.ok) return steps

        val bootFile = File.createTempFile("jetpacs-boot", ".el").apply {
            writeText(depBootstrapForms(depends) + "\n")
            deleteOnExit()
        }

        val mkdir = Adb.exec(
            listOf("ssh", "-p", SSH_PORT.toString(),
                   "-o", "StrictHostKeyChecking=accept-new",
                   SSH_TARGET, "mkdir -p .emacs.d"),
            timeoutSeconds = 30,
        )
        steps += Step("ssh mkdir -p .emacs.d", mkdir)
        if (!mkdir.ok) return steps

        val scp = Adb.exec(
            listOf("scp", "-P", SSH_PORT.toString(),
                   "-o", "StrictHostKeyChecking=accept-new",
                   bootFile.absolutePath,
                   "$SSH_TARGET:.emacs.d/jetpacs-boot.el"),
            timeoutSeconds = 60,
        )
        steps += Step("scp → ~/.emacs.d/jetpacs-boot.el", scp)
        if (!scp.ok) return steps

        // Double-quoted remote command → emacsclient receives an
        // apostrophe-free (load …) form; package-install may take a while.
        steps += Step(
            "emacsclient bootstrap engines",
            Adb.exec(
                listOf("ssh", "-p", SSH_PORT.toString(),
                       "-o", "StrictHostKeyChecking=accept-new",
                       SSH_TARGET,
                       "emacsclient -e \"(load (expand-file-name " +
                           "\\\"jetpacs-boot.el\\\" user-emacs-directory))\""),
                timeoutSeconds = 300,
            ),
        )
        return steps
    }
}
