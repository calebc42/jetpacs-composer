;;; jetpacs-crud-orgapp.el --- app.org front end for jetpacs-crud -*- lexical-binding: t; -*-

;; Copyright (C) 2026 calebc42 and contributors
;; SPDX-License-Identifier: GPL-3.0-or-later

;; Parses the declarative `app.org' format (docs/FORMAT.md) into the
;; spec plist `jetpacs-crud-register' consumes, and provides the install
;; surface: register a file, install shipped app text, scan a directory.
;; The composer generates these files; a person with a text editor can
;; write one by hand — the format is the contract, not the tool.

;;; Code:

(require 'cl-lib)
(require 'org)
(require 'jetpacs-crud)

;; ─── Parsing helpers ─────────────────────────────────────────────────────────

(defconst jetpacs-crud-orgapp--keyword-re
  "^#\\+\\(JETPACS_APP\\|JETPACS_ICON\\|JETPACS_ORDER\\|JETPACS_APP_FORMAT\\|TITLE\\):[ \t]*\\(.*?\\)[ \t]*$"
  "File-level keywords of the format (docs/FORMAT.md), case-insensitive.")

(defun jetpacs-crud-orgapp--keywords ()
  "Collect the format's file-level keywords from the current buffer.
Returns an alist of upcased NAME -> VALUE; the first occurrence wins."
  (let ((case-fold-search t)
        (found nil))
    (save-excursion
      (goto-char (point-min))
      (while (re-search-forward jetpacs-crud-orgapp--keyword-re nil t)
        (let ((name (upcase (match-string-no-properties 1))))
          (unless (assoc name found)
            (push (cons name (match-string-no-properties 2)) found)))))
    (nreverse found)))

(defun jetpacs-crud-orgapp--slug (title)
  "A view slug from TITLE: lowercase, runs of non-alphanumerics to dashes."
  (let ((slug (replace-regexp-in-string
               "\\`-+\\|-+\\'" ""
               (replace-regexp-in-string "[^a-z0-9]+" "-" (downcase title)))))
    (if (string-empty-p slug) "view" slug)))

(defun jetpacs-crud-orgapp--parse-coltypes (value file)
  "Parse a `:COLTYPES:' VALUE into the runtime's type list.
Tokens: text, number, date, checkbox, enum(A,B,C).  Unknown tokens are
an error naming FILE — the format is closed."
  (let ((start 0) (types nil))
    (while (string-match "\\([a-z]+\\)\\((\\([^)]*\\))\\)?" value start)
      (let ((token (match-string 1 value))
            (options (match-string 3 value)))
        (setq start (match-end 0))
        (push (pcase token
                ((or "text" "number" "date" "checkbox")
                 (when options
                   (user-error "%s: %s takes no options in COLTYPES" file token))
                 (intern token))
                ("enum"
                 (let ((opts (and options
                                  (cl-remove-if #'string-empty-p
                                                (mapcar #'string-trim
                                                        (split-string options ","))))))
                   (unless opts
                     (user-error "%s: enum needs options, e.g. enum(A,B)" file))
                   (cons 'enum opts)))
                (_ (user-error "%s: unknown column type %S in COLTYPES"
                               file token)))
              types)))
    (nreverse types)))

(defun jetpacs-crud-orgapp--parse-source (value app-file)
  "Parse a `:SOURCE:' VALUE: nil for inline, else (:file F :heading H).
Relative paths resolve against APP-FILE's directory."
  (let ((value (string-trim value)))
    (unless (string-equal-ignore-case value "inline")
      (let* ((split (split-string value "::" t "[ \t]+"))
             (path (car split))
             (target (cadr split))
             (heading (when target
                        (unless (string-prefix-p "*" target)
                          (user-error "%s: SOURCE target must be *Heading, got %S"
                                      app-file target))
                        (string-trim (substring target 1)))))
        (list :file (expand-file-name path (file-name-directory app-file))
              :heading heading)))))

(defun jetpacs-crud-orgapp--parse-view (app-file index)
  "Parse the view at the level-1 heading at point (0-based INDEX).
Point must be on the heading line."
  (let* ((title (string-trim (org-get-heading t t t t)))
         (prop (lambda (name) (org-entry-get (point) name)))
         (kind-raw (funcall prop "KIND"))
         (kind (pcase (and kind-raw (downcase (string-trim kind-raw)))
                 ((or `nil "table") 'table)
                 ("checklist" 'checklist)
                 (other (user-error "%s: unknown KIND %S under %S"
                                    app-file other title))))
         (source-raw (funcall prop "SOURCE"))
         (coltypes-raw (funcall prop "COLTYPES"))
         (columns-raw (funcall prop "COLUMNS"))
         (order-raw (funcall prop "ORDER")))
    (list :name (jetpacs-crud-orgapp--slug title)
          :title title
          :icon (or (funcall prop "ICON")
                    (if (eq kind 'checklist) "checklist" "table_chart"))
          :order (if order-raw (string-to-number order-raw)
                   (* 10 (1+ index)))
          :kind kind
          :source (and source-raw
                       (jetpacs-crud-orgapp--parse-source source-raw app-file))
          :coltypes (and coltypes-raw
                         (jetpacs-crud-orgapp--parse-coltypes coltypes-raw
                                                              app-file))
          :columns (and columns-raw
                        (cl-remove-if #'string-empty-p
                                      (mapcar #'string-trim
                                              (split-string columns-raw "|")))))))

;; ─── The parser ──────────────────────────────────────────────────────────────

(defun jetpacs-crud-parse-app (file)
  "Parse app FILE (docs/FORMAT.md) into a `jetpacs-crud-register' spec.
Signals `user-error' with FILE and a reason on any format violation."
  (unless (file-readable-p file)
    (user-error "Cannot read app file: %s" file))
  (with-temp-buffer
    (insert-file-contents file)
    (let ((org-inhibit-startup t))
      (delay-mode-hooks (org-mode)))
    (let* ((file (expand-file-name file))
           (keywords (jetpacs-crud-orgapp--keywords))
           (id (cdr (assoc "JETPACS_APP" keywords)))
           (format-v (cdr (assoc "JETPACS_APP_FORMAT" keywords)))
           (views nil))
      (unless id
        (user-error "%s: missing #+JETPACS_APP: keyword" file))
      (unless (string-match-p "\\`[a-z][a-z0-9-]*\\'" id)
        (user-error "%s: app id must match [a-z][a-z0-9-]*, got %S" file id))
      (when (and format-v (not (equal (string-trim format-v) "1")))
        (user-error "%s: unsupported JETPACS_APP_FORMAT %S" file format-v))
      (save-excursion
        (goto-char (point-min))
        (let ((index 0))
          (while (re-search-forward "^\\* " nil t)
            (beginning-of-line)
            (push (jetpacs-crud-orgapp--parse-view file index) views)
            (setq index (1+ index))
            (end-of-line))))
      (setq views (nreverse views))
      (unless views
        (user-error "%s: an app needs at least one view (a level-1 heading)"
                    file))
      (let ((slugs (mapcar (lambda (v) (plist-get v :name)) views)))
        (when (/= (length slugs) (length (delete-dups (copy-sequence slugs))))
          (user-error "%s: two views slugify to the same name; retitle one"
                      file)))
      (list :id id
            :label (or (cdr (assoc "TITLE" keywords)) (capitalize id))
            :icon (or (cdr (assoc "JETPACS_ICON" keywords)) "apps")
            :order (if-let ((o (cdr (assoc "JETPACS_ORDER" keywords))))
                       (string-to-number o)
                     100)
            :file file
            :views views))))

;; ─── Install surface ─────────────────────────────────────────────────────────

(defcustom jetpacs-crud-apps-directory
  (expand-file-name "jetpacs-crud/" user-emacs-directory)
  "Directory holding installed CRUD app documents (one .org per app).
The files here ARE the install registry: `jetpacs-crud-load-directory'
registers everything in it, so apps survive Emacs restarts with no
extra state."
  :type 'directory :group 'jetpacs)

(defun jetpacs-crud-register-file (file)
  "Parse and register app FILE; returns the app id.
Replaces any prior registration of the same id (live reload)."
  (jetpacs-crud-register (jetpacs-crud-parse-app file)))

(defun jetpacs-crud-install (id text)
  "Install app ID from document TEXT and register it.
Writes TEXT to `jetpacs-crud-apps-directory'/ID.org unless that file
already exists — an existing document may hold user data (inline
tables) and is never clobbered; delete it to reinstall fresh."
  (let ((file (expand-file-name (concat id ".org")
                                jetpacs-crud-apps-directory)))
    (unless (file-exists-p file)
      (make-directory jetpacs-crud-apps-directory t)
      (let ((coding-system-for-write 'utf-8))
        (with-temp-file file (insert text))))
    (jetpacs-crud-register-file file)))

(defun jetpacs-crud-load-directory (&optional directory)
  "Register every app document in DIRECTORY (default the apps directory).
A file that fails to parse warns and is skipped — one bad app must not
take down the rest.  Returns the list of registered ids."
  (let ((dir (or directory jetpacs-crud-apps-directory))
        (ids nil))
    (when (file-directory-p dir)
      (dolist (file (directory-files dir t "\\.org\\'"))
        (condition-case err
            (push (jetpacs-crud-register-file file) ids)
          (error (display-warning
                  'jetpacs-crud
                  (format "skipping %s: %s" file (error-message-string err))
                  :warning)))))
    (nreverse ids)))

(provide 'jetpacs-crud-orgapp)
;;; jetpacs-crud-orgapp.el ends here
