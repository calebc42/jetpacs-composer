;;; jetpacs-crud-vulpea.el --- The engine layer: vulpea + org-ql for jetpacs-crud -*- lexical-binding: t; -*-

;; Copyright (C) 2026 calebc42 and contributors
;; SPDX-License-Identifier: GPL-3.0-or-later

;; The optional-engine layer of the jetpacs-crud runtime: the vulpea and
;; org-ql availability probes, `:ID:' adoption + re-indexing of source
;; files, the index-backed reads every heading-family kind shares, the
;; FILTER router (index predicate vs org-ql handoff), and the plugin
;; extractor that indexes org tables + checkbox items for the `table' /
;; `checklist' kinds.
;;
;; Both engines are OPTIONAL: absent, the probes answer nil, engine-backed
;; views render "needs vulpea" placeholders, and the runtime still loads
;; and runs on bare jetpacs-core (the bundle smoke test's world).  vulpea
;; is only ever asked to READ (queries) and to re-index a file we just
;; wrote — writes go through org and `org-id', so we lean on no vulpea
;; write/config surface.
;;
;; This file and `jetpacs-crud.el' are one runtime split in two: the
;; rendering/action side calls into this engine side (probe, reads,
;; reindex) and this side calls a handful of its helpers back.  Loading
;; order between them is free — the bundle inlines both, and this file
;; requires `jetpacs-crud' (safe: jetpacs-crud never requires this file).

;;; Code:

