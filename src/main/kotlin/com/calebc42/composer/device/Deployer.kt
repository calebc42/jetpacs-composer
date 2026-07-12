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
     * The one-time addition to the device init that makes staged
     * composer bundles install themselves — the starter-init adopt
     * pattern, generalized from a fixed list to the composer's
     * jetpacs-app-*.el naming convention. Place it AFTER
     * `(require 'jetpacs-core)`.
     */
    val installSnippet = """
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
}
