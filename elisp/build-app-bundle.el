;;; build-app-bundle.el --- Build a shippable jetpacs-app-<id>.el bundle -*- lexical-binding: t; -*-

;; Copyright (C) 2026 calebc42 and contributors
;; SPDX-License-Identifier: GPL-3.0-or-later

;; Turns one app.org document into one self-contained, installable
;; elisp bundle: the jetpacs-crud runtime + the document text + an
;; install call.  On the device, `(require 'jetpacs-core)' first, then
;; load the bundle — the app registers itself and appears in the
;; launcher; the document is materialized under
;; `jetpacs-crud-apps-directory' on first load and never clobbered after
;; (it may hold user data).
;;
;; Batch usage (from the repo root; the jetpacs submodule provides core):
;;
;;   emacs -Q --batch -l elisp/build-app-bundle.el -- path/to/app.org [OUT-DIR]
;;
;; The composer desktop app performs this same assembly itself (template
;; substitution over the identical inputs); this script is the reference
;; implementation and what CI pins the equivalence against.

;;; Code:

(require 'cl-lib)

(defconst jetpacs-crud-bundle--here
  (file-name-directory (or load-file-name buffer-file-name))
  "The elisp/ source directory this script lives in.")

(defun jetpacs-crud-bundle--slurp (file)
  "FILE's contents as a string (the sources and documents are UTF-8)."
  (with-temp-buffer
    (let ((coding-system-for-read 'utf-8))
      (insert-file-contents file))
    (buffer-string)))

(defun jetpacs-crud-build-bundle (app-file &optional out-dir)
  "Build the single-file bundle for APP-FILE into OUT-DIR (default: its dir).
Validates the document first — a bundle is only ever built from a spec
that parses.  Returns the output path."
  ;; Parsing needs the runtime, which needs the jetpacs core; resolve both
  ;; relative to this repo so the script works from a bare checkout.
  (let ((core-dir (expand-file-name "../jetpacs/emacs/core"
                                    jetpacs-crud-bundle--here)))
    (when (file-directory-p core-dir)
      (add-to-list 'load-path core-dir)))
  (add-to-list 'load-path (directory-file-name jetpacs-crud-bundle--here))
  (require 'jetpacs-crud)
  (require 'jetpacs-crud-orgapp)
  (let* ((spec (jetpacs-crud-parse-app app-file))
         (id (plist-get spec :id))
         (text (jetpacs-crud-bundle--slurp app-file))
         (out (expand-file-name
               (format "jetpacs-app-%s.el" id)
               (or out-dir (file-name-directory (expand-file-name app-file)))))
         (coding-system-for-write 'utf-8))
    (with-temp-file out
      (insert (format ";;; jetpacs-app-%s.el --- %s, a Jetpacs CRUD app -*- lexical-binding: t; -*-\n"
                      id (plist-get spec :label))
              ";;\n"
              (format ";; Jetpacs-App: %s\n" id)
              ";; GENERATED FILE -- do not edit by hand; edit the app.org and rebuild.\n"
              ";; Produced by jetpacs-composer (build-app-bundle.el).\n"
              ";; Requires the Jetpacs foundation: (require 'jetpacs-core) before loading.\n"
              ";;\n"
              ";;; Code:\n\n")
      (dolist (part '("jetpacs-crud.el" "jetpacs-crud-orgapp.el"))
        (insert ";;; ==================================================================\n"
                (format ";;; BEGIN %s (the jetpacs-composer runtime)\n" part)
                ";;; ==================================================================\n\n")
        (insert (jetpacs-crud-bundle--slurp
                 (expand-file-name part jetpacs-crud-bundle--here)))
        (insert "\n"))
      (insert ";;; ==================================================================\n"
              ";;; The app document\n"
              ";;; ==================================================================\n\n"
              (format "(jetpacs-crud-install %S %S)\n\n" id text)
              (format "(provide 'jetpacs-app-%s)\n" id)
              (format ";;; jetpacs-app-%s.el ends here\n" id)))
    (message "Wrote %s" out)
    out))

;; Batch entry point: everything after "--" is APP-ORG [OUT-DIR].
(when noninteractive
  (let ((args command-line-args-left))
    (when (equal (car args) "--")
      (setq args (cdr args)))
    (when args
      (setq command-line-args-left nil)
      (condition-case err
          (jetpacs-crud-build-bundle (car args) (cadr args))
        (error (message "build-app-bundle: %s" (error-message-string err))
               (kill-emacs 1))))))

;;; build-app-bundle.el ends here