(require 'cl-lib)
(require 'org)
(require 'org-id)
(require 'org-element)
(require 'jetpacs-org)
;; The rendering/action half of the runtime (source scopes, the
;; with-source macro, schema props).  Not circular: it never requires us.
(require 'jetpacs-crud)

;; ─── The org-ql probe (the FILTER wide-terms engine) ─────────────────────────

(declare-function org-ql-select "org-ql" (buffers-files query &rest rest))

(defvar jetpacs-crud--org-ql 'unknown
  "Cached org-ql availability: `unknown' re-probes, else t / nil.
Reset on `jetpacs-shell-refresh-hook' so installing org-ql mid-session
widens FILTER without a restart.")

(defun jetpacs-crud--org-ql-p ()
  "Non-nil when the org-ql package is installed."
  (when (eq jetpacs-crud--org-ql 'unknown)
    (setq jetpacs-crud--org-ql (and (require 'org-ql nil t)
                                    (fboundp 'org-ql-select)
                                    t)))
  jetpacs-crud--org-ql)

(add-hook 'jetpacs-shell-refresh-hook
          (lambda () (setq jetpacs-crud--org-ql 'unknown)))

;; ─── The vulpea probe + id adoption ──────────────────────────────────────────

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
(declare-function vulpea-note-closed "vulpea-note" (note))
(declare-function vulpea-note-pos "vulpea-note" (note))

(defvar jetpacs-crud--vulpea 'unknown
  "Cached vulpea availability: `unknown' re-probes, else t / nil.
Reset on `jetpacs-shell-refresh-hook' so installing vulpea mid-session
and pulling to refresh lights notes views up without a restart.")

(defun jetpacs-crud--vulpea-p ()
  "Non-nil when vulpea is installed and its note database is usable.
The first time vulpea is detected, the tables+checklist extractor is
registered (`jetpacs-crud-vulpea--register-extractor')."
  (when (eq jetpacs-crud--vulpea 'unknown)
    (setq jetpacs-crud--vulpea (and (require 'vulpea nil t)
                                    (fboundp 'vulpea-db-query)
                                    t))
    (when jetpacs-crud--vulpea
      (jetpacs-crud-vulpea--register-extractor)))
  jetpacs-crud--vulpea)

(add-hook 'jetpacs-shell-refresh-hook
          (lambda () (setq jetpacs-crud--vulpea 'unknown)))

;; ─── Engine self-provisioning ────────────────────────────────────────────────
;;
;; A freshly deployed app on a fresh device used to dead-end on the
;; "needs vulpea" placeholder until the user hand-installed packages: the
;; engine bootstrap lived only in a deploy snippet that was easy to skip.
;; The runtime now provisions its OWN engines: one automatic attempt per
;; session when a registered app needs them (idle, never blocking boot),
;; plus the placeholder's Install button (`crud.engines.install') as the
;; on-demand/retry path.  Success lights views up live — probes reset,
;; sources id-adopted, autosync wired, shell refreshed — no restart.
;;
;; Trust boundary (locked in Stage 4): app data can never trigger an
;; install.  Only this closed, runtime-owned engine pair is ever
;; installed here — a document's `#+JETPACS_DEPENDS:'/pack extras stay on
;; the explicit deploy paths (ssh bootstrap, dialog warnings).

(declare-function vulpea-db-autosync-mode "vulpea-db" (&optional arg))
(declare-function vulpea-db-sync-full-scan "vulpea-db" ())

(defvar jetpacs-crud-engines-auto-install t
  "When non-nil, registering an app that needs missing engines schedules
one automatic install attempt for this session (on an idle timer, so
boot is never blocked).  The placeholder's Install button works
regardless.  Set to nil in user.el to manage packages yourself.")

(defconst jetpacs-crud--engine-packages '(org-ql vulpea)
  "The closed MELPA engine pair the runtime's views read through.
Mirrors the Deployer's DEFAULT_ENGINES.  Deliberately not extensible
from app data — see the trust note above.")

(defvar jetpacs-crud--engines-attempted nil
  "Non-nil once this session has scheduled its automatic install attempt.")

(defvar jetpacs-crud--engines-installing nil
  "Non-nil while an engine install is in flight.
Load-bearing re-entrancy guard: `package-refresh-contents' pumps the
event loop (`accept-process-output'), so a second button tap could
otherwise re-enter mid-install.")

(defun jetpacs-crud-engines-blocked-reason ()
  "A human-readable reason installing engines cannot help, or nil.
vulpea's index rides `emacsql-sqlite-builtin', which needs an Emacs
built with SQLite — no package install can supply a missing build
feature, so the placeholder says this instead of offering a dead button."
  (unless (and (fboundp 'sqlite-available-p) (sqlite-available-p))
    "this Emacs build lacks SQLite, which vulpea's index needs — use an Emacs built --with-sqlite3"))

(defun jetpacs-crud--engines-missing ()
  "The engine packages not currently loadable, freshly probed."
  (setq jetpacs-crud--org-ql 'unknown
        jetpacs-crud--vulpea 'unknown)
  (let (missing)
    (unless (jetpacs-crud--org-ql-p) (push 'org-ql missing))
    (unless (jetpacs-crud--vulpea-p) (push 'vulpea missing))
    (nreverse missing)))

(defun jetpacs-crud--engines-wire-vulpea ()
  "Wire vulpea's autosync over the vault and the app-source dir, additively.
The bootstrap the deploy snippet used to carry, now runtime-owned so a
device provisions itself.  Additive on purpose: the user's own
`vulpea-db-sync-directories' entries are kept, autosync stays the single
index (never a second one — battery rule), and the initial full scan
runs once per device (marker file)."
  (when (require 'vulpea nil t)
    (defvar vulpea-db-sync-directories)
    (dolist (dir (list org-directory
                       (expand-file-name "jetpacs-crud/" user-emacs-directory)))
      (when (and (stringp dir) (file-directory-p dir))
        (add-to-list 'vulpea-db-sync-directories dir)))
    (when (fboundp 'vulpea-db-autosync-mode)
      (vulpea-db-autosync-mode 1))
    (let ((marker (expand-file-name "jetpacs-crud/.vulpea-scanned"
                                    user-emacs-directory)))
      (unless (file-exists-p marker)
        (ignore-errors (vulpea-db-sync-full-scan))
        (make-directory (file-name-directory marker) t)
        (write-region "" nil marker nil 'silent)))))

(defun jetpacs-crud--engines-light-up ()
  "The moment the engines are loadable: make the views real, live.
Wires autosync, id-adopts every registered vulpea view's source (the
step that ran as a no-op when the app registered engine-less), and
refreshes the shell — which re-probes and rebuilds every pushed view."
  (jetpacs-crud--engines-wire-vulpea)
  (dolist (cell jetpacs-crud--apps)
    (let ((spec (cdr cell)))
      (dolist (view (plist-get spec :views))
        (when (memq (plist-get view :kind) jetpacs-crud--vulpea-kinds)
          (ignore-errors (jetpacs-crud-vulpea-ensure-source spec view))))))
  (when (fboundp 'jetpacs-shell-refresh)
    (jetpacs-shell-refresh)))

(defun jetpacs-crud-engines-ensure ()
  "Install any missing engine package from MELPA, then light views up.
Synchronous (package.el is), idempotent, and never signals: returns
non-nil when every engine is loadable afterwards, else nil with the
reason in *Messages*.  The retry story is simply calling this again —
each restart's automatic attempt and every Install-button tap do."
  (cond
   (jetpacs-crud--engines-installing
    (message "jetpacs-crud: engine install already in progress")
    nil)
   ((null (jetpacs-crud--engines-missing))
    (jetpacs-crud--engines-light-up)
    t)
   ((jetpacs-crud-engines-blocked-reason)
    (message "jetpacs-crud: %s" (jetpacs-crud-engines-blocked-reason))
    nil)
   (t
    (setq jetpacs-crud--engines-installing t)
    (unwind-protect
        (condition-case err
            (progn
              (require 'package)
              (defvar package-archives)
              (add-to-list 'package-archives
                           '("melpa" . "https://melpa.org/packages/") t)
              (unless (bound-and-true-p package--initialized)
                (package-initialize))
              (package-refresh-contents)
              (dolist (pkg (jetpacs-crud--engines-missing))
                (unless (package-installed-p pkg)
                  (message "jetpacs-crud: installing %s…" pkg)
                  (package-install pkg)))
              (let ((still (jetpacs-crud--engines-missing)))
                (if still
                    (progn
                      (message "jetpacs-crud: %s installed but not loadable — see *Messages*"
                               (mapconcat #'symbol-name still ", "))
                      nil)
                  (jetpacs-crud--engines-light-up)
                  (message "jetpacs-crud: engines ready — views refreshed")
                  t)))
          (error
           (message "jetpacs-crud: engine install failed: %s"
                    (error-message-string err))
           nil))
      (setq jetpacs-crud--engines-installing nil)))))

(defun jetpacs-crud--engines-maybe-auto-install (spec)
  "Schedule this session's one automatic engine install when SPEC needs it.
Called at app registration; fires on an idle timer so boot cost is zero.
Skipped when disabled, already attempted, nothing is missing, no view of
SPEC is engine-backed, the build can never run vulpea anyway — or Emacs
is batch (`noninteractive'): a CI/test run must never reach for MELPA."
  (when (and jetpacs-crud-engines-auto-install
             (not noninteractive)
             (not jetpacs-crud--engines-attempted)
             (cl-some (lambda (v) (memq (plist-get v :kind)
                                        jetpacs-crud--vulpea-kinds))
                      (plist-get spec :views))
             (not (jetpacs-crud-engines-blocked-reason))
             (jetpacs-crud--engines-missing))
    (setq jetpacs-crud--engines-attempted t)
    (run-with-idle-timer
     3 nil
     (lambda ()
       (message "jetpacs-crud: engines missing — attempting install (%s)…"
                (mapconcat #'symbol-name jetpacs-crud--engine-packages ", "))
       (jetpacs-crud-engines-ensure)))))

(defun jetpacs-crud-vulpea-ensure-source (spec view)
  "Give VIEW's source file and record headings stable `:ID:'s, then reindex.
vulpea only indexes a heading that carries an `:ID:', so a heading-family
kind must adopt ids before it can be read from the index.  This adds a
file-level `:ID:', an `:ID:' on the source heading (when the SOURCE names
one), and one on each direct-child record heading; it saves only when a
buffer actually changed and then asks vulpea to re-index the file.

It is a no-op when vulpea is absent (the org-buffer scan still serves the
view) and idempotent when the ids already exist — an already-normalized
file is neither modified nor re-saved.  Only VIEW's own declared source
file is touched (an inline source is the app document itself); nothing
outside it is read or written.  Returns non-nil when the file changed.

See FORMAT.md \"What the runtime does to your files\"."
  (when (jetpacs-crud--vulpea-p)
    (let* ((source (jetpacs-crud--view-source spec view))
           (file (car source))
           (heading (cdr source))
           (changed nil))
      (when (and file (file-readable-p file))
        (jetpacs-crud--with-source file
          (cl-flet ((adopt ()
                      (let ((before (org-id-get)))
                        (org-id-get-create)
                        (unless (equal before (org-id-get)) (setq changed t)))))
            (save-restriction
              (widen)
              (goto-char (point-min))
              (adopt)                     ; file-level id (the extractor's anchor)
              (let ((base 0))
                (when heading
                  (unless (jetpacs-crud--goto-heading heading)
                    (user-error "Heading %s not found in %s" heading file))
                  (setq base (org-outline-level))
                  (adopt)                 ; the source heading itself
                  (org-narrow-to-subtree))
                (let ((target (1+ base)))
                  (org-map-entries
                   (lambda () (when (= (org-outline-level) target) (adopt)))
                   nil)))))
          (when (and changed (buffer-modified-p))
            (save-buffer)))
        (when changed
          (vulpea-db-update-file (expand-file-name file))))
      changed)))

(defun jetpacs-crud--reindex (file)
  "Re-index FILE in vulpea after a write so index-backed reads stay fresh.
A no-op without vulpea (table/checklist reads then come from nowhere, but
those kinds already require vulpea to render)."
  (when (jetpacs-crud--vulpea-p)
    (vulpea-db-update-file (expand-file-name file))))

;; ─── Index-backed reads (the one scan every heading kind shares) ─────────────

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

(defun jetpacs-crud--query-view-notes (spec view)
  "The `vulpea-note' records backing heading-family VIEW of SPEC.
The one index scan every heading kind shares.  SOURCE dispatch:
 - a `dir/' vault  -> its file-level notes (one note file per record);
 - `file::*Heading' -> the id'd headings directly under that heading;
 - a bare file / an inline source (the app document, scoped to the
   view's own heading) -> the id'd level-1 headings of that scope.
Records must be id-adopted first (`jetpacs-crud-vulpea-ensure-source')
for the index to see them.  The scope query itself is canonical
\(`jetpacs-org-vulpea-source-notes'); this only maps the view's
plumbing onto the core's source shape."
  (let ((dir (plist-get (plist-get view :source) :dir)))
    (if dir
        (jetpacs-org-vulpea-source-notes (list :dir dir))
      (let ((fh (jetpacs-crud--view-source spec view)))
        (jetpacs-org-vulpea-source-notes
         (list :file (car fh) :heading (cdr fh)))))))

(defun jetpacs-crud--view-notes-records (spec view)
  "Unified records for heading-family VIEW: plists (:id :pos :fields :done).
Reads VIEW's notes from the vulpea index (`jetpacs-crud--query-view-notes'),
applies the compiled `:FILTER:' plus any live search overlay, and reads
each field off the `vulpea-note' struct — no file visit.  `:pos' is the
note's indexed heading position (a mutation hint; the id is authoritative).

Self-guards on `jetpacs-crud--vulpea-p': without vulpea it returns nil, so
every caller (bodies, export, reminders, ref resolution) degrades to \"no
records\" rather than calling into an absent index."
  (when (jetpacs-crud--vulpea-p)
   (let* ((props (mapcar #'car (jetpacs-crud--schema-props view)))
         (search-id (format "search_%s" (plist-get view :name)))
         (search (jetpacs-ui-state search-id))
         (static-tree (jetpacs-org-parse-query (plist-get view :filter)))
         (tree (if (and search (not (string-empty-p search)))
                   (let ((q (list 'or `(regexp ,search) `(tags ,search) `(todo ,search))))
                     (if static-tree (list 'and static-tree q) q))
                 static-tree))
         (notes (jetpacs-crud--query-view-notes spec view))
         (compiled (jetpacs-crud--compile-filter tree))
         (keep (pcase compiled
                 (`(:pred ,pred) pred)
                 (`(:org-ql ,ql)
                  (let ((set (jetpacs-crud--notes-org-ql-ids notes ql)))
                    (lambda (n) (gethash (vulpea-note-id n) set)))))))
    (delq nil
          (mapcar
           (lambda (n)
             (when (funcall keep n)
               (list :id (vulpea-note-id n)
                     :pos (vulpea-note-pos n)
                     :done (jetpacs-org-note-matches-p '(done) n)
                     :fields (mapcar (lambda (p)
                                       (cons p (jetpacs-crud--note-field n p)))
                                     props))))
           notes)))))

;; The note-index matcher is CANONICAL (jetpacs-org.el, api 1.6.0): one
;; query grammar, two accessors — `jetpacs-org-entry-matches-p' at point,
;; `jetpacs-org-note-matches-p' off the `vulpea-note' struct.  This file
;; used to carry its own copy; never re-grow one.

(defun jetpacs-crud--compile-filter (tree)
  "Compile org-ql sexp TREE for a vulpea-backed view.
Returns (:pred CLOSURE) — a `vulpea-note' -> bool via the canonical
`jetpacs-org-note-matches-p' — or (:org-ql TREE) when a term needs
org-ql over the source file (`jetpacs-org-note-query-supported-p' is
the router).  An empty TREE compiles to a predicate that admits every
note."
  (cond
   ((null tree) (list :pred (lambda (_note) t)))
   ((jetpacs-org-note-query-supported-p tree)
    (list :pred (lambda (note) (jetpacs-org-note-matches-p tree note))))
   (t (list :org-ql tree))))

(defun jetpacs-crud--notes-org-ql-ids (notes tree)
  "IDs among NOTES whose headings match org-ql sexp TREE via org-ql.
Runs org-ql once per distinct source file, collecting each matched
heading's `:ID:'.  Requires org-ql; signals `user-error' naming it
otherwise (the honest \"install org-ql\" instruction).  Heading-per-record
notes match; file-level notes (level 0) have no heading for org-ql to
visit, so an org-ql-only FILTER does not select them — narrow the SOURCE."
  (unless (jetpacs-crud--org-ql-p)
    (user-error "FILTER term needs the org-ql package installed on this device"))
  (let ((ids (make-hash-table :test 'equal))
        (files (delete-dups
                (mapcar (lambda (n) (expand-file-name (vulpea-note-path n)))
                        notes))))
    (dolist (file files)
      (when (file-readable-p file)
        (dolist (id (org-ql-select file tree
                                   :action (lambda () (org-entry-get nil "ID"))))
          (when (and (stringp id) (not (string-empty-p id)))
            (puthash id t ids)))))
    ids))

(defun jetpacs-crud--scan-notes-impl (spec view)
  "VIEW's note records: plists (:id ID :fields ALIST), FILTER-matched.
A thin projection of the shared `jetpacs-crud--view-notes-records' — notes
address by their stable `:ID:', so `:pos'/`:done' are dropped here."
  (mapcar (lambda (r) (list :id (plist-get r :id) :fields (plist-get r :fields)))
          (jetpacs-crud--view-notes-records spec view)))

;; ─── Tables & checklists: the vulpea extractor ──────────────────────────────
;;
;; `table' and `checklist' kinds have no org `:ID:' to hang on, so they ride
;; a vulpea PLUGIN EXTRACTOR instead: when vulpea indexes a source file, the
;; extractor walks its AST once (on the file-level note pass) and records
;; every org table and checkbox item into two plugin tables keyed to the
;; file-level note id.  The `:on-delete :cascade' foreign key means a
;; re-index (which deletes and re-inserts the file's notes) drops the old
;; rows for free.  The readers below return the exact shapes the table and
;; checklist renderers already consume, so the bodies just swap their file
;; scan for a DB read.  Requires a file-level `:ID:' on the source (vulpea
;; only indexes files that have one) — `jetpacs-crud-vulpea-ensure-source'
;; adopts it.

(declare-function vulpea-db "vulpea-db" ())
(declare-function vulpea-db-register-extractor "vulpea-db-extract"
                  (extractor-or-name &optional fn))
(declare-function make-vulpea-extractor "vulpea-db-extract" (&rest slots))
(declare-function vulpea-parse-ctx-ast "vulpea-db-extract" (ctx))
(declare-function vulpea-parse-ctx-file-node "vulpea-db-extract" (ctx))
(declare-function emacsql "emacsql" (db sql &rest args))

(defconst jetpacs-crud-vulpea--extractor-version 1
  "Schema/behaviour version of the jetpacs tables+checklist extractor.
Bump to force a rebuild of the plugin tables on the next sync.")

(defvar jetpacs-crud-vulpea--extractor-registered nil
  "Non-nil once the jetpacs extractor is registered in this session.")

(defun jetpacs-crud-vulpea--ancestor-heading (element)
  "The nearest ancestor headline's raw title for org-element ELEMENT, or nil."
  (let ((hl (org-element-lineage element '(headline))))
    (and hl (org-element-property :raw-value hl))))

(defun jetpacs-crud-vulpea--plain (s)
  "S trimmed and stripped of text properties.
`org-element-interpret-data' returns propertized strings whose properties
reference AST nodes — unreadable `#<…>' objects that break emacsql's
prin1/read storage.  Store plain text only."
  (substring-no-properties (string-trim (or s ""))))

(defun jetpacs-crud-vulpea--cell-text (cell)
  "The trimmed, property-free text of org-element table-CELL, from the AST."
  (jetpacs-crud-vulpea--plain
   (org-element-interpret-data (org-element-contents cell))))

(defun jetpacs-crud-vulpea--table-rows (table)
  "Rows of org-element TABLE: `hline', or a list of (TEXT . POS) cells.
POS is the cell's content start (a hint; mutation re-locates by row/col)."
  (org-element-map table 'table-row
    (lambda (row)
      (if (eq (org-element-property :type row) 'rule)
          'hline
        (org-element-map row 'table-cell
          (lambda (cell)
            (cons (jetpacs-crud-vulpea--cell-text cell)
                  (org-element-property :contents-begin cell))))))))

(defun jetpacs-crud-vulpea--item-text (item)
  "The trimmed, property-free text of a checkbox org-element ITEM."
  (let ((para (org-element-map item 'paragraph #'identity nil t)))
    (if para
        (jetpacs-crud-vulpea--plain
         (org-element-interpret-data (org-element-contents para)))
      "")))

(defun jetpacs-crud-vulpea--encode-rows (rows)
  "Encode ROWS (`hline' / (TEXT . POS) lists) to JSON via the native encoder.
Each row is the string \"hline\" or an array of [text, pos] pairs.  Uses
`json-serialize' (a subr — no `json' library, and consistent with the
`json-parse-string' decoder)."
  (json-serialize
   (vconcat
    (mapcar (lambda (r)
              (if (eq r 'hline) "hline"
                (vconcat (mapcar (lambda (c) (vector (car c) (cdr c))) r))))
            rows))))

(defun jetpacs-crud-vulpea--decode-rows (json)
  "Decode JSON (from `jetpacs-crud-vulpea--encode-rows') back to :rows shape."
  (mapcar (lambda (r)
            (if (stringp r) 'hline
              (mapcar (lambda (c) (cons (nth 0 c) (nth 1 c))) r)))
          (json-parse-string json :array-type 'list)))

(defun jetpacs-crud-vulpea--extract (ctx data)
  "vulpea extractor: index this file's org tables and checkbox items.
Acts once per file — on the file-level note pass, detected by DATA's id
matching the context's file node — and keys every row to that file-level
note id.  Returns DATA unchanged (a side-effecting extractor)."
  (let* ((file-node (vulpea-parse-ctx-file-node ctx))
         (file-id (plist-get file-node :id)))
    (when (and file-id (equal (plist-get data :id) file-id))
      (let ((db (vulpea-db))
            (ast (vulpea-parse-ctx-ast ctx))
            (tbl-index -1)
            (ordinal -1))
        (org-element-map ast 'table
          (lambda (table)
            (when (eq (org-element-property :type table) 'org)
              (let* ((rows (jetpacs-crud-vulpea--table-rows table))
                     (ncols (apply #'max 0
                                   (mapcar (lambda (r) (if (listp r) (length r) 0))
                                           rows))))
                (cl-incf tbl-index)
                (emacsql db [:insert :into jetpacs-tables :values $v1]
                         (vector file-id
                                 (jetpacs-crud-vulpea--ancestor-heading table)
                                 tbl-index
                                 (org-element-property :begin table)
                                 ncols
                                 (jetpacs-crud-vulpea--encode-rows rows)))))))
        (org-element-map ast 'item
          (lambda (item)
            (when (org-element-property :checkbox item)
              (cl-incf ordinal)
              (emacsql db [:insert :into jetpacs-checklist :values $v1]
                       (vector file-id
                               (jetpacs-crud-vulpea--ancestor-heading item)
                               ordinal
                               (pcase (org-element-property :checkbox item)
                                 ('on "X") ('trans "-") (_ " "))
                               (jetpacs-crud-vulpea--item-text item)
                               (org-element-property :begin item))))))))
    data))

(defun jetpacs-crud-vulpea--register-extractor ()
  "Register the jetpacs tables+checklist extractor with vulpea (idempotent).
Creates the plugin tables (via the extractor `:schema') and installs the
extractor; re-registering the same name replaces it, so this is safe to
call repeatedly (e.g. after a refresh-hook re-probe)."
  (when (and (fboundp 'vulpea-db-register-extractor)
             (not jetpacs-crud-vulpea--extractor-registered))
    (vulpea-db-register-extractor
     (make-vulpea-extractor
      :name 'jetpacs-crud
      :version jetpacs-crud-vulpea--extractor-version
      :priority 100
      :schema '((jetpacs-tables
                 [(note-id :not-null) heading tbl-index begin ncols rows-json]
                 (:foreign-key [note-id] :references notes [id]
                  :on-delete :cascade))
                (jetpacs-checklist
                 [(note-id :not-null) heading ordinal state item-text pos]
                 (:foreign-key [note-id] :references notes [id]
                  :on-delete :cascade)))
      :extract-fn #'jetpacs-crud-vulpea--extract))
    (setq jetpacs-crud-vulpea--extractor-registered t)))

(defun jetpacs-crud-vulpea--file-note-id (file)
  "The vulpea id of FILE's file-level note, or nil.
The plugin rows are keyed to this id; resolving it first keeps the plugin
queries simple (no nested subquery) and correctly parameterized."
  (caar (emacsql (vulpea-db)
                 [:select id :from notes
                  :where (and (= path $s1) (= level 0))]
                 (expand-file-name file))))

(defun jetpacs-crud-vulpea-tables (file heading)
  "Org tables indexed for FILE under HEADING (nil = file scope).
Returns a list of scan plists (:begin :ncols :rows) — the shape
`jetpacs-crud--table-node' consumes — in document order."
  (when-let (((jetpacs-crud--vulpea-p))
             (nid (jetpacs-crud-vulpea--file-note-id file)))
    (let ((rows (if heading
                    (emacsql (vulpea-db)
                             [:select [begin ncols rows-json] :from jetpacs-tables
                              :where (and (= note-id $s1) (= heading $s2))
                              :order-by [(asc tbl-index)]]
                             nid heading)
                  (emacsql (vulpea-db)
                           [:select [begin ncols rows-json] :from jetpacs-tables
                            :where (and (= note-id $s1) (is heading nil))
                            :order-by [(asc tbl-index)]]
                           nid))))
      (mapcar (lambda (r)
                (list :begin (nth 0 r)
                      :ncols (nth 1 r)
                      :rows (jetpacs-crud-vulpea--decode-rows (nth 2 r))))
              rows))))

(defun jetpacs-crud-vulpea-checklist (file heading)
  "Checkbox items indexed for FILE under HEADING (nil = file scope).
Returns a list of (STATE TEXT POS), the shape
`jetpacs-crud--checklist-item-node' consumes, in document order."
  (when-let (((jetpacs-crud--vulpea-p))
             (nid (jetpacs-crud-vulpea--file-note-id file)))
    (if heading
        (emacsql (vulpea-db)
                 [:select [state item-text pos] :from jetpacs-checklist
                  :where (and (= note-id $s1) (= heading $s2))
                  :order-by [(asc ordinal)]]
                 nid heading)
      (emacsql (vulpea-db)
               [:select [state item-text pos] :from jetpacs-checklist
                :where (and (= note-id $s1) (is heading nil))
                :order-by [(asc ordinal)]]
               nid))))

(provide 'jetpacs-crud-vulpea)
;;; jetpacs-crud-vulpea.el ends here
