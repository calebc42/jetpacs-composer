// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.device

import java.io.File

/**
 * The two ways a bundle reaches the device (jetpacs deploy.ps1's exact
 * mechanics, reused):
 *
 * - STAGING: `adb push` to /sdcard/Download; the device init's adopt
 *   loop installs it on the next Emacs (re)start. Needs nothing but adb.
 * - LIVE: scp straight into ~/.emacs.d/elisp over Termux sshd
 *   (adb-forwarded port 8022), then `emacsclient -e '(load …)'` — the
 *   running Emacs re-registers the app and the phone updates in
 *   seconds. Needs `sshd` running inside Termux and an Emacs server.
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

        // Create the destination before scp — on a first-ever deploy to a fresh
        // device ~/.emacs.d/elisp doesn't exist yet (deploy.sh/deploy.ps1 do this).
        val mkdir = Adb.exec(
            listOf("ssh", "-p", SSH_PORT.toString(),
                   "-o", "StrictHostKeyChecking=accept-new",
                   SSH_TARGET, "mkdir -p .emacs.d/elisp"),
            timeoutSeconds = 30,
        )
        steps += Step("ssh mkdir -p .emacs.d/elisp", mkdir)
        if (!mkdir.ok) return steps

        val scp = Adb.exec(
            listOf("scp", "-P", SSH_PORT.toString(),
                   "-o", "StrictHostKeyChecking=accept-new",
                   bundle.absolutePath,
                   "$SSH_TARGET:.emacs.d/elisp/${bundle.name}"),
            timeoutSeconds = 120,
        )
        steps += Step("scp → ~/.emacs.d/elisp/${bundle.name}", scp)
        if (!scp.ok) return steps

        steps += Step(
            "emacsclient load (live reload)",
            Adb.exec(
                listOf("ssh", "-p", SSH_PORT.toString(),
                       "-o", "StrictHostKeyChecking=accept-new",
                       SSH_TARGET,
                       "emacsclient -e '(load \"~/.emacs.d/elisp/${bundle.name}\")'"),
                timeoutSeconds = 60,
            ),
        )
        return steps
    }

    /**
     * The engine-bootstrap forms: install org-ql + vulpea from MELPA and
     * wire vulpea's autosync over the org vault AND the installed-apps
     * directory (composer apps keep their inline sources there). Mirrors
     * Glasspane's docs/starter-init.el. Every step is wrapped so an
     * offline launch never breaks startup — installs are retried each
     * launch until they succeed. Idempotent (package-installed-p, a
     * once-only full-scan marker, and autosync-mode being a no-op when on).
     */
    val depBootstrapForms = """
        ;; jetpacs-composer engines: org-ql (rich FILTER queries) and vulpea
        ;; (the note index behind records/notes/board/… kinds).
        (require 'package)
        (add-to-list 'package-archives '("melpa" . "https://melpa.org/packages/") t)
        (package-initialize)
        (dolist (pkg '(org-ql vulpea))
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
     * The one-time addition to the device init that makes staged
     * composer bundles install themselves — the starter-init adopt
     * pattern, generalized from a fixed list to the composer's
     * jetpacs-app-*.el naming convention — preceded by the engine
     * bootstrap. Place it AFTER `(require 'jetpacs-core)`.
     */
    val installSnippet = depBootstrapForms + "\n\n" + """
        ;; jetpacs-composer apps: adopt newer staged bundles, then load all.
        (let ((dir (expand-file-name "elisp" user-emacs-directory)))
          (dolist (staged (append
                           (file-expand-wildcards "/sdcard/Download/jetpacs-app-*.el")
                           (file-expand-wildcards "/sdcard/Documents/jetpacs-app-*.el")))
            (let ((installed (expand-file-name (file-name-nondirectory staged) dir)))
              (when (or (not (file-exists-p installed))
                        (file-newer-than-file-p staged installed))
                (make-directory dir t)
                (copy-file staged installed t)
                (message "%s: adopted from %s"
                         (file-name-nondirectory staged)
                         (file-name-directory staged)))))
          (dolist (bundle (file-expand-wildcards
                           (expand-file-name "jetpacs-app-*.el" dir)))
            (load bundle)))
    """.trimIndent()

    /**
     * Provision an already-running device without editing its init: run
     * [depBootstrapForms] over the live emacsclient channel. The forms
     * contain apostrophe-quoted elisp, which does not survive ssh
     * single-quote wrapping, so we stage them as a file and `load` it
     * (a double-quoted remote command with no apostrophes). Idempotent —
     * safe to run on every deploy.
     */
    fun bootstrapDeps(serial: String): List<Step> {
        val steps = mutableListOf<Step>()
        val forward = Adb.forward(serial, SSH_PORT, SSH_PORT)
        steps += Step("adb forward tcp:$SSH_PORT", forward)
        if (!forward.ok) return steps

        val bootFile = File.createTempFile("jetpacs-boot", ".el").apply {
            writeText(depBootstrapForms + "\n")
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
