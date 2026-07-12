;;; jetpacs-crud.el --- Declarative CRUD apps over org tables -*- lexical-binding: t; -*-

;; Copyright (C) 2026 calebc42 and contributors
;; SPDX-License-Identifier: GPL-3.0-or-later

;; The jetpacs-composer runtime: a generic CRUD engine that turns a
;; declarative app spec (see docs/FORMAT.md, usually parsed from an
;; `app.org' by jetpacs-crud-orgapp.el) into a registered Jetpacs Tier-1
;; app.  Org tables are the datasources — the header row is the schema,
;; `:COLTYPES:' types the columns — and org files are the backend: every
;; mutation goes through org-mode's own table/checkbox machinery, saves,
;; and repushes.
;;
;; Depends only on the Jetpacs foundation (jetpacs-core).  No Glasspane,
;; no org-agenda, no code in the app documents — the wire vocabulary is
;; the closed `crud.*' action set below, and handlers resolve every file
;; from the registered spec rather than trusting paths off the wire.
;;
;; Positions can never go stale: views re-scan their source table on
;; every push, and every mutation ends in save + repush.

;;; Code:

(require 'cl-lib)
(require 'org)
(require 'org-table)
(require 'org-element)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-apps)

;; ─── Registry ────────────────────────────────────────────────────────────────

(defvar jetpacs-crud--apps nil
  "Alist of (ID . SPEC) for every registered CRUD app.
SPEC is the plist described in docs/FORMAT.md: :id :label :icon :order
:file and :views, where each view is a plist :name :title :icon :order
:kind :source :coltypes :columns.")

(defun jetpacs-crud--app (id)
  "The registered spec for app ID, or nil."
  (cdr (assoc id jetpacs-crud--apps)))

(defun jetpacs-crud--view (spec name)
  "The view plist named NAME in app SPEC, or nil."
  (cl-find name (plist-get spec :views)
           :key (lambda (v) (plist-get v :name)) :test #'equal))

(defun jetpacs-crud--view-source (spec view)
  "Resolve VIEW's datasource in app SPEC to a cons (FILE . HEADING).
An inline source is the app file itself with the view's own heading."
  (let ((source (plist-get view :source)))
    (if source
        (cons (plist-get source :file) (plist-get source :heading))
      (cons (plist-get spec :file) (plist-get view :title)))))

;; ─── Column types ────────────────────────────────────────────────────────────

(defun jetpacs-crud--coltype (view col)
  "The type of 1-based data column COL in VIEW.
A symbol `text' / `number' / `date' / `checkbox', or (enum OPTIONS...).
Columns beyond the declared list are `text'."
  (or (nth (1- col) (plist-get view :coltypes)) 'text))

(defun jetpacs-crud--checkbox-col-p (view col)
  "Non-nil when COL of VIEW is a checkbox column."
  (eq (jetpacs-crud--coltype view col) 'checkbox))

(defun jetpacs-crud--sanitize-field (text)
  "TEXT made safe for an org table field: one line, no raw pipes."
  (string-replace "|" "\\vert{}" (string-replace "\n" " " text)))

(defun jetpacs-crud--prompt-value (label type &optional current)
  "Prompt for a LABEL value of TYPE, seeded with CURRENT; validate.
Inside an action handler the prompt surfaces as a native dialog on the
phone (the minibuffer bridge).  Signals `user-error' on invalid input."
  (pcase type
    (`(enum . ,options)
     (completing-read (format "%s: " label) options nil t nil nil
                      (or current (car options))))
    ('number
     (let ((input (string-trim (read-string (format "%s (number): " label)
                                            current))))
       (unless (or (string-empty-p input)
                   (string-match-p "\\`-?[0-9]+\\(\\.[0-9]+\\)?\\'" input))
         (user-error "Not a number: %s" input))
       input))
    ('date
     (let ((input (string-trim (read-string (format "%s (YYYY-MM-DD): " label)
                                            current))))
       (unless (or (string-empty-p input)
                   (string-match-p
                    "\\`[0-9]\\{4\\}-[0-9]\\{2\\}-[0-9]\\{2\\}\\'" input))
         (user-error "Not a date (YYYY-MM-DD): %s" input))
       input))
    (_ (read-string (format "%s: " label) current))))

;; ─── Locating and scanning source tables ─────────────────────────────────────

(defun jetpacs-crud--goto-heading (heading)
  "Move point to the line of level-anything HEADING in the current buffer.
Returns point, or nil when the heading doesn't exist.  Exact title match."
  (let ((found (org-find-exact-headline-in-buffer heading nil t)))
    (when found (goto-char found))))

(defun jetpacs-crud--scope-bounds (heading)
  "Bounds (BEG . END) of the search scope: HEADING's subtree, or the buffer.
Point ends at BEG.  nil when HEADING is given but missing."
  (if (null heading)
      (progn (goto-char (point-min)) (cons (point-min) (point-max)))
    (when (jetpacs-crud--goto-heading heading)
      (cons (point) (save-excursion (org-end-of-subtree t t) (point))))))

