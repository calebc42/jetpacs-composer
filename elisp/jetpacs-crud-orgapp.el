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

(defconst jetpacs-crud-orgapp-format-version "3"
  "The only app.org format version accepted by this runtime.")

;; ─── Parsing helpers ─────────────────────────────────────────────────────────

(defconst jetpacs-crud-orgapp--keyword-re
  "^#\\+\\(JETPACS_APP\\|JETPACS_ICON\\|JETPACS_ORDER\\|JETPACS_APP_FORMAT\\|JETPACS_INBOX\\|JETPACS_DEPENDS\\|TITLE\\|TODO\\|TAGS\\):[ \t]*\\(.*?\\)[ \t]*$"
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

(defalias 'jetpacs-crud-orgapp--slug 'jetpacs-crud--slug
  "A view slug from a title; the canonical implementation lives in the runtime
so group destinations and view names slugify identically.")

(defun jetpacs-crud-orgapp--parse-coltypes (value file)
  "Parse a `:COLTYPES:' VALUE into the runtime's type list.
Tokens: text, number, date, checkbox, enum(A,B,C), and reserved ref(VIEW).
Unknown future tokens warn and degrade to text without rejecting the app."
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
                ("ref"
                 (let* ((parts (and options
                                    (mapcar #'string-trim
                                            (split-string options ","))))
                        (target (car parts))
                        (display (cadr parts)))
                   (unless (and target (not (string-empty-p target)))
                     (user-error "%s: ref needs a target view, e.g. ref(companies)" file))
                   (when (> (length parts) 2)
                     (user-error "%s: ref accepts target and optional display field" file))
                   (append (list 'ref target)
                           (when (and display (not (string-empty-p display)))
                             (list display)))))
                ("enum"
                 (let ((opts (and options
                                  (cl-remove-if #'string-empty-p
                                                (mapcar #'string-trim
                                                        (split-string options ","))))))
                   (unless opts
                     (user-error "%s: enum needs options, e.g. enum(A,B)" file))
                   (cons 'enum opts)))
                (_ (display-warning 'jetpacs-crud
                                    (format "%s: unknown column type %S in COLTYPES (treating as text)"
                                            file token)
                                    :warning)
                   (list 'unknown token)))
              types)))
    (nreverse types)))

(defconst jetpacs-crud-orgapp--special-props
  '("ITEM" "TODO" "DEADLINE" "SCHEDULED" "PRIORITY" "TAGS"
    "EFFORT" "CATEGORY")
  "Org special properties recognized in a `:SCHEMA:' (upcased on parse).")

(defun jetpacs-crud-orgapp--parse-schema (value file)
  "Parse a `:SCHEMA:' VALUE into a list of (PROP . LABEL).
Tokens are org column-view style: %PROP or %PROP(Label).  Property
names that case-insensitively match an org special property are
upcased; drawer property names keep their case (org reads them
case-insensitively anyway)."
  (let ((start 0) (fields nil))
    (while (string-match "%\\([A-Za-z_][A-Za-z_0-9-]*\\)\\((\\([^)]*\\))\\)?"
                         value start)
      (let* ((raw (match-string 1 value))
             (label (match-string 3 value))
             (prop (if (member (upcase raw) jetpacs-crud-orgapp--special-props)
                       (upcase raw)
                     raw)))
        (setq start (match-end 0))
        (push (cons prop label) fields)))
    (unless fields
      (user-error "%s: SCHEMA needs at least one %%PROP token" file))
    (let ((props (mapcar #'car fields)))
      (when (/= (length props)
                (length (delete-dups (mapcar #'upcase (copy-sequence props)))))
        (user-error "%s: duplicate property in SCHEMA" file)))
    (nreverse fields)))

(defun jetpacs-crud-orgapp--parse-actions (value file)
  "Validate recognized `:ACTIONS:' tokens and return trimmed VALUE.
Known tokens mirror `ActionDef'; unknown future tokens warn and are preserved."
  (let ((pos 0)
        (len (length value))
        (case-fold-search nil))
    (while (< pos len)
      (if (string-match-p "\\`[ \t]*\\'" (substring value pos))
          (setq pos len)
        (unless (and (string-match
                      "[ \t]*\\([a-z]+\\)\\((\\([^)]*\\))\\)?" value pos)
                     (= (match-beginning 0) pos))
          (user-error "%s: malformed action near %S in ACTIONS"
                      file (substring value pos)))
        (let* ((token (match-string 1 value))
               (options-form (match-string 2 value))
               (options (match-string 3 value))
               (end (match-end 0)))
          (when (and (< end len)
                     (not (memq (aref value end) '(?\s ?\t))))
            (user-error "%s: malformed action near %S in ACTIONS"
                        file (substring value pos)))
          (pcase token
            ("todo"
             (when (or (null options)
                       (string-empty-p (string-trim options)))
               (user-error "%s: todo() needs a keyword" file)))
            ((or "schedule" "deadline")
             (when options-form
               (user-error "%s: %s takes no options in ACTIONS" file token)))
            ((or "tags" "priority" "refile" "archive") nil)
            (_ (display-warning 'jetpacs-crud
                                (format "%s: unknown action %S in ACTIONS (ignoring)"
                                        file token)
                                :warning)
               (list 'unknown token)))
          (setq pos end))))
    (string-trim value)))

(defun jetpacs-crud-orgapp--parse-depends (value file)
  "Parse a `#+JETPACS_DEPENDS:' VALUE into a list of package-name strings.
Names are whitespace-separated and each must match [a-z][a-z0-9-]*.  This
is deployment metadata — the composer reads it to install the named
packages on the device (see docs/FORMAT.md); the runtime records it but
never acts on it, so an unfamiliar name here never blocks an app from
loading."
  (let ((names (split-string (or value "") "[ \t]+" t))
        (case-fold-search nil))     ; [a-z] must stay case-sensitive in batch
    (dolist (name names)
      (unless (string-match-p "\\`[a-z][a-z0-9-]*\\'" name)
        (user-error "%s: JETPACS_DEPENDS name must match [a-z][a-z0-9-]*, got %S"
                    file name)))
    names))

(defun jetpacs-crud-orgapp--parse-metrics (value file)
  "Parse closed dashboard metric VALUE into (OP FIELD?) entries."
  (let ((metrics
         (mapcar
          (lambda (raw)
            (let ((token (string-trim raw)))
              (cond
               ((equal token "count") '(count))
               ((string-match "\\`\\(sum\\|avg\\)(\\([^)]+\\))\\'" token)
                (list (intern (match-string 1 token))
                      (string-trim (match-string 2 token))))
               (t (user-error "%s: unknown dashboard metric %S" file token)))))
          (cl-remove-if #'string-empty-p (split-string value "|")))))
    (unless metrics (user-error "%s: METRICS needs at least one metric" file))
    metrics))

(defun jetpacs-crud-orgapp--parse-source (value app-file)
  "Parse a `:SOURCE:' VALUE.
nil for inline; (:dir D) for a trailing-slash vault directory (notes
views — one note file per record); else (:file F :heading H).  Relative
paths resolve against APP-FILE's directory."
  (let ((value (string-trim value)))
    (unless (string-equal-ignore-case value "inline")
      (if (string-prefix-p "pack:" value t)
          (let* ((slash (string-search "/" value))
                 (packId (substring value 5 slash))
                 (source (substring value (1+ slash))))
            (list :pack packId :source source))
        (if (string-suffix-p "/" value)
          (list :dir (file-name-as-directory
                      (expand-file-name value (file-name-directory app-file))))
        (let* ((split (split-string value "::" t "[ \t]+"))
               (path (car split))
               (target (cadr split))
               (heading (when target
                          (unless (string-prefix-p "*" target)
                            (user-error "%s: SOURCE target must be *Heading, got %S"
                                        app-file target))
                          (string-trim (substring target 1)))))
          (list :file (expand-file-name path (file-name-directory app-file))
                :heading heading)))))))

(defun jetpacs-crud-orgapp--parse-view (app-file index)
  "Parse the view at the level-1 heading at point (0-based INDEX).
Point must be on the heading line."
  (let* ((title (string-trim (org-get-heading t t t t)))
         (prop (lambda (name) (org-entry-get (point) name)))
         (kind-raw (funcall prop "KIND"))
         (kind (pcase (and kind-raw (downcase (string-trim kind-raw)))
                 ((or `nil "table") 'table)
                 ("checklist" 'checklist)
                 ("records" 'records)
                 ("notes" 'notes)
                 ("board" 'board)
                 ("calendar" 'calendar)
                 ("gallery" 'gallery)
                 ("tree" 'tree)
                 ("dashboard" 'dashboard)
                 ("gantt" 'gantt)
                 (other (display-warning 'jetpacs-crud
                                         (format "%s: unknown KIND %S under %S (falling back to unknown)"
                                                 app-file other title)
                                         :warning)
                        'unknown)))
         (source-raw (funcall prop "SOURCE"))
         (coltypes-raw (funcall prop "COLTYPES"))
         (columns-raw (funcall prop "COLUMNS"))
         (schema-raw (funcall prop "SCHEMA"))
         (filter-raw (funcall prop "FILTER"))
         (group-by-raw (funcall prop "GROUP_BY"))
         (date-field-raw (funcall prop "DATE_FIELD"))
         (image-field-raw (funcall prop "IMAGE_FIELD"))
         (metrics-raw (funcall prop "METRICS"))
         (on-raw (funcall prop "ON"))
         (rel-raw (funcall prop "REL"))
         (reminder-field-raw (funcall prop "DATEFIELD"))
         (actions-raw (funcall prop "ACTIONS"))
         (order-raw (funcall prop "ORDER"))
         (nav-raw (funcall prop "NAV"))
         (group-raw (funcall prop "GROUP")))
    (when (and (memq kind '(records notes board calendar gallery tree dashboard gantt)) (not schema-raw))
      (user-error "%s: a %s view needs a :SCHEMA: under %S"
                  app-file (symbol-name kind) title))
    (when (and (eq kind 'notes) (not source-raw))
      (user-error "%s: a notes view needs a :SOURCE: (a vault dir or \
file::*Heading) under %S" app-file title))
    (when (and (eq kind 'dashboard) (not metrics-raw))
      (user-error "%s: a dashboard view needs :METRICS: under %S"
                  app-file title))
    (when (and (not on-raw) (or rel-raw reminder-field-raw))
      (user-error "%s: :REL: and :DATEFIELD: require :ON: date-field under %S"
                  app-file title))
    (when on-raw
      (unless (equal (downcase (string-trim on-raw)) "date-field")
        (user-error "%s: :ON: must be date-field under %S" app-file title))
      (unless (and rel-raw reminder-field-raw)
        (user-error "%s: :ON: date-field needs :REL: and :DATEFIELD: under %S"
                    app-file title))
      (unless (string-match-p "\\`[+-]?[0-9]+d\\'" (string-trim rel-raw))
        (user-error "%s: :REL: must be whole days, e.g. -3d under %S"
                    app-file title)))
    (list :name (jetpacs-crud-orgapp--slug title)
          :title title
          :icon (or (funcall prop "ICON")
                    (pcase kind
                      ('checklist "checklist")
                      ('records "list_alt")
                      ('notes "sticky_note_2")
                      ('board "view_kanban")
                      ('calendar "calendar_month")
                      ('gallery "grid_view")
                      ('tree "account_tree")
                      ('dashboard "bar_chart")
                      ('gantt "view_timeline")
                      (_ "table_chart")))
          :order (if order-raw (string-to-number order-raw)
                   (* 10 (1+ index)))
          :kind kind
          :source (and source-raw
                       (jetpacs-crud-orgapp--parse-source source-raw app-file))
          :coltypes (and coltypes-raw
                         (jetpacs-crud-orgapp--parse-coltypes coltypes-raw
                                                              app-file))
          :columns (and columns-raw
                        (delq nil
                              (mapcar (lambda (s)
                                        (let ((trimmed (string-trim s)))
                                          (unless (string-empty-p trimmed)
                                            trimmed)))
                                      (split-string columns-raw "|"))))
          :schema (and schema-raw
                       (jetpacs-crud-orgapp--parse-schema schema-raw app-file))
          :filter (and filter-raw (string-trim filter-raw))
          :group-by (and group-by-raw (string-trim group-by-raw))
          :date-field (and date-field-raw (string-trim date-field-raw))
          :image-field (and image-field-raw (string-trim image-field-raw))
          :metrics (and metrics-raw
                        (jetpacs-crud-orgapp--parse-metrics metrics-raw app-file))
          :reminder (and on-raw
                         (list :date-field (string-trim reminder-field-raw)
                               :relative-days
                               (string-to-number (string-remove-suffix
                                                  "d" (string-trim rel-raw)))))
          :actions (and actions-raw
                        (jetpacs-crud-orgapp--parse-actions actions-raw
                                                            app-file))
          :nav (pcase (and nav-raw (downcase (string-trim nav-raw)))
                 ((or `nil "tab") 'tab)
                 ("drawer" 'drawer)
                 (other (display-warning
                         'jetpacs-crud
                         (format "%s: unknown NAV %S under %S (treating as tab)"
                                 app-file other title)
                         :warning)
                        'tab))
          :group (and group-raw
                      (let ((g (string-trim group-raw)))
                        (unless (string-empty-p g) g))))))

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
      (when (and format-v
                 (> (string-to-number (string-trim format-v))
                    (string-to-number jetpacs-crud-orgapp-format-version)))
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
            :inbox (when-let ((raw (cdr (assoc "JETPACS_INBOX" keywords))))
                     (let ((path (string-trim raw)))
                       (unless (string-empty-p path)
                         (when (string-suffix-p "/" path)
                           (user-error "%s: JETPACS_INBOX must name an org file" file))
                         (expand-file-name path (file-name-directory file)))))
            :depends (when-let ((raw (cdr (assoc "JETPACS_DEPENDS" keywords))))
                       (jetpacs-crud-orgapp--parse-depends raw file))
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

;; ─── Update-in-place merge (preserve on-device data across redeploys) ────────
;;
;; A redeployed bundle carries the CURRENT structure but the composer's
;; template data.  The on-device document may hold data the user has since
;; edited (inline table rows, checklist items, records added on the phone).
;; The merge below adopts the new structure — file keywords, the view set
;; and order, prose, and every view's property drawer — while keeping each
;; still-present view's body verbatim.  Views are matched by heading title;
;; the level-1 heading is the section boundary (records are its level-2
;; children, so they ride along in the body).

(defun jetpacs-crud-orgapp--slurp (file)
  "FILE's contents as a UTF-8 string."
  (with-temp-buffer
    (let ((coding-system-for-read 'utf-8))
      (insert-file-contents file))
    (buffer-string)))

(defun jetpacs-crud-orgapp--write (file text)
  "Write TEXT to FILE as UTF-8, creating its directory."
  (make-directory (file-name-directory file) t)
  (let ((coding-system-for-write 'utf-8))
    (with-temp-file file (insert text))))

(defun jetpacs-crud-orgapp--drawer-end ()
  "From point on a heading line, the position where the entry's body begins.
Skips an immediately-following :PROPERTIES:...:END: drawer; otherwise the
line after the heading."
  (save-excursion
    (forward-line 1)
    (when (looking-at-p "[ \t]*:PROPERTIES:[ \t]*$")
      (when (re-search-forward "^[ \t]*:END:[ \t]*$" nil t)
        (forward-line 1)))
    (point)))

(defun jetpacs-crud-orgapp--section-end ()
  "From point on a level-1 heading line, the position of the next level-1
heading, or `point-max'."
  (save-excursion
    (forward-line 1)
    (if (re-search-forward "^\\* " nil t)
        (match-beginning 0)
      (point-max))))

(defun jetpacs-crud-orgapp--heading-bodies (text)
  "Alist (TITLE . BODY) for each level-1 heading in org TEXT.
BODY is everything under the heading after its property drawer, through
the next level-1 heading — the inline data and any records or prose.  The
first heading of a repeated title wins (titles are unique in a valid app)."
  (with-temp-buffer
    (let ((org-inhibit-startup t)) (delay-mode-hooks (org-mode)))
    (insert text)
    (goto-char (point-min))
    (let ((bodies nil))
      (while (re-search-forward "^\\* " nil t)
        (beginning-of-line)
        (let* ((title (string-trim (org-get-heading t t t t)))
               (end (jetpacs-crud-orgapp--section-end))
               (body (buffer-substring-no-properties
                      (jetpacs-crud-orgapp--drawer-end) end)))
          (unless (assoc title bodies)
            (push (cons title body) bodies))
          (goto-char end)))
      (nreverse bodies))))

(defun jetpacs-crud-orgapp--merge-preserving-data (new-text old-text)
  "Merge NEW-TEXT's structure with the per-view data in OLD-TEXT.
Adopts NEW-TEXT's keywords, view set, order, prose, and property drawers,
but replaces each shared view's body (matched by heading title) with
OLD-TEXT's — preserving inline table rows, checklist items, and records a
device may have edited.  A view only in OLD-TEXT is dropped; a view only
in NEW-TEXT keeps its template body.  Returns the merged text."
  (let ((old-bodies (jetpacs-crud-orgapp--heading-bodies old-text)))
    (with-temp-buffer
      (let ((org-inhibit-startup t)) (delay-mode-hooks (org-mode)))
      (insert new-text)
      (goto-char (point-min))
      (while (re-search-forward "^\\* " nil t)
        (beginning-of-line)
        (let* ((title (string-trim (org-get-heading t t t t)))
               (cell (assoc title old-bodies))
               (end (jetpacs-crud-orgapp--section-end)))
          (if (not cell)
              (goto-char end)
            (let ((body-start (jetpacs-crud-orgapp--drawer-end)))
              (delete-region body-start end)
              (goto-char body-start)
              (insert (cdr cell))))))
      (buffer-string))))

(defun jetpacs-crud-install (id text)
  "Install app ID from document TEXT and register it.
First install writes TEXT verbatim.  A later install (the file already
exists) UPDATES IN PLACE, preserving on-device data: TEXT's structure —
file keywords, the view set and order, prose, and every view's property
drawer — is adopted, while each view still present keeps its on-device
body (inline table rows, checklist items, records).  A view TEXT drops is
removed; a new view arrives with its template body.  Matching is by
heading title, so renaming a view in the composer resets that view's data.
If the merge cannot be parsed back (a corrupt on-device file), the
existing document is kept untouched rather than risk its data."
  (let ((file (expand-file-name (concat id ".org")
                                jetpacs-crud-apps-directory)))
    (if (not (file-exists-p file))
        (jetpacs-crud-orgapp--write file text)
      (condition-case err
          (let ((merged (jetpacs-crud-orgapp--merge-preserving-data
                         text (jetpacs-crud-orgapp--slurp file)))
                (tmp (make-temp-file "jetpacs-crud-merge" nil ".org")))
            (unwind-protect
                (progn
                  (jetpacs-crud-orgapp--write tmp merged)
                  (jetpacs-crud-parse-app tmp) ; validate before committing
                  (jetpacs-crud-orgapp--write file merged))
              (ignore-errors (delete-file tmp))))
        (error
         (display-warning
          'jetpacs-crud
          (format "%s: kept the on-device document; update merge failed: %s"
                  id (error-message-string err))
          :warning))))
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
