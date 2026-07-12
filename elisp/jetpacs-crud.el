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

;; ─── The FILTER query engine ─────────────────────────────────────────────────
;;
;; A view's `:FILTER:' is a query over its records.  We reuse org-ql's sexp
;; language as the grammar — the same shape Glasspane's search speaks — and
;; carry a built-in interpreter (`jetpacs-crud--entry-matches-p') that covers
;; the common subset.  When the org-ql package is installed on the device it
;; extends the language for whole-file scopes (the notes datasource); for the
;; per-entry, subtree-scoped kinds here the interpreter is always the engine.
;;
;; Three input shapes, one grammar (`jetpacs-crud--parse-query'):
;;   - an org-ql sexp:        (and (todo "NEXT") (tags "work"))
;;   - filter tokens:         todo:NEXT tags:work,home priority:A
;;   - free text:             substring match on heading + body
;; Empty FILTER means "every record"; a malformed one signals, so an empty
;; result always means "nothing matched", never "the query didn't parse".

(defconst jetpacs-crud--ql-literals '(today nil t < <= > >= =)
  "Symbols that carry meaning to org-ql; normalization leaves them alone.")

(defun jetpacs-crud--normalize-ql-arg (arg)
  "Rewrite one query ARG into org-ql's string-argument shape."
  (cond
   ((and (consp arg) (eq (car arg) 'quote))
    (jetpacs-crud--normalize-ql-arg (cadr arg)))
   ((consp arg) (jetpacs-crud--normalize-ql arg))
   ((keywordp arg) arg)
   ((memq arg jetpacs-crud--ql-literals) arg)
   ((symbolp arg) (symbol-name arg))
   (t arg)))

(defun jetpacs-crud--normalize-ql (form)
  "Rewrite query FORM into org-ql's string-argument shape.
Users may type `(todo NEXT)' with a bare symbol; org-ql wants
`(todo \"NEXT\")'.  Non-literal symbols in argument position are
stringified; quotes are unwrapped."
  (cond
   ((not (consp form)) form)
   ((eq (car form) 'quote) (jetpacs-crud--normalize-ql (cadr form)))
   (t (cons (car form)
            (mapcar #'jetpacs-crud--normalize-ql-arg (cdr form))))))

(defun jetpacs-crud--query-tokens (query)
  "Split QUERY into whitespace tokens, keeping \"quoted phrases\" whole."
  (let ((tokens nil) (start 0))
    (while (string-match "\"\\([^\"]*\\)\"\\|\\S-+" query start)
      (push (or (match-string 1 query) (match-string 0 query)) tokens)
      (setq start (match-end 0)))
    (nreverse tokens)))

(defun jetpacs-crud--parse-query (query)
  "Parse a FILTER string QUERY into an org-ql sexp, or nil when empty.
See the section header for the three accepted shapes.  Signals
`user-error' on a malformed sexp."
  (let ((q (string-trim (or query ""))))
    (cond
     ((string-empty-p q) nil)
     ((string-match-p "\\`'?(" q)
      (condition-case _
          (jetpacs-crud--normalize-ql (car (read-from-string q)))
        (error (user-error "Malformed FILTER query: %s" q))))
     (t
      (let (clauses)
        (dolist (tok (jetpacs-crud--query-tokens q))
          (push
           (cond
            ((string-prefix-p "todo:" tok)
             (cons 'todo (split-string (substring tok 5) "," t)))
            ((string-prefix-p "tags:" tok)
             (cons 'tags (split-string (substring tok 5) "," t)))
            ((string-prefix-p "priority:" tok)
             (cons 'priority (split-string (substring tok 9) "," t)))
            (t (list 'regexp (regexp-quote tok))))
           clauses))
        (setq clauses (nreverse clauses))
        (if (cdr clauses) (cons 'and clauses) (car clauses)))))))

(defun jetpacs-crud--entry-priority ()
  "The priority character of the heading at point, or nil."
  (save-excursion (org-back-to-heading t) (nth 3 (org-heading-components))))

(defun jetpacs-crud--planning-day (spec)
  "Resolve a planning SPEC to an absolute day number.
SPEC is `today', an integer day offset, or a YYYY-MM-DD string."
  (pcase spec
    ('today (time-to-days (current-time)))
    ((pred integerp) (+ (time-to-days (current-time)) spec))
    ((pred stringp) (time-to-days (org-time-string-to-time spec)))
    (_ nil)))

(defun jetpacs-crud--planning-match-p (which args)
  "Match the entry's WHICH planning stamp against org-ql-style ARGS.
WHICH is `scheduled' or `deadline'.  ARGS is a plist of
:on / :from / :to day-specs; empty ARGS means mere presence."
  (let* ((prop (if (eq which 'deadline) "DEADLINE" "SCHEDULED"))
         (stamp (org-entry-get (point) prop)))
    (and stamp
         (let ((day (time-to-days (org-time-string-to-time stamp)))
               (on (plist-get args :on))
               (from (plist-get args :from))
               (to (plist-get args :to)))
           (and (or (not on) (equal day (jetpacs-crud--planning-day on)))
                (or (not from) (>= day (jetpacs-crud--planning-day from)))
                (or (not to) (<= day (jetpacs-crud--planning-day to))))))))

(defun jetpacs-crud--entry-matches-p (tree)
  "Non-nil when the org entry at point matches org-ql sexp TREE.
Point must be on a heading.  Implements the common org-ql subset;
an unsupported term signals `user-error' naming org-ql — that term
needs the org-ql package, which extends this interpreter when present."
  (pcase tree
    (`(and . ,cs) (cl-every #'jetpacs-crud--entry-matches-p cs))
    (`(or . ,cs) (cl-some #'jetpacs-crud--entry-matches-p cs))
    (`(not ,c) (not (jetpacs-crud--entry-matches-p c)))
    (`(todo . ,kws)
     (let ((state (org-get-todo-state)))
       (if kws (and state (member state kws) t)
         (and state (not (member state org-done-keywords)) t))))
    (`(done) (and (member (org-get-todo-state) org-done-keywords) t))
    (`(tags . ,tgs)
     (let ((have (org-get-tags nil t)))
       (if tgs (and (cl-some (lambda (tg) (member tg have)) tgs) t)
         (and have t))))
    (`(priority ,op ,val)
     (let ((p (jetpacs-crud--entry-priority))
           (v (if (stringp val) (aref val 0) val)))
       ;; org urgency runs A > B > C, i.e. the higher priority is the
       ;; smaller character, so the comparator flips against the chars.
       (and p (pcase op
                ('< (> p v)) ('<= (>= p v))
                ('> (< p v)) ('>= (<= p v)) ('= (= p v))
                (_ (user-error "Bad priority comparator %S" op))))))
    (`(priority . ,ps)
     (let ((p (jetpacs-crud--entry-priority)))
       (and p (member (char-to-string p) ps) t)))
    (`(heading . ,texts)
     (let ((h (or (org-get-heading t t t t) ""))
           (case-fold-search t))
       (cl-every (lambda (s) (string-match-p (regexp-quote s) h)) texts)))
    (`(regexp . ,res)
     (let ((end (save-excursion (outline-next-heading) (point)))
           (case-fold-search t))
       (cl-every (lambda (re)
                   (save-excursion
                     (org-back-to-heading t)
                     (re-search-forward re end t)))
                 res)))
    (`(property ,name . ,val)
     (let ((got (org-entry-get (point) name)))
       (if val (equal got (car val)) (and got t))))
    (`(level ,n) (= (org-current-level) n))
    (`(level ,lo ,hi) (<= lo (org-current-level) hi))
    (`(scheduled . ,args) (jetpacs-crud--planning-match-p 'scheduled args))
    (`(deadline . ,args) (jetpacs-crud--planning-match-p 'deadline args))
    (_ (user-error "Query term %S needs the org-ql package installed" tree))))

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
headings of the file when the source names none), kept when they
match the view's `:FILTER:' (see the FILTER query engine above).
Fields are read via `org-entry-get', so special properties come from
org itself."
  (let* ((source (jetpacs-crud--view-source spec view))
         (file (car source))
         (heading (cdr source))
         (props (mapcar #'car (jetpacs-crud--schema-props view)))
         (tree (jetpacs-crud--parse-query (plist-get view :filter))))
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
              ;; MATCH is nil — we walk every entry in scope and gate on the
              ;; parsed FILTER ourselves, so the query grammar is org-ql's,
              ;; not org's tags/property MATCH syntax.
              (org-map-entries
               (lambda ()
                 (when (and (= (org-outline-level) target)
                            (or (null tree)
                                (jetpacs-crud--entry-matches-p tree)))
                   (push (list :pos (point)
                               :fields (mapcar
                                        (lambda (p)
                                          (cons p (or (org-entry-get (point) p)
                                                      "")))
                                        props))
                         records)))
               nil)
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

;; ─── Notes: vulpea-backed records (optional datasource) ─────────────────────
;;
;; A `notes' view is a records view whose datasource is a vulpea vault.  The
;; SOURCE is either a directory (`contacts/' — one note file per record) or a
;; `file.org::*Heading' (id'd headings under that heading).  vulpea's SQLite
;; index supplies the scan and resolves a record by its stable note `:ID:';
;; fields are ordinary org PROPERTIES, so the whole records mutation machinery
;; applies and vulpea indexes them too.  vulpea is only ever asked to READ
;; (queries) and to re-index a file we just wrote — writes go through org and
;; `org-id', so we lean on no vulpea write/config surface.
;;
;; vulpea is OPTIONAL: absent, a notes view shows a placeholder and the runtime
;; still loads and runs on bare jetpacs-core (the bundle smoke test's world).

(declare-function vulpea-db-query "vulpea-db-query" (&optional predicate))
(declare-function vulpea-db-query-by-directory "vulpea-db-query"
                  (directory &optional level))
(declare-function vulpea-db-get-by-id "vulpea-db-query" (id))
(declare-function vulpea-db-update-file "vulpea-db" (path))
(declare-function vulpea-db--delete-file-notes "vulpea-db-extract" (path))
(declare-function vulpea-note-id "vulpea-note" (note))
(declare-function vulpea-note-path "vulpea-note" (note))
(declare-function vulpea-note-level "vulpea-note" (note))
(declare-function vulpea-note-title "vulpea-note" (note))
(declare-function vulpea-note-tags "vulpea-note" (note))
(declare-function vulpea-note-properties "vulpea-note" (note))
(declare-function vulpea-note-todo "vulpea-note" (note))
(declare-function vulpea-note-priority "vulpea-note" (note))
(declare-function vulpea-note-scheduled "vulpea-note" (note))
(declare-function vulpea-note-deadline "vulpea-note" (note))
(declare-function vulpea-note-outline-path "vulpea-note" (note))

(defvar jetpacs-crud--vulpea 'unknown
  "Cached vulpea availability: `unknown' re-probes, else t / nil.
Reset on `jetpacs-shell-refresh-hook' so installing vulpea mid-session
and pulling to refresh lights notes views up without a restart.")

(defun jetpacs-crud--vulpea-p ()
  "Non-nil when vulpea is installed and its note database is usable."
  (when (eq jetpacs-crud--vulpea 'unknown)
    (setq jetpacs-crud--vulpea (and (require 'vulpea nil t)
                                    (fboundp 'vulpea-db-query)
                                    t)))
  jetpacs-crud--vulpea)

(add-hook 'jetpacs-shell-refresh-hook
          (lambda () (setq jetpacs-crud--vulpea 'unknown)))

(defun jetpacs-crud--slug (text)
  "A filesystem-safe slug for TEXT (note file names)."
  (let ((s (replace-regexp-in-string
            "\\`-+\\|-+\\'" ""
            (replace-regexp-in-string "[^a-z0-9]+" "-" (downcase (string-trim text))))))
    (if (string-empty-p s) "note" s)))

(defun jetpacs-crud--note-field (note prop)
  "Read schema PROP from vulpea NOTE off the index (no file visit)."
  (pcase prop
    ("ITEM" (or (vulpea-note-title note) ""))
    ("TODO" (or (vulpea-note-todo note) ""))
    ("TAGS" (string-join (vulpea-note-tags note) " "))
    ("DEADLINE" (let ((d (vulpea-note-deadline note))) (if (stringp d) d "")))
    ("SCHEDULED" (let ((s (vulpea-note-scheduled note))) (if (stringp s) s "")))
    ("PRIORITY" (let ((p (vulpea-note-priority note)))
                  (cond ((null p) "")
                        ((characterp p) (char-to-string p))
                        (t (format "%s" p)))))
    ;; vulpea indexes drawer keys upper-cased (org's canonical form), so
    ;; match the schema prop case-insensitively.
    (_ (or (cdr (assoc-string prop (vulpea-note-properties note) t)) ""))))

(defun jetpacs-crud--notes-query (view)
  "The `vulpea-note' records for VIEW's SOURCE.
A directory SOURCE returns its file-level notes; a `file::*Heading'
SOURCE returns the id'd headings directly under that heading."
  (let* ((source (plist-get view :source))
         (dir (plist-get source :dir)))
    (if dir
        (vulpea-db-query-by-directory (directory-file-name dir) 0)
      (let ((file (plist-get source :file))
            (heading (plist-get source :heading)))
        (vulpea-db-query
         (lambda (n)
           (and (equal (expand-file-name (vulpea-note-path n))
                       (expand-file-name file))
                (> (vulpea-note-level n) 0)
                (if heading
                    (equal (vulpea-note-outline-path n) (list heading))
                  t))))))))

(defun jetpacs-crud--note-matches-p (tree note)
  "Non-nil when vulpea NOTE matches org-ql sexp TREE (index-only subset).
Terms outside the subset signal `user-error' — over the index we can
match `and'/`or'/`not', `todo', `tags', `property', `regexp', `level'."
  (pcase tree
    (`(and . ,cs) (cl-every (lambda (c) (jetpacs-crud--note-matches-p c note)) cs))
    (`(or . ,cs) (cl-some (lambda (c) (jetpacs-crud--note-matches-p c note)) cs))
    (`(not ,c) (not (jetpacs-crud--note-matches-p c note)))
    (`(todo . ,kws)
     (let ((s (vulpea-note-todo note)))
       (if kws (and s (member s kws) t) (and s t))))
    (`(tags . ,tgs)
     (let ((have (vulpea-note-tags note)))
       (if tgs (and (cl-some (lambda (tg) (member tg have)) tgs) t) (and have t))))
    (`(property ,name . ,val)
     (let ((got (cdr (assoc-string name (vulpea-note-properties note) t))))
       (if val (equal got (car val)) (and got t))))
    (`(regexp . ,res)
     (let ((hay (concat (or (vulpea-note-title note) "") " "
                        (mapconcat #'cdr (vulpea-note-properties note) " ")))
           (case-fold-search t))
       (cl-every (lambda (re) (string-match-p re hay)) res)))
    (`(level ,n) (= (vulpea-note-level note) n))
    (_ (user-error "FILTER term %S is not supported over notes; \
narrow the SOURCE or install org-ql" tree))))

(defun jetpacs-crud--scan-notes (spec view)
  "VIEW's note records: plists (:id ID :fields ALIST), FILTER-matched."
  (ignore spec)
  (let* ((props (mapcar #'car (jetpacs-crud--schema-props view)))
         (tree (jetpacs-crud--parse-query (plist-get view :filter))))
    (delq nil
          (mapcar
           (lambda (n)
             (when (or (null tree) (jetpacs-crud--note-matches-p tree n))
               (list :id (vulpea-note-id n)
                     :fields (mapcar (lambda (p)
                                       (cons p (jetpacs-crud--note-field n p)))
                                     props))))
           (jetpacs-crud--notes-query view)))))

(defun jetpacs-crud--note-card (spec view record)
  "The card node for note RECORD (:id :fields) of VIEW in SPEC.
Mirrors the records card, but taps carry the stable note `:ID:' and
fire the `crud.note.*' actions."
  (let* ((fields (plist-get record :fields))
         (id (plist-get record :id))
         (schema (jetpacs-crud--schema-props view))
         (title (let ((item (alist-get "ITEM" fields nil nil #'equal)))
                  (if (and item (not (string-empty-p item))) item "Untitled")))
         (todo (alist-get "TODO" fields nil nil #'equal))
         (args-for (lambda (prop)
                     `((app . ,(plist-get spec :id))
                       (view . ,(plist-get view :name))
                       (id . ,id)
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
                   (jetpacs-action "crud.note.field.edit"
                                :args (funcall args-for "ITEM"))))
        (jetpacs-menu
         (list (jetpacs-menu-item "Delete note"
                               (jetpacs-action "crud.note.menu"
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
                          :on-tap (jetpacs-action "crud.note.field.edit"
                                               :args (funcall args-for prop))
                          :padding 2)))))))))

(defun jetpacs-crud--notes-body (spec view)
  "The body node for a notes VIEW of SPEC (vulpea-backed, degrades)."
  (if (not (jetpacs-crud--vulpea-p))
      (jetpacs-lazy-column
       (jetpacs-empty-state
        :icon "extension_off"
        :title "Notes need vulpea"
        :caption "Install the vulpea package on this device to use this view."))
    (let ((records (jetpacs-crud--scan-notes spec view)))
      (apply #'jetpacs-lazy-column
             (or (mapcar (lambda (r) (jetpacs-crud--note-card spec view r)) records)
                 (list (jetpacs-empty-state
                        :icon "sticky_note_2"
                        :title "No notes"
                        :caption (if (plist-get view :filter)
                                     "Nothing matches the view's filter"
                                   "Tap + to add the first note"))))))))

;; ─── Datasource kinds (the dispatch registry) ────────────────────────────────
;;
;; Each `:KIND:' maps to a body builder and the crud.* action its add-FAB
;; fires.  A new datasource is one `jetpacs-crud--define-kind' call next to
;; its scan/body functions — the view builder and the FAB never grow another
;; branch.  `table' is the fallthrough for an unregistered kind (defensive;
;; the parser already rejects unknown kinds up front).

(defvar jetpacs-crud--kinds nil
  "Alist (KIND-SYMBOL . PLIST) of datasource behaviours.
PLIST keys: :body, a (SPEC VIEW) -> node builder; :fab, the crud.*
action string the add-FAB fires.")

(cl-defun jetpacs-crud--define-kind (kind &key body fab)
  "Register datasource KIND with its :body builder and :fab action string."
  (setf (alist-get kind jetpacs-crud--kinds) (list :body body :fab fab)))

(defun jetpacs-crud--kind-plist (view)
  "The behaviour plist for VIEW's kind, defaulting to `table'."
  (or (alist-get (plist-get view :kind) jetpacs-crud--kinds)
      (alist-get 'table jetpacs-crud--kinds)))

(jetpacs-crud--define-kind 'table
  :body #'jetpacs-crud--table-body :fab "crud.row.add")
(jetpacs-crud--define-kind 'checklist
  :body #'jetpacs-crud--checklist-body :fab "crud.item.add")
(jetpacs-crud--define-kind 'records
  :body #'jetpacs-crud--records-body :fab "crud.record.add")
(jetpacs-crud--define-kind 'notes
  :body #'jetpacs-crud--notes-body :fab "crud.note.add")

;; ─── The view builder ────────────────────────────────────────────────────────

(defun jetpacs-crud--fab (spec view)
  "The add FAB for VIEW of SPEC."
  (let ((args `((app . ,(plist-get spec :id))
                (view . ,(plist-get view :name)))))
    (jetpacs-fab "add"
              :on-tap (jetpacs-action
                       (or (plist-get (jetpacs-crud--kind-plist view) :fab)
                           "crud.row.add")
                       :args args))))

(defun jetpacs-crud--build-view (id name snackbar)
  "Build the full scaffold view for app ID's view NAME."
  (let* ((spec (or (jetpacs-crud--app id)
                   (error "CRUD app %s is not registered" id)))
         (view (or (jetpacs-crud--view spec name)
                   (error "CRUD app %s has no view %s" id name))))
    (jetpacs-shell-tab-view
     (format "%s.%s" id name)
     (funcall (plist-get (jetpacs-crud--kind-plist view) :body) spec view)
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

;; ─── Note actions (vulpea-backed records) ────────────────────────────────────
;;
;; A note record is addressed by its stable `:ID:', not a buffer position: the
;; id comes off the wire, but the resolved note's file must fall inside the
;; view's declared SOURCE — the same file-from-spec boundary the other handlers
;; keep.  Reads come from the vulpea index; writes go through org and end in a
;; `vulpea-db-update-file' re-index + a push.

(defun jetpacs-crud--note-in-source-p (view note)
  "Non-nil when NOTE's file is within VIEW's declared SOURCE."
  (let* ((source (plist-get view :source))
         (dir (plist-get source :dir))
         (file (plist-get source :file))
         (path (expand-file-name (vulpea-note-path note))))
    (cond
     (dir (string-prefix-p (expand-file-name (directory-file-name dir)) path))
     (file (equal (expand-file-name file) path))
     (t nil))))

(defun jetpacs-crud--note-resolve (args)
  "Resolve note wire ARGS to (SPEC VIEW NOTE); validate the source scope."
  (let* ((appid (alist-get 'app args))
         (name (alist-get 'view args))
         (nid (alist-get 'id args))
         (spec (or (jetpacs-crud--app appid)
                   (user-error "Unknown CRUD app: %S" appid)))
         (view (or (jetpacs-crud--view spec name)
                   (user-error "Unknown view %S in app %s" name appid))))
    (unless (jetpacs-crud--vulpea-p) (user-error "vulpea is not installed"))
    (unless (stringp nid) (user-error "Missing note id in %S" args))
    (let ((note (or (vulpea-db-get-by-id nid)
                    (user-error "No note with id %s" nid))))
      (unless (jetpacs-crud--note-in-source-p view note)
        (user-error "Note %s is outside this view's source" nid))
      (list spec view note))))

(defun jetpacs-crud--note-goto (id)
  "Move point to the entry with :ID: ID in the current buffer; return point.
A file-level note (id in the top drawer, not a heading) leaves point at
`point-min'."
  (let ((pos (org-find-entry-with-id id)))
    (goto-char (or pos (point-min)))
    (point)))

(defun jetpacs-crud--note-mutate (file id fn)
  "Run FN at note ID in FILE; save, re-index in vulpea, push."
  (with-current-buffer (find-file-noselect file)
    (org-with-wide-buffer
     (jetpacs-crud--note-goto id)
     (funcall fn))
    (let ((save-silently t)) (save-buffer)))
  (when (fboundp 'vulpea-db-update-file) (vulpea-db-update-file file))
  (jetpacs-shell-push))

(defun jetpacs-crud--note-set-title (note input)
  "Set NOTE's title to INPUT: the headline for a heading note, else `#+title:'."
  (if (> (vulpea-note-level note) 0)
      (progn
        (org-back-to-heading t)
        (when (looking-at org-complex-heading-regexp)
          (if (match-beginning 4)
              (replace-match input t t nil 4)
            (goto-char (line-end-position))
            (insert " " input))))
    (goto-char (point-min))
    (if (re-search-forward "^#\\+title:.*$" nil t)
        (replace-match (concat "#+title: " input) t t)
      (insert "#+title: " input "\n"))))

(defun jetpacs-crud--note-prompt-values (view schema allowed-alist)
  "Prompt for each non-ITEM SCHEMA field of VIEW; return a (PROP . VALUE) alist."
  (cl-loop for (prop . label) in schema
           unless (member prop '("ITEM"))
           collect
           (cons prop
                 (let ((allowed (cdr (assoc prop allowed-alist))))
                   (cond
                    (allowed
                     (completing-read (format "%s: " (or label prop))
                                      (car allowed) nil (cdr allowed)))
                    ((or (member prop '("DEADLINE" "SCHEDULED"))
                         (eq (jetpacs-crud--field-type view prop) 'date))
                     (jetpacs-crud--prompt-value (or label prop) 'date))
                    (t (jetpacs-crud--prompt-value
                        (or label prop)
                        (jetpacs-crud--field-type view prop))))))))

(defun jetpacs-crud--note-write-heading-note (heading title values)
  "Insert an id'd heading TITLE with VALUES under HEADING (or at level 1)."
  (let (level insert-at)
    (if heading
        (progn
          (unless (jetpacs-crud--goto-heading heading)
            (user-error "Heading %s not found" heading))
          (setq level (1+ (org-outline-level)))
          (setq insert-at (save-excursion (org-end-of-subtree t t) (point))))
      (setq level 1 insert-at (point-max)))
    (goto-char insert-at)
    (unless (bolp) (insert "\n"))
    (insert (make-string level ?*) " " title "\n")
    (forward-line -1)
    (org-id-get-create)
    (dolist (kv values)
      (unless (string-empty-p (cdr kv))
        (org-entry-put (point) (car kv) (cdr kv))))))

(defun jetpacs-crud--note-write-file-note (title values)
  "Write a fresh file-level note into the current (empty) buffer."
  (goto-char (point-min))
  (insert "#+title: " title "\n")
  (goto-char (point-min))
  (org-id-get-create)                   ; the top-of-file :ID: drawer
  (dolist (kv values)
    (unless (string-empty-p (cdr kv))
      (org-entry-put (point-min) (car kv) (cdr kv)))))

(defun jetpacs-crud-action-note-add (args _payload)
  "Create a note in the view's vault (dir) or under its source heading."
  (let* ((appid (alist-get 'app args))
         (name (alist-get 'view args))
         (spec (or (jetpacs-crud--app appid)
                   (user-error "Unknown CRUD app: %S" appid)))
         (view (or (jetpacs-crud--view spec name)
                   (user-error "Unknown view %S in app %s" name appid)))
         (source (plist-get view :source))
         (dir (plist-get source :dir))
         (file (plist-get source :file))
         (heading (plist-get source :heading))
         (schema (jetpacs-crud--schema-props view)))
    (unless (jetpacs-crud--vulpea-p) (user-error "vulpea is not installed"))
    (let* ((probe (or file (and dir (car (ignore-errors
                                           (directory-files dir t "\\.org\\'"))))))
           (allowed-alist
            (when (and probe (file-readable-p probe))
              (jetpacs-crud--with-source probe
                (mapcar (lambda (f)
                          (cons (car f)
                                (jetpacs-crud--allowed-values (point-min) (car f))))
                        schema))))
           (title (string-trim (read-string "Title: ")))
           (values (jetpacs-crud--note-prompt-values view schema allowed-alist)))
      (when (string-empty-p title) (user-error "A note needs a title"))
      (let ((target (if dir
                        (expand-file-name (concat (jetpacs-crud--slug title) ".org")
                                          dir)
                      file)))
        (when (and dir (file-exists-p target))
          (user-error "A note file already exists: %s" target))
        (when dir (make-directory dir t))
        (with-current-buffer (find-file-noselect target)
          (org-with-wide-buffer
           (if dir
               (jetpacs-crud--note-write-file-note title values)
             (jetpacs-crud--note-write-heading-note heading title values)))
          (let ((save-silently t)) (save-buffer)))
        (when (fboundp 'vulpea-db-update-file) (vulpea-db-update-file target))
        (jetpacs-shell-push)))))

(defun jetpacs-crud-action-note-field-edit (args _payload)
  "Edit one field of the note at the tapped card (org properties + specials)."
  (pcase-let* ((`(,_spec ,view ,note) (jetpacs-crud--note-resolve args))
               (prop (alist-get 'prop args))
               (file (vulpea-note-path note))
               (nid (vulpea-note-id note)))
    (unless (cl-find prop (jetpacs-crud--schema-props view)
                     :key #'car :test #'equal)
      (user-error "Field %S is not in the view's schema" prop))
    (let* ((label (or (cdr (cl-find prop (jetpacs-crud--schema-props view)
                                    :key #'car :test #'equal))
                      prop))
           current allowed)
      (jetpacs-crud--with-source file
        (jetpacs-crud--note-goto nid)
        (setq current (if (equal prop "ITEM")
                          (or (vulpea-note-title note) "")
                        (or (org-entry-get (point) prop) "")))
        (setq allowed (jetpacs-crud--allowed-values (point) prop)))
      (cond
       ((equal prop "ITEM")
        (let ((input (string-trim (read-string (format "%s: " label) current))))
          (when (string-empty-p input) (user-error "A note needs a title"))
          (jetpacs-crud--note-mutate file nid
            (lambda () (jetpacs-crud--note-set-title note input)))))
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
          (jetpacs-crud--note-mutate file nid
            (lambda ()
              (if (string-empty-p input)
                  (jetpacs-crud--field-remove prop)
                (org-entry-put (point) prop input))))))))))

(defun jetpacs-crud-action-note-menu (args _payload)
  "Delete the note behind an explicit confirmation.
File-per-record notes delete the file; heading notes cut the subtree."
  (pcase-let* ((`(,_spec ,_view ,note) (jetpacs-crud--note-resolve args))
               (file (vulpea-note-path note))
               (level (vulpea-note-level note))
               (nid (vulpea-note-id note))
               (title (or (vulpea-note-title note) "this note")))
    (when (y-or-n-p (format "Delete \"%s\"? " title))
      (if (= level 0)
          (progn
            (when (get-file-buffer file)
              (with-current-buffer (get-file-buffer file)
                (set-buffer-modified-p nil)
                (kill-buffer)))
            (delete-file file)
            ;; Drop the note from the index.  On the device
            ;; `vulpea-db-autosync-mode' would also catch the deletion, but
            ;; do it now so the view refreshes without waiting on the watcher.
            ;; (Re-indexing a *deleted* file would fail — the file is gone.)
            (when (fboundp 'vulpea-db--delete-file-notes)
              (ignore-errors (vulpea-db--delete-file-notes file)))
            (jetpacs-shell-push))
        (jetpacs-crud--note-mutate file nid
          (lambda () (org-back-to-heading t) (org-cut-subtree)))))))

(with-jetpacs-owner "jetpacs-crud"
  (jetpacs-defaction "crud.cell.edit"       #'jetpacs-crud-action-cell-edit)
  (jetpacs-defaction "crud.cell.toggle"     #'jetpacs-crud-action-cell-toggle)
  (jetpacs-defaction "crud.row.add"         #'jetpacs-crud-action-row-add)
  (jetpacs-defaction "crud.row.menu"        #'jetpacs-crud-action-row-menu)
  (jetpacs-defaction "crud.checkbox.toggle" #'jetpacs-crud-action-checkbox-toggle)
  (jetpacs-defaction "crud.item.add"        #'jetpacs-crud-action-item-add)
  (jetpacs-defaction "crud.field.edit"      #'jetpacs-crud-action-field-edit)
  (jetpacs-defaction "crud.record.add"      #'jetpacs-crud-action-record-add)
  (jetpacs-defaction "crud.record.menu"     #'jetpacs-crud-action-record-menu)
  (jetpacs-defaction "crud.note.add"        #'jetpacs-crud-action-note-add)
  (jetpacs-defaction "crud.note.field.edit" #'jetpacs-crud-action-note-field-edit)
  (jetpacs-defaction "crud.note.menu"       #'jetpacs-crud-action-note-menu))

(provide 'jetpacs-crud)
;;; jetpacs-crud.el ends here