(defun jetpacs-crud--find-table (heading)
  "Position of the first org table within HEADING's scope, or nil.
Leaves point at the table's first line when found."
  (let ((bounds (jetpacs-crud--scope-bounds heading)))
    (when bounds
      (when (re-search-forward org-table-line-regexp (cdr bounds) t)
        (goto-char (org-table-begin))
        (point)))))

(defun jetpacs-crud--scan-table ()
  "Scan the org table at point into a plist (:begin :end :rows :ncols).
:rows is an ordered list — the symbol `hline', or a list of cells
\(TEXT . POS) where POS is a buffer position inside the field (valid
for `org-table-current-column' and `org-table-get-field')."
  (let ((beg (org-table-begin))
        (end (org-table-end))
        (rows nil)
        (ncols 0))
    (save-excursion
      (goto-char beg)
      (while (< (point) end)
        (if (org-at-table-hline-p)
            (push 'hline rows)
          (let* ((lbeg (line-beginning-position))
                 (line (buffer-substring-no-properties
                        lbeg (line-end-position)))
                 (pipes (cl-loop for i from 0 below (length line)
                                 when (eq (aref line i) ?|) collect i))
                 (cells nil))
            (cl-loop for (a b) on pipes while a do
                     (let ((text (string-trim
                                  (substring line (1+ a) (or b (length line))))))
                       ;; After the trailing pipe there is no cell; an
                       ;; unterminated last segment is one only when it
                       ;; has content.
                       (when (or b (not (string-empty-p text)))
                         (push (cons text (+ lbeg a 1)) cells))))
            (setq cells (nreverse cells))
            (setq ncols (max ncols (length cells)))
            (push cells rows)))
        (forward-line 1)))
    (list :begin beg :end end :rows (nreverse rows) :ncols ncols)))

(defmacro jetpacs-crud--with-source (file &rest body)
  "Run BODY in FILE's buffer, widened, saving excursion.
The buffer is the source of truth (unsaved edits included)."
  (declare (indent 1) (debug (form body)))
  `(with-current-buffer (find-file-noselect ,file)
     (org-with-wide-buffer ,@body)))

(defun jetpacs-crud--source-table (spec view)
  "Scan VIEW's source table: (FILE . TABLE) where TABLE is a scan plist.
Returns (FILE . nil) when the source file or table doesn't exist."
  (let* ((source (jetpacs-crud--view-source spec view))
         (file (car source))
         (heading (cdr source)))
    (cons file
          (when (file-readable-p file)
            (jetpacs-crud--with-source file
              (when (jetpacs-crud--find-table heading)
                (jetpacs-crud--scan-table)))))))

;; ─── Scaffolding missing sources ─────────────────────────────────────────────

(defun jetpacs-crud--scaffold-source (spec view)
  "Create VIEW's external source file when missing and scaffoldable.
Table views need `:columns' for the header row; checklist views get the
heading alone.  Inline sources and existing files are left untouched."
  (let* ((source (plist-get view :source))
         (file (and source (plist-get source :file)))
         (heading (and source (plist-get source :heading)))
         (columns (plist-get view :columns)))
    (when (and file (not (file-exists-p file))
               (or columns (memq (plist-get view :kind) '(checklist records))))
      (make-directory (file-name-directory file) t)
      (let ((coding-system-for-write 'utf-8))
        (with-temp-file file
          (when heading (insert "* " heading "\n\n"))
          (when (and columns (eq (plist-get view :kind) 'table))
            (insert "| " (mapconcat #'identity columns " | ") " |\n")
            (insert "|" (mapconcat (lambda (_) "---") columns "+") "|\n"))))
      (message "jetpacs-crud: scaffolded %s for %s.%s"
               file (plist-get spec :id) (plist-get view :name)))))

;; ─── Rendering: table views ──────────────────────────────────────────────────

(defun jetpacs-crud--cell-display (view col text)
  "The display string for TEXT in data column COL of VIEW."
  (if (jetpacs-crud--checkbox-col-p view col)
      (if (string-match-p "\\`\\[[xX]\\]\\'" text) "☑" "☐")
    text))

(defun jetpacs-crud--cell-node (spec view col cell &optional header)
  "Build the table-cell node for CELL (TEXT . POS) at data column COL.
HEADER cells are inert; body cells tap-edit (or tap-toggle for checkbox
columns) and long-press for the row menu."
  (let* ((text (car cell))
         (pos (cdr cell))
         (args `((app . ,(plist-get spec :id))
                 (view . ,(plist-get view :name))
                 (pos . ,pos))))
    (jetpacs-table-cell
     (list (jetpacs-span (if header text
                           (jetpacs-crud--cell-display view col text))))
     :on-tap (unless header
               (jetpacs-action (if (jetpacs-crud--checkbox-col-p view col)
                                   "crud.cell.toggle" "crud.cell.edit")
                            :args args))
     :on-long-tap (unless header
                    (jetpacs-action "crud.row.menu" :args args
                                 :when-offline "drop")))))

(defun jetpacs-crud--table-aligns (view ncols)
  "Column aligns for VIEW's NCOLS columns: number columns end-align.
nil when every column is start-aligned (no wire noise)."
  (let ((aligns (cl-loop for col from 1 to ncols
                         collect (if (eq (jetpacs-crud--coltype view col)
                                         'number)
                                     "end" "start"))))
    (and (member "end" aligns) aligns)))

(defun jetpacs-crud--table-node (spec view table)
  "The `jetpacs-table' node for scan plist TABLE of VIEW in SPEC.
The first non-rule row renders as the header."
  (let ((add-args `((app . ,(plist-get spec :id))
                    (view . ,(plist-get view :name))))
        (header-seen nil))
    (jetpacs-table
     (mapcar (lambda (row)
               (if (eq row 'hline)
                   (jetpacs-table-rule)
                 (let ((header (not header-seen)))
                   (setq header-seen t)
                   (jetpacs-table-row
                    (cl-loop for cell in row
                             for col from 1
                             collect (jetpacs-crud--cell-node
                                      spec view col cell header))
                    :header header))))
             (plist-get table :rows))
     :aligns (jetpacs-crud--table-aligns view (plist-get table :ncols))
     :on-add-row (jetpacs-action "crud.row.add" :args add-args))))

(defun jetpacs-crud--table-body (spec view)
  "The body node for table VIEW of SPEC: the live table, or an empty state."
  (let* ((scan (jetpacs-crud--source-table spec view))
         (table (cdr scan)))
    (jetpacs-lazy-column
     (if table
         (jetpacs-crud--table-node spec view table)
       (jetpacs-empty-state
        :icon "table_chart"
        :title "No data yet"
        :caption (format "No table found in %s" (car scan)))))))

;; ─── Rendering: checklist views ──────────────────────────────────────────────

(defconst jetpacs-crud--checkbox-item-re
  "^[ \t]*[-+*] +\\[\\([ xX-]\\)\\] +\\(.*\\)$"
  "Matches a checkbox plain-list item: state in group 1, text in group 2.")

(defun jetpacs-crud--scan-checklist (heading)
  "Checkbox items in HEADING's scope: a list of (STATE TEXT POS).
STATE is the checkbox character; POS the item's line start."
  (let ((bounds (jetpacs-crud--scope-bounds heading))
        (items nil))
    (when bounds
      (while (re-search-forward jetpacs-crud--checkbox-item-re (cdr bounds) t)
        (push (list (match-string-no-properties 1)
                    (match-string-no-properties 2)
                    (line-beginning-position))
              items)))
    (nreverse items)))

(defun jetpacs-crud--checklist-item-node (spec view item)
  "The row node for checklist ITEM (STATE TEXT POS) of VIEW in SPEC."
  (pcase-let ((`(,state ,text ,pos) item))
    (let ((done (member state '("x" "X"))))
      (jetpacs-row
       (jetpacs-box
        (list (jetpacs-icon (cond (done "check_box")
                               ((equal state "-") "indeterminate_check_box")
                               (t "check_box_outline_blank"))
                         :size 22))
        :on-tap (jetpacs-action "crud.checkbox.toggle"
                             :args `((app . ,(plist-get spec :id))
                                     (view . ,(plist-get view :name))
                                     (pos . ,pos)))
        :padding 4)
       (jetpacs-box
        (list (jetpacs-rich-text (list (jetpacs-span text :strike done))))
        :weight 1 :padding 4)
       :align "center"))))

(defun jetpacs-crud--checklist-body (spec view)
  "The body node for checklist VIEW of SPEC."
  (let* ((source (jetpacs-crud--view-source spec view))
         (file (car source))
         (items (when (file-readable-p file)
                  (jetpacs-crud--with-source file
                    (jetpacs-crud--scan-checklist (cdr source))))))
    (apply #'jetpacs-lazy-column
           (or (mapcar (lambda (item)
                         (jetpacs-crud--checklist-item-node spec view item))
                       items)
               (list (jetpacs-empty-state
                      :icon "checklist"
                      :title "Nothing here yet"
                      :caption "Tap + to add the first item"))))))

;; ─── Records: headings + property drawers, org's native record shape ────────
;;
;; Core org is the engine here, on purpose (the jetpacs thesis applied
;; to data): `org-entry-get'/`org-entry-put' are the field accessors —
;; and they treat ITEM/TODO/DEADLINE/SCHEDULED/PRIORITY as first-class,
;; so a TODO field cycles the file's real keyword sequence and DEADLINE
;; writes a real planning line.  Filtering is `org-map-entries' with
;; org's own match syntax; enum options come from org's allowed-values
;; convention (PROP_ALL).  See FORMAT.md "What the runtime does to your
;; files" for the layout-normalization contract this buys us into.

(defun jetpacs-crud--schema-props (view)
  "VIEW's schema as a list of (PROP . LABEL)."
  (plist-get view :schema))

(defun jetpacs-crud--field-type (view prop)
  "The declared type of PROP in VIEW (positional in the schema)."
  (let ((idx (cl-position prop (jetpacs-crud--schema-props view)
                          :key #'car :test #'equal)))
    (or (and idx (nth idx (plist-get view :coltypes))) 'text)))

(defun jetpacs-crud--scan-records (spec view)
  "VIEW's records: a list of plists (:pos POS :fields ALIST).
Records are the direct children of the source heading (level-1
headings of the file when the source names none), filtered by the
view's org match string.  Fields are read via `org-entry-get', so
special properties come from org itself."
  (let* ((source (jetpacs-crud--view-source spec view))
         (file (car source))
         (heading (cdr source))
         (props (mapcar #'car (jetpacs-crud--schema-props view))))
    (when (file-readable-p file)
      (jetpacs-crud--with-source file
        (save-restriction
          (let ((base 0))
            (when heading
              (unless (jetpacs-crud--goto-heading heading)
                (user-error "Heading %s not found in %s" heading file))
              (setq base (org-outline-level))
              (org-narrow-to-subtree))
            (let ((target (1+ base))
                  (records nil))
              (org-map-entries
               (lambda ()
                 (when (= (org-outline-level) target)
                   (push (list :pos (point)
                               :fields (mapcar
                                        (lambda (p)
                                          (cons p (or (org-entry-get (point) p)
                                                      "")))
                                        props))
                         records)))
               (let ((filter (plist-get view :filter)))
                 (and filter (not (string-empty-p filter)) filter)))
              (nreverse records))))))))

(defun jetpacs-crud--record-card (spec view record)
  "The card node for RECORD of VIEW in SPEC."
  (let* ((fields (plist-get record :fields))
         (pos (plist-get record :pos))
         (schema (jetpacs-crud--schema-props view))
         (title (let ((item (alist-get "ITEM" fields nil nil #'equal)))
                  (if (and item (not (string-empty-p item))) item "Untitled")))
         (todo (alist-get "TODO" fields nil nil #'equal))
         (args-for (lambda (prop)
                     `((app . ,(plist-get spec :id))
                       (view . ,(plist-get view :name))
                       (pos . ,pos)
                       ,@(when prop `((prop . ,prop)))))))
    (jetpacs-card
     (list
      (jetpacs-column
       (jetpacs-row
        (jetpacs-box
         (list (jetpacs-text (if (and todo (not (string-empty-p todo)))
                               (format "%s %s" todo title)
                             title)
                          'title))
         :weight 1
         :on-tap (when (cl-find "ITEM" schema :key #'car :test #'equal)
                   (jetpacs-action "crud.field.edit"
                                :args (funcall args-for "ITEM"))))
        (jetpacs-menu
         (list (jetpacs-menu-item "Delete record"
                               (jetpacs-action "crud.record.menu"
                                            :args (funcall args-for nil))
                               :icon "delete"))))
       (apply #'jetpacs-column
              (cl-loop for (prop . label) in schema
                       unless (member prop '("ITEM" "TODO"))
                       collect
                       (let ((value (alist-get prop fields nil nil #'equal)))
                         (jetpacs-box
                          (list (jetpacs-row
                                 (jetpacs-text (or label prop) 'caption nil nil nil 1)
                                 (jetpacs-spacer :width 12)
                                 (jetpacs-text (if (string-empty-p value) "—" value)
                                            'body 1)))
                          :on-tap (jetpacs-action "crud.field.edit"
                                               :args (funcall args-for prop))
                          :padding 2)))))))))

(defun jetpacs-crud--records-body (spec view)
  "The body node for records VIEW of SPEC."
  (let ((records (jetpacs-crud--scan-records spec view)))
    (apply #'jetpacs-lazy-column
           (or (mapcar (lambda (r) (jetpacs-crud--record-card spec view r))
                       records)
               (list (jetpacs-empty-state
                      :icon "list_alt"
                      :title "No records"
                      :caption (if (plist-get view :filter)
                                   "Nothing matches the view's filter"
                                 "Tap + to add the first record")))))))

;; ─── The view builder ────────────────────────────────────────────────────────

(defun jetpacs-crud--fab (spec view)
  "The add FAB for VIEW of SPEC."
  (let ((args `((app . ,(plist-get spec :id))
                (view . ,(plist-get view :name)))))
    (jetpacs-fab "add"
              :on-tap (jetpacs-action (pcase (plist-get view :kind)
                                        ('checklist "crud.item.add")
                                        ('records "crud.record.add")
                                        (_ "crud.row.add"))
                                   :args args))))

(defun jetpacs-crud--build-view (id name snackbar)
  "Build the full scaffold view for app ID's view NAME."
  (let* ((spec (or (jetpacs-crud--app id)
                   (error "CRUD app %s is not registered" id)))
         (view (or (jetpacs-crud--view spec name)
                   (error "CRUD app %s has no view %s" id name))))
    (jetpacs-shell-tab-view
     (format "%s.%s" id name)
     (pcase (plist-get view :kind)
       ('checklist (jetpacs-crud--checklist-body spec view))
       ('records (jetpacs-crud--records-body spec view))
       (_ (jetpacs-crud--table-body spec view)))
     :top-bar (jetpacs-shell-default-top-bar (plist-get view :title))
     :fab (jetpacs-crud--fab spec view)
     :snackbar snackbar)))

;; ─── Registration ────────────────────────────────────────────────────────────

(defun jetpacs-crud-register (spec)
  "Register SPEC (docs/FORMAT.md) as a live Jetpacs app.
Re-registering an id replaces it wholesale (the live-reload path).
Missing external sources are scaffolded when the view allows it.
Returns the app id."
  (let ((id (plist-get spec :id)))
    (unless (and (stringp id) (string-match-p "\\`[a-z][a-z0-9-]*\\'" id))
      (user-error "Invalid app id: %S" id))
    (when (jetpacs-crud--app id)
      (jetpacs-crud-unregister id))
    (dolist (view (plist-get spec :views))
      (jetpacs-crud--scaffold-source spec view))
    (setf (alist-get id jetpacs-crud--apps nil nil #'equal) spec)
    (with-jetpacs-owner id
      (dolist (view (plist-get spec :views))
        (let ((name (plist-get view :name)))
          (jetpacs-shell-define-view (format "%s.%s" id name)
            :builder (lambda (snackbar)
                       (jetpacs-crud--build-view id name snackbar))
            :tab (list :icon (plist-get view :icon)
                       :label (plist-get view :title))
            :order (plist-get view :order))))
      (jetpacs-defapp id
        :label (plist-get spec :label)
        :icon (plist-get spec :icon)
        :views (mapcar (lambda (v)
                         (format "%s.%s" id (plist-get v :name)))
                       (plist-get spec :views))
        :order (plist-get spec :order)))
    id))

(defun jetpacs-crud-unregister (id)
  "Tear down CRUD app ID: its views, launcher entry, and registry row."
  (jetpacs-app-unregister id)
  (setf (alist-get id jetpacs-crud--apps nil t #'equal) nil)
  id)

;; ─── Action handlers (the closed crud.* vocabulary) ──────────────────────────

(defun jetpacs-crud--resolve (args &optional need-pos)
  "Resolve wire ARGS to (SPEC VIEW FILE POS); validate everything.
The file comes from the registered spec, never from the wire, so a
handler can only ever touch a declared source.  Signals `user-error'
on anything unknown."
  (let* ((id (alist-get 'app args))
         (name (alist-get 'view args))
         (pos (alist-get 'pos args))
         (spec (or (jetpacs-crud--app id)
                   (user-error "Unknown CRUD app: %S" id)))
         (view (or (jetpacs-crud--view spec name)
                   (user-error "Unknown view %S in app %s" name id)))
         (file (car (jetpacs-crud--view-source spec view))))
    (when (and need-pos (not (integerp pos)))
      (user-error "Missing position in %S" args))
    (unless (file-readable-p file)
      (user-error "Source file missing: %s" file))
    (list spec view file pos)))

(defun jetpacs-crud--table-mutate (file pos fn)
  "Run FN with point at POS inside FILE's table; align, recalc, save, push.
The jetpacs-crud port of the proven Glasspane table-mutation shape: after
FN the table is realigned, recalculated when a #+TBLFM follows it, and
the buffer saved.  A mutation that consumes the whole table skips the
realign instead of erroring.  Ends in a full repush — positions on the
phone are refreshed before the user can tap again."
  (with-current-buffer (find-file-noselect file)
    (org-with-wide-buffer
     (goto-char pos)
     (unless (org-at-table-p) (user-error "No table at position %s" pos))
     (let ((table-beg (org-table-begin)))
       (funcall fn)
       (unless (org-at-table-p)
         (goto-char (min table-beg (point-max))))
       (when (org-at-table-p)
         (org-table-align)
         (when (save-excursion
                 (goto-char (org-table-end))
                 (let ((case-fold-search t))
                   (looking-at-p "[ \t]*#\\+TBLFM:")))
           (org-table-recalculate t)))))
    (let ((save-silently t))
      (save-buffer)))
  (jetpacs-shell-push))

(defun jetpacs-crud--cell-context (file pos)
  "Column number and trimmed current text of the field at POS in FILE."
  (jetpacs-crud--with-source file
    (goto-char pos)
    (unless (org-at-table-p) (user-error "No table cell at position %s" pos))
    (cons (org-table-current-column)
          (string-trim (org-table-get-field)))))

(defun jetpacs-crud--column-label (spec view col)
  "The header name of data column COL in VIEW, else \"Column COL\"."
  (let* ((table (cdr (jetpacs-crud--source-table spec view)))
         (header (and table (cl-find-if #'listp (plist-get table :rows)))))
    (or (car (nth (1- col) header)) (format "Column %d" col))))

(defun jetpacs-crud-action-cell-edit (args _payload)
  "Edit the table field at the tapped cell, typed by its column."
  (pcase-let* ((`(,spec ,view ,file ,pos) (jetpacs-crud--resolve args t))
               (`(,col . ,current) (jetpacs-crud--cell-context file pos))
               (type (jetpacs-crud--coltype view col)))
    (if (eq type 'checkbox)
        (jetpacs-crud-action-cell-toggle args nil)
      (let ((input (jetpacs-crud--prompt-value
                    (jetpacs-crud--column-label spec view col) type current)))
        (jetpacs-crud--table-mutate file pos
          (lambda ()
            (org-table-get-field nil (jetpacs-crud--sanitize-field input))))))))

(defun jetpacs-crud-action-cell-toggle (args _payload)
  "Toggle the checkbox-column field at the tapped cell."
  (pcase-let* ((`(,_spec ,_view ,file ,pos) (jetpacs-crud--resolve args t))
               (`(,_col . ,current) (jetpacs-crud--cell-context file pos)))
    (let ((new (if (string-match-p "\\`\\[[xX]\\]\\'" current) "[ ]" "[X]")))
      (jetpacs-crud--table-mutate file pos
        (lambda () (org-table-get-field nil new))))))

(defun jetpacs-crud--locate-table (spec view file)
  "Position of VIEW's source table in FILE, or `user-error'."
  (or (jetpacs-crud--with-source file
        (jetpacs-crud--find-table (cdr (jetpacs-crud--view-source spec view))))
      (user-error "No source table for %s.%s"
                  (plist-get spec :id) (plist-get view :name))))

(defun jetpacs-crud-action-row-add (args _payload)
  "Append a row: one typed prompt per column, checkbox columns default [ ]."
  (pcase-let* ((`(,spec ,view ,file ,_pos) (jetpacs-crud--resolve args))
               (table-pos (jetpacs-crud--locate-table spec view file)))
    (let* ((table (jetpacs-crud--with-source file
                    (goto-char table-pos)
                    (jetpacs-crud--scan-table)))
           (header (cl-find-if #'listp (plist-get table :rows)))
           (ncols (plist-get table :ncols))
           (values
            (cl-loop for col from 1 to ncols
                     for label = (or (car (nth (1- col) header))
                                     (format "Column %d" col))
                     for type = (jetpacs-crud--coltype view col)
                     collect (if (eq type 'checkbox) "[ ]"
                               (jetpacs-crud--sanitize-field
                                (jetpacs-crud--prompt-value label type))))))
      (jetpacs-crud--table-mutate file table-pos
        (lambda ()
          (goto-char (org-table-end))
          (forward-line -1)
          (org-table-insert-row t)
          (cl-loop for value in values
                   for col from 1
                   unless (string-empty-p value)
                   do (org-table-goto-column col)
                   (org-table-get-field nil value)))))))

(defun jetpacs-crud-action-row-menu (args _payload)
  "Long-press row menu: insert above, delete, or edit the cell."
  (pcase-let* ((`(,_spec ,_view ,file ,pos) (jetpacs-crud--resolve args t)))
    (pcase (completing-read "Row: "
                            '("Insert row above" "Delete row" "Edit cell")
                            nil t)
      ("Insert row above"
       (jetpacs-crud--table-mutate file pos #'org-table-insert-row))
      ("Delete row"
       (jetpacs-crud--table-mutate file pos #'org-table-kill-row))
      ("Edit cell"
       (jetpacs-crud-action-cell-edit args nil)))))

(defun jetpacs-crud-action-checkbox-toggle (args _payload)
  "Toggle the checklist item at the tapped position."
  (pcase-let* ((`(,_spec ,_view ,file ,pos) (jetpacs-crud--resolve args t)))
    (with-current-buffer (find-file-noselect file)
      (org-with-wide-buffer
       (goto-char pos)
       (unless (looking-at-p jetpacs-crud--checkbox-item-re)
         (user-error "No checkbox item at position %s" pos))
       (org-toggle-checkbox))
      (let ((save-silently t))
        (save-buffer)))
    (jetpacs-shell-push)))

(defun jetpacs-crud-action-item-add (args _payload)
  "Append a new unchecked item to the checklist."
  (pcase-let* ((`(,spec ,view ,file ,_pos) (jetpacs-crud--resolve args)))
    (let ((text (string-trim (read-string "New item: "))))
      (when (string-empty-p text) (user-error "Empty item"))
      (with-current-buffer (find-file-noselect file)
        (org-with-wide-buffer
         (let* ((heading (cdr (jetpacs-crud--view-source spec view)))
                (bounds (or (jetpacs-crud--scope-bounds heading)
                            (user-error "Heading not found in %s" file)))
                (items (progn (goto-char (car bounds))
                              (jetpacs-crud--scan-checklist heading)))
                (last-pos (caddr (car (last items)))))
           (if last-pos
               (progn (goto-char last-pos) (end-of-line)
                      (insert "\n- [ ] " text))
             (goto-char (cdr bounds))
             (unless (bolp) (insert "\n"))
             (insert "- [ ] " text "\n"))))
        (let ((save-silently t))
          (save-buffer))))
    (jetpacs-shell-push)))

;; ─── Record actions ──────────────────────────────────────────────────────────

(defun jetpacs-crud--record-mutate (file pos fn)
  "Run FN with point at the record heading at POS in FILE; save, push."
  (with-current-buffer (find-file-noselect file)
    (org-with-wide-buffer
     (goto-char pos)
     (unless (org-at-heading-p)
       (user-error "No record at position %s" pos))
     (funcall fn))
    (let ((save-silently t))
      (save-buffer)))
  (jetpacs-shell-push))

(defun jetpacs-crud--current-date (value)
  "The YYYY-MM-DD inside a planning VALUE like \"<2027-01-01 Fri>\"."
  (when (and value (string-match "[0-9]\\{4\\}-[0-9]\\{2\\}-[0-9]\\{2\\}" value))
    (match-string 0 value)))

(defun jetpacs-crud--allowed-values (pom prop)
  "Org's allowed values for PROP at POM: (STRINGS . REQUIRE-MATCH), or nil.
Wraps `org-property-get-allowed-values' (the PROP_ALL convention, and
the real keyword sequence for TODO).  PRIORITY letters arrive as
characters — normalized to strings.  An :ETC declaration marks the
list open-ended (org's `org-unrestricted' text property), which
relaxes the match requirement."
  (let ((raw (org-property-get-allowed-values pom prop)))
    (when raw
      (let ((strings (delq nil (mapcar (lambda (x)
                                         (cond ((consp x) (car x))
                                               ((characterp x)
                                                (char-to-string x))
                                               ((stringp x) x)))
                                       raw))))
        (when strings
          (cons strings
                (not (get-text-property 0 'org-unrestricted
                                        (car strings)))))))))

(defun jetpacs-crud--field-remove (prop)
  "Remove PROP from the record at point, through org's own commands.
`org-entry-put' silently ignores nil for the special properties, so
each gets its explicit removal form."
  (pcase prop
    ("TODO" (org-todo 'none))
    ("DEADLINE" (org-deadline '(4)))
    ("SCHEDULED" (org-schedule '(4)))
    ("PRIORITY" (org-priority 'remove))
    (_ (org-entry-delete (point) prop))))

(defun jetpacs-crud-action-field-edit (args _payload)
  "Edit one field of the record at the tapped position.
Special properties run org's own machinery via `org-entry-put':
TODO writes the file's real keyword sequence, DEADLINE/SCHEDULED write
planning lines.  Enum choices come from org's allowed-values
convention (PROP_ALL, and the keyword sequence for TODO) when the file
declares them, else from the declared column type."
  (pcase-let* ((`(,spec ,view ,file ,pos) (jetpacs-crud--resolve args t))
               (prop (alist-get 'prop args)))
    (unless (cl-find prop (jetpacs-crud--schema-props view)
                     :key #'car :test #'equal)
      (user-error "Field %S is not in the view's schema" prop))
    (let* ((label (or (cdr (cl-find prop (jetpacs-crud--schema-props view)
                                    :key #'car :test #'equal))
                      prop))
           current allowed)
      (jetpacs-crud--with-source file
        (goto-char pos)
        (unless (org-at-heading-p)
          (user-error "No record at position %s" pos))
        (setq current (or (org-entry-get (point) prop) ""))
        (setq allowed (jetpacs-crud--allowed-values (point) prop)))
      (cond
       ((equal prop "ITEM")
        (let ((input (string-trim (read-string (format "%s: " label) current))))
          (when (string-empty-p input) (user-error "A record needs a title"))
          (jetpacs-crud--record-mutate file pos
            (lambda ()
              ;; Replace only the title group of the headline, so the
              ;; TODO keyword, priority, and tags survive.
              (org-back-to-heading t)
              (when (looking-at org-complex-heading-regexp)
                (if (match-beginning 4)
                    (replace-match input t t nil 4)
                  (goto-char (line-end-position))
                  (insert " " input)))))))
       (t
        (let* ((dateish (or (member prop '("DEADLINE" "SCHEDULED"))
                            (eq (jetpacs-crud--field-type view prop) 'date)))
               (input
                (cond
                 (allowed
                  (completing-read (format "%s: " label) (car allowed)
                                   nil (cdr allowed) nil nil current))
                 (dateish (jetpacs-crud--prompt-value
                           label 'date (jetpacs-crud--current-date current)))
                 (t (jetpacs-crud--prompt-value
                     label (jetpacs-crud--field-type view prop) current)))))
          (jetpacs-crud--record-mutate file pos
            (lambda ()
              (if (string-empty-p input)
                  (jetpacs-crud--field-remove prop)
                (org-entry-put (point) prop input))))))))))

(defun jetpacs-crud-action-record-add (args _payload)
  "Append a new record: one typed prompt per schema field.
The heading lands at the end of the source subtree; fields are written
through `org-entry-put', so the drawer and planning lines are org's."
  (pcase-let* ((`(,spec ,view ,file ,_pos) (jetpacs-crud--resolve args)))
    (let* ((schema (jetpacs-crud--schema-props view))
           (heading (cdr (jetpacs-crud--view-source spec view)))
           ;; File-declared choices (keyword sequence, PROP_ALL) apply to
           ;; new records too; file-wide declarations resolve from any
           ;; position, so point-min serves before the record exists.
           (allowed-alist
            (when (file-readable-p file)
              (jetpacs-crud--with-source file
                (mapcar (lambda (f)
                          (cons (car f)
                                (jetpacs-crud--allowed-values (point-min)
                                                              (car f))))
                        schema))))
           (title (string-trim (read-string "Title: ")))
           (values
            (cl-loop for (prop . label) in schema
                     unless (member prop '("ITEM"))
                     collect
                     (cons prop
                           (let ((allowed (cdr (assoc prop allowed-alist))))
                             (cond
                              (allowed
                               (completing-read (format "%s: " (or label prop))
                                                (car allowed)
                                                nil (cdr allowed)))
                              ((or (member prop '("DEADLINE" "SCHEDULED"))
                                   (eq (jetpacs-crud--field-type view prop)
                                       'date))
                               (jetpacs-crud--prompt-value (or label prop)
                                                           'date))
                              (t (jetpacs-crud--prompt-value
                                  (or label prop)
                                  (jetpacs-crud--field-type view prop)))))))))
      (when (string-empty-p title) (user-error "A record needs a title"))
      (with-current-buffer (find-file-noselect file)
        (org-with-wide-buffer
         (let (level insert-at)
           (if heading
               (progn
                 (unless (jetpacs-crud--goto-heading heading)
                   (user-error "Heading %s not found in %s" heading file))
                 (setq level (1+ (org-outline-level)))
                 (setq insert-at (save-excursion (org-end-of-subtree t t)
                                                 (point))))
             (setq level 1 insert-at (point-max)))
           (goto-char insert-at)
           (unless (bolp) (insert "\n"))
           (insert (make-string level ?*) " " title "\n")
           (forward-line -1)
           (dolist (kv values)
             (unless (string-empty-p (cdr kv))
               (org-entry-put (point) (car kv) (cdr kv))))))
        (let ((save-silently t))
          (save-buffer))))
    (jetpacs-shell-push)))

(defun jetpacs-crud-action-record-menu (args _payload)
  "The record card's menu: delete, behind an explicit confirmation.
Deletion removes the record's whole subtree — the documented cost of
managing records (FORMAT.md)."
  (pcase-let* ((`(,_spec ,_view ,file ,pos) (jetpacs-crud--resolve args t)))
    (let (title)
      (jetpacs-crud--with-source file
        (goto-char pos)
        (unless (org-at-heading-p)
          (user-error "No record at position %s" pos))
        (setq title (or (org-entry-get (point) "ITEM") "this record")))
      (when (y-or-n-p (format "Delete \"%s\" and everything under it? " title))
        (jetpacs-crud--record-mutate file pos
          (lambda ()
            (org-back-to-heading t)
            (org-cut-subtree)))))))

(with-jetpacs-owner "jetpacs-crud"
  (jetpacs-defaction "crud.cell.edit"       #'jetpacs-crud-action-cell-edit)
  (jetpacs-defaction "crud.cell.toggle"     #'jetpacs-crud-action-cell-toggle)
  (jetpacs-defaction "crud.row.add"         #'jetpacs-crud-action-row-add)
  (jetpacs-defaction "crud.row.menu"        #'jetpacs-crud-action-row-menu)
  (jetpacs-defaction "crud.checkbox.toggle" #'jetpacs-crud-action-checkbox-toggle)
  (jetpacs-defaction "crud.item.add"        #'jetpacs-crud-action-item-add)
  (jetpacs-defaction "crud.field.edit"      #'jetpacs-crud-action-field-edit)
  (jetpacs-defaction "crud.record.add"      #'jetpacs-crud-action-record-add)
  (jetpacs-defaction "crud.record.menu"     #'jetpacs-crud-action-record-menu))

(provide 'jetpacs-crud)
;;; jetpacs-crud.el ends here
