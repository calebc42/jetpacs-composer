;;; crud-tests.el --- ERT suite for the jetpacs-crud runtime -*- lexical-binding: t; -*-

;; Copyright (C) 2026 calebc42 and contributors
;; SPDX-License-Identifier: GPL-3.0-or-later

;; Run from the repo root (the jetpacs submodule provides the core):
;;
;;   emacs -Q --batch -l elisp/test/crud-tests.el -f ert-run-tests-batch-and-exit
;;
;; Mutation tests stage fixtures into a fresh temp directory per test,
;; so the corpus in test/fixtures/ is never modified.  Goldens pin the
;; wire JSON of the pantry view bodies; regenerate deliberately with
;; `jetpacs-crud-tests-regen-goldens' after an intentional wire change.

;;; Code:

(let* ((here (file-name-directory (or load-file-name buffer-file-name)))
       (elisp (directory-file-name (expand-file-name ".." here)))
       (core (expand-file-name "../../jetpacs/emacs/core" here)))
  (add-to-list 'load-path elisp)
  (when (file-directory-p core)
    (add-to-list 'load-path core)))

;; Optional: make vulpea available so the notes CRUD test runs instead of
;; skipping.  Its deps (emacsql, dash, s) come from installed ELPA packages;
;; vulpea itself from the checkout named by $VULPEA_DIR.  Without either, the
;; notes CRUD test skips (see its `skip-unless') and everything else is
;; unaffected — the runtime never hard-depends on vulpea.
(ignore-errors (package-activate-all))
(let ((vulpea-dir (getenv "VULPEA_DIR")))
  (when (and vulpea-dir (file-directory-p vulpea-dir))
    (add-to-list 'load-path vulpea-dir)))

(require 'ert)
(require 'jetpacs-lint)
(require 'jetpacs-crud)
(require 'jetpacs-crud-orgapp)

;; Batch shells (CI, wsl.exe) often run under a C locale; the corpus and
;; the goldens are UTF-8 (unicode fixtures, the ☑/☐ cell glyphs).
(prefer-coding-system 'utf-8)

(defconst jetpacs-crud-tests--here
  (file-name-directory (or load-file-name buffer-file-name)))

(defconst jetpacs-crud-tests--fixtures
  (expand-file-name "fixtures" jetpacs-crud-tests--here))

(defconst jetpacs-crud-tests--goldens
  (expand-file-name "goldens" jetpacs-crud-tests--here))

;; ─── Harness ─────────────────────────────────────────────────────────────────

(defvar jetpacs-crud-tests--temp-dirs nil)

(defun jetpacs-crud-tests--stage (fixture &rest extras)
  "Copy FIXTURE (+ EXTRAS) into a fresh temp dir; return the main file."
  (let ((dir (make-temp-file "jetpacs-crud-test" t)))
    (push dir jetpacs-crud-tests--temp-dirs)
    (dolist (f (cons fixture extras))
      (copy-file (expand-file-name f jetpacs-crud-tests--fixtures)
                 (expand-file-name f dir)))
    (expand-file-name fixture dir)))

(defun jetpacs-crud-tests--cleanup ()
  "Kill buffers visiting staged files and delete the temp dirs."
  (dolist (buf (buffer-list))
    (let ((file (buffer-file-name buf)))
      (when (and file
                 (cl-some (lambda (dir) (string-prefix-p dir file))
                          jetpacs-crud-tests--temp-dirs))
        (with-current-buffer buf (set-buffer-modified-p nil))
        (kill-buffer buf))))
  (dolist (dir jetpacs-crud-tests--temp-dirs)
    (ignore-errors (delete-directory dir t)))
  (setq jetpacs-crud-tests--temp-dirs nil))

(defmacro jetpacs-crud-tests--with-clean-state (&rest body)
  "Run BODY against empty shell/app/crud registries, cleaning up after."
  (declare (indent 0) (debug (body)))
  `(let ((jetpacs-shell-views nil)
         (jetpacs-shell-drawer-items jetpacs-shell-drawer-items)
         (jetpacs-shell-top-actions jetpacs-shell-top-actions)
         (jetpacs-shell--current-tab nil)
         (jetpacs-shell--snackbar nil)
         (jetpacs-apps--registry nil)
         (jetpacs-apps--current nil)
         (jetpacs-apps--fabs nil)
         (jetpacs-crud--apps nil)
         (jetpacs--registration-owners (make-hash-table :test 'equal))
         ;; Pushes bump the persisted revision counter; keep the test
         ;; runs out of the real ~/.emacs.d.
         (jetpacs--revision 0)
         (jetpacs-revision-file (make-temp-file "jetpacs-rev")))
     (unwind-protect (progn ,@body)
       (jetpacs-crud-tests--cleanup))))

(defun jetpacs-crud-tests--slurp (file)
  (with-temp-buffer (insert-file-contents file) (buffer-string)))

(defun jetpacs-crud-tests--parser-parity-cases ()
  "Read the shared parser accept/reject manifest."
  (with-temp-buffer
    (insert-file-contents
     (expand-file-name "parser-parity.manifest" jetpacs-crud-tests--fixtures))
    (let (cases)
      (dolist (line (split-string (buffer-string) "\n" t))
        (setq line (string-trim line))
        (unless (or (string-empty-p line) (string-prefix-p "#" line))
          (let ((fields (split-string line "[ \t]+" t)))
            (unless (= (length fields) 3)
              (error "Bad parser parity manifest line: %s" line))
            (push fields cases))))
      (nreverse cases))))

(defun jetpacs-crud-tests--cell-pos (id view-name data-row col)
  "Buffer position of DATA-ROW (1-based, header excluded) / COL's cell."
  (let* ((spec (jetpacs-crud--app id))
         (view (jetpacs-crud--view spec view-name))
         (table (cdr (jetpacs-crud--source-table spec view)))
         (rows (cl-remove 'hline (plist-get table :rows))))
    (cdr (nth (1- col) (nth data-row rows)))))

(defun jetpacs-crud-tests--item-pos (id view-name text)
  "Position of the checklist item whose text is TEXT."
  (let* ((spec (jetpacs-crud--app id))
         (view (jetpacs-crud--view spec view-name))
         (source (jetpacs-crud--view-source spec view)))
    (jetpacs-crud--with-source (car source)
      (nth 2 (cl-find text (jetpacs-crud--scan-checklist (cdr source))
                      :key #'cadr :test #'equal)))))

(defun jetpacs-crud-tests--body-json (id view-name)
  "The serialized wire JSON of ID's VIEW-NAME body, as multibyte text.
`json-serialize' returns UTF-8 bytes (a unibyte string) when the tree
contains non-ASCII — decode so the goldens are comparable text."
  (let* ((spec (jetpacs-crud--app id))
         (view (jetpacs-crud--view spec view-name)))
    (decode-coding-string
     (json-serialize (if (eq (plist-get view :kind) 'checklist)
                         (jetpacs-crud--checklist-body spec view)
                       (jetpacs-crud--table-body spec view))
                     :null-object :null :false-object :false)
     'utf-8)))

;; ─── Parsing ─────────────────────────────────────────────────────────────────

(ert-deftest jetpacs-crud-parser-parity-manifest ()
  "The device parser obeys the shared JVM/ERT accept/reject contract."
  (dolist (case (jetpacs-crud-tests--parser-parity-cases))
    (let ((name (nth 0 case))
          (expectation (nth 1 case))
          (file (expand-file-name (nth 2 case)
                                  jetpacs-crud-tests--fixtures)))
      (pcase expectation
        ("accept"
         (condition-case err
             (should (jetpacs-crud-parse-app file))
           (error (ert-fail (format "%s should accept %s, got %S"
                                    name file err)))))
        ("reject"
         (should-error (jetpacs-crud-parse-app file) :type 'user-error))
        (_ (ert-fail (format "Unknown parser parity expectation %S"
                             expectation)))))))

(ert-deftest jetpacs-crud-parser-parity-fixture-covers-accepted-surface ()
  "Pin the semantics represented by the shared all-surface fixture."
  (let* ((file (expand-file-name "parser-parity-all.org"
                                 jetpacs-crud-tests--fixtures))
         (spec (jetpacs-crud-parse-app file))
         (views (plist-get spec :views))
         (table (car views))
         (records (cl-find 'records views
                           :key (lambda (view) (plist-get view :kind))))
         (notes (cl-find 'notes views
                         :key (lambda (view) (plist-get view :kind))))
         (board (cl-find 'board views
                         :key (lambda (view) (plist-get view :kind))))
         (calendar (cl-find 'calendar views
                            :key (lambda (view) (plist-get view :kind))))
         (gallery (cl-find 'gallery views
                           :key (lambda (view) (plist-get view :kind)))))
    (should (equal (mapcar (lambda (view) (plist-get view :kind)) views)
                   '(table checklist records notes board calendar gallery)))
    (should (equal (plist-get table :icon) "table_chart"))
    (should (= (plist-get table :order) 10))
    (should (equal (plist-get table :columns)
                   '("Text" "Number" "Date" "Done" "Choice")))
    (should (equal (plist-get table :coltypes)
                   '(text number date checkbox (enum "A" "B"))))
    (should (equal (mapcar #'car (plist-get records :schema))
                   '("ITEM" "TODO" "DEADLINE" "SCHEDULED" "PRIORITY"
                     "TAGS" "EFFORT" "CATEGORY")))
    (should (equal (plist-get records :actions)
                   "todo(DONE) schedule deadline tags(work,home) priority(A) refile(* Archive) archive(subtree)"))
    (should (equal (plist-get records :filter) "(todo \"TODO\")"))
    (should (plist-get (plist-get notes :source) :dir))
    (should (equal (plist-get board :group-by) "TODO"))
    (should (equal (plist-get calendar :date-field) "DEADLINE"))
    (should (equal (plist-get gallery :image-field) "Photo"))
    (with-temp-buffer
      (insert-file-contents file)
      (let ((keywords (jetpacs-crud-orgapp--keywords)))
        (dolist (name '("JETPACS_APP" "TITLE" "JETPACS_ICON" "JETPACS_ORDER"
                        "JETPACS_APP_FORMAT" "TODO" "TAGS"))
          (should (assoc name keywords)))))))

(ert-deftest jetpacs-crud-parse-pantry ()
  (let ((spec (jetpacs-crud-parse-app
               (expand-file-name "pantry.org" jetpacs-crud-tests--fixtures))))
    (should (equal (plist-get spec :id) "pantry"))
    (should (equal (plist-get spec :label) "Pantry"))
    (should (equal (plist-get spec :icon) "kitchen"))
    (let ((views (plist-get spec :views)))
      (should (= (length views) 2))
      (let ((inv (car views)) (shop (cadr views)))
        (should (equal (plist-get inv :name) "inventory"))
        (should (eq (plist-get inv :kind) 'table))
        (should (equal (plist-get inv :coltypes)
                       '(text number date (enum "Low" "Mid" "High") checkbox)))
        (should (null (plist-get inv :source)))
        (should (= (plist-get inv :order) 10))
        (should (equal (plist-get shop :name) "shopping"))
        (should (eq (plist-get shop :kind) 'checklist))
        (should (= (plist-get shop :order) 20))))))

(ert-deftest jetpacs-crud-parse-case-variants ()
  (let ((spec (jetpacs-crud-parse-app
               (expand-file-name "case-variants.org"
                                 jetpacs-crud-tests--fixtures))))
    (should (equal (plist-get spec :id) "shelf"))
    (should (equal (plist-get spec :icon) "inventory_2"))
    (should (= (plist-get spec :order) 42))
    (let ((view (car (plist-get spec :views))))
      (should (equal (plist-get view :coltypes) '(text number)))
      (should (equal (plist-get view :icon) "category"))
      (should (= (plist-get view :order) 7)))))

(ert-deftest jetpacs-crud-parse-external-source ()
  (let* ((file (expand-file-name "external-source.org"
                                 jetpacs-crud-tests--fixtures))
         (spec (jetpacs-crud-parse-app file))
         (view (car (plist-get spec :views)))
         (source (plist-get view :source)))
    (should (equal (plist-get source :file)
                   (expand-file-name "stock-backend.org"
                                     (file-name-directory file))))
    (should (equal (plist-get source :heading) "Stock"))
    (should (equal (plist-get view :columns) '("Part" "Count")))))

(ert-deftest jetpacs-crud-parse-malformed ()
  (dolist (fixture '("malformed-no-app.org" "malformed-dup.org"))
    (should-error (jetpacs-crud-parse-app
                   (expand-file-name fixture jetpacs-crud-tests--fixtures))
                  :type 'user-error)))

(ert-deftest jetpacs-crud-parse-unknown-coltype-degrades ()
  "Vocabulary is forward-compatible: an unknown COLTYPES token parses
as an `unknown' marker (rendered as text) instead of erroring."
  (let* ((spec (jetpacs-crud-parse-app
                (expand-file-name "malformed-coltype.org"
                                  jetpacs-crud-tests--fixtures)))
         (view (car (plist-get spec :views))))
    (should (equal (plist-get view :coltypes) '(text (unknown "florb"))))))

(ert-deftest jetpacs-crud-parse-unicode ()
  (let ((spec (jetpacs-crud-parse-app
               (expand-file-name "unicode.org" jetpacs-crud-tests--fixtures))))
    (should (equal (plist-get spec :label) "食料品 🍙"))
    (should (= (length (plist-get spec :views)) 1))))

;; ─── Registration ────────────────────────────────────────────────────────────

(ert-deftest jetpacs-crud-register-effects ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (should (equal (jetpacs-crud-register-file file) "pantry"))
      (should (assoc "pantry.inventory" jetpacs-shell-views))
      (should (assoc "pantry.shopping" jetpacs-shell-views))
      (should (assoc "pantry" jetpacs-apps--registry))
      (should (jetpacs-crud--app "pantry")))))

(ert-deftest jetpacs-crud-register-reload-idempotent ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((views (length jetpacs-shell-views))
            (apps (length jetpacs-apps--registry)))
        (jetpacs-crud-register-file file)
        (should (= (length jetpacs-shell-views) views))
        (should (= (length jetpacs-apps--registry) apps))
        (should (= (length jetpacs-crud--apps) 1))))))

(ert-deftest jetpacs-crud-register-scaffolds-external-source ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "external-source.org"))
           (backend (expand-file-name "stock-backend.org"
                                      (file-name-directory file))))
      (should-not (file-exists-p backend))
      (jetpacs-crud-register-file file)
      (should (file-exists-p backend))
      (let ((content (jetpacs-crud-tests--slurp backend)))
        (should (string-match-p "^\\* Stock" content))
        (should (string-match-p "| Part | Count |" content))))))

;; ─── Rendering ───────────────────────────────────────────────────────────────

(ert-deftest jetpacs-crud-views-lint-clean ()
  (jetpacs-crud-tests--with-clean-state
    (dolist (fixture '("pantry.org" "unicode.org"))
      (let ((file (jetpacs-crud-tests--stage fixture)))
        (jetpacs-crud-register-file file)))
    (dolist (entry jetpacs-shell-views)
      (let ((view (funcall (plist-get (cdr entry) :builder) nil)))
        (should (null (jetpacs-lint-spec view)))
        (should (jetpacs-render-to-json view))))))

;; ─── The canonical kitchen sink ─────────────────────────────────────────────
;;
;; hello-world.org is the one document that exercises every FORMAT-1
;; surface.  These two tests are its teeth: coverage fails when a new
;; kind is registered without growing the fixture, and the lint pass
;; builds every body against the real widget constructors — the bugs a
;; parse-only test can never see.

(ert-deftest jetpacs-crud-hello-world-covers-every-kind ()
  "Every registered datasource kind appears in the kitchen sink."
  (let* ((spec (jetpacs-crud-parse-app
                (expand-file-name "hello-world.org"
                                  jetpacs-crud-tests--fixtures)))
         (kinds (mapcar (lambda (v) (plist-get v :kind))
                        (plist-get spec :views))))
    (dolist (kind (mapcar #'car jetpacs-crud--kinds))
      (should (memq kind kinds)))))

(ert-deftest jetpacs-crud-hello-world-registers-scaffolds-and-lints ()
  "Register the kitchen sink and build every view body for real."
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "hello-world.org")))
      (should (equal (jetpacs-crud-register-file file) "hello-world"))
      ;; The Shopping view scaffolded its relative external source.
      (should (file-exists-p (expand-file-name
                              "shopping-list.org"
                              (file-name-directory file))))
      ;; Nine views, but six shell views: the four :GROUP: Tasks members
      ;; collapse into one tabulated destination; the rest (two of them
      ;; :NAV: drawer) each register their own.
      (should (= (length jetpacs-shell-views) 6))
      (dolist (entry jetpacs-shell-views)
        (let ((body (funcall (plist-get (cdr entry) :builder) nil)))
          (should (null (jetpacs-lint-spec body)))
          (should (jetpacs-render-to-json body)))))))

;; ─── Update-in-place install (preserve on-device data on redeploy) ──────────
;;
;; A redeployed bundle carries the current structure but the composer's
;; template data.  jetpacs-crud-install must adopt the new structure while
;; keeping whatever the device has edited under each still-present view.

(defmacro jetpacs-crud-tests--with-apps-dir (var &rest body)
  "Bind `jetpacs-crud-apps-directory' to a fresh temp dir named VAR for BODY."
  (declare (indent 1) (debug (symbolp body)))
  `(let* ((,var (make-temp-file "jetpacs-crud-install" t))
          (jetpacs-crud-apps-directory ,var))
     (push ,var jetpacs-crud-tests--temp-dirs)
     ,@body))

(ert-deftest jetpacs-crud-install-updates-drawer-keeps-inline-rows ()
  "A redeploy adopts the new property drawer but preserves inline rows."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-tests--with-apps-dir dir
      (let ((file (expand-file-name "shop.org" dir))
            (v1 (concat "#+JETPACS_APP: shop\n#+TITLE: Shop\n\n"
                        "* Items\n:PROPERTIES:\n:ICON: table_chart\n"
                        ":ORDER: 10\n:COLTYPES: text number\n:END:\n\n"
                        "| Item | Qty |\n|------+-----|\n| Milk | 2 |\n")))
        (jetpacs-crud-install "shop" v1)
        ;; The device adds a row.
        (jetpacs-crud-orgapp--write
         file (replace-regexp-in-string
               "| Milk | 2 |\n" "| Milk | 2 |\n| Eggs | 12 |\n"
               (jetpacs-crud-orgapp--slurp file)))
        ;; Redeploy: same view, drawer gains :NAV: drawer + a new icon,
        ;; carrying only the template row.
        (jetpacs-crud-install
         "shop" (concat "#+JETPACS_APP: shop\n#+TITLE: Shop\n\n"
                        "* Items\n:PROPERTIES:\n:ICON: inventory\n"
                        ":ORDER: 10\n:COLTYPES: text number\n:NAV: drawer\n:END:\n\n"
                        "| Item | Qty |\n|------+-----|\n| Milk | 2 |\n"))
        (let ((result (jetpacs-crud-orgapp--slurp file)))
          (should (string-match-p ":NAV: drawer" result)) ; new structure
          (should (string-match-p ":ICON: inventory" result))
          (should (string-match-p "Eggs" result))         ; kept device data
          (should (string-match-p "12" result)))))))

(ert-deftest jetpacs-crud-install-preserves-records-adds-and-drops ()
  "A redeploy keeps device-added records, drops removed views, adds new ones."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-tests--with-apps-dir dir
      (let ((file (expand-file-name "todo.org" dir))
            (v1 (concat "#+JETPACS_APP: todo\n#+TITLE: Todo\n#+TODO: TODO | DONE\n\n"
                        "* Tasks\n:PROPERTIES:\n:KIND: records\n:ORDER: 10\n"
                        ":SCHEMA: %ITEM(Task) %TODO(Status)\n"
                        ":COLTYPES: text enum(TODO,DONE)\n:END:\n\n"
                        "** TODO Buy milk\n"
                        "* Notes\n:PROPERTIES:\n:ORDER: 20\n:END:\n\n"
                        "| Note |\n|------|\n| hi |\n")))
        (jetpacs-crud-install "todo" v1)
        ;; The device adds a record under Tasks.
        (jetpacs-crud-orgapp--write
         file (replace-regexp-in-string
               "\\*\\* TODO Buy milk\n" "** TODO Buy milk\n** DONE Call mom\n"
               (jetpacs-crud-orgapp--slurp file)))
        ;; Redeploy: Tasks gains a FILTER, Notes is gone, Board is new.
        (jetpacs-crud-install
         "todo" (concat "#+JETPACS_APP: todo\n#+TITLE: Todo\n#+TODO: TODO | DONE\n\n"
                        "* Tasks\n:PROPERTIES:\n:KIND: records\n:ORDER: 10\n"
                        ":SCHEMA: %ITEM(Task) %TODO(Status)\n"
                        ":COLTYPES: text enum(TODO,DONE)\n:FILTER: (todo)\n:END:\n\n"
                        "** TODO Buy milk\n"
                        "* Board\n:PROPERTIES:\n:KIND: board\n:ORDER: 30\n"
                        ":SCHEMA: %ITEM(Card) %TODO(Status)\n"
                        ":COLTYPES: text enum(TODO,DONE)\n:GROUP_BY: TODO\n:END:\n\n"
                        "** TODO Ship it\n"))
        (let ((result (jetpacs-crud-orgapp--slurp file))
              (spec (jetpacs-crud--app "todo")))
          (should (string-match-p ":FILTER: (todo)" result)) ; new drawer
          (should (string-match-p "Call mom" result))        ; kept device record
          (should-not (string-match-p "^\\* Notes" result))  ; dropped
          (should (string-match-p "^\\* Board" result))      ; added
          (should (string-match-p "Ship it" result))
          (let ((titles (mapcar (lambda (v) (plist-get v :title))
                                (plist-get spec :views))))
            (should (member "Tasks" titles))
            (should (member "Board" titles))
            (should-not (member "Notes" titles))))))))

(ert-deftest jetpacs-crud-install-first-time-writes-verbatim ()
  "The first install of an id writes the document text unchanged."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-tests--with-apps-dir dir
      (let ((file (expand-file-name "shop.org" dir))
            (text (concat "#+JETPACS_APP: shop\n#+TITLE: Shop\n\n"
                          "* Items\n:PROPERTIES:\n:COLTYPES: text\n:END:\n\n"
                          "| Item |\n|------|\n| Milk |\n")))
        (jetpacs-crud-install "shop" text)
        (should (equal (jetpacs-crud-orgapp--slurp file) text))))))

(ert-deftest jetpacs-crud-install-keeps-old-on-bad-merge ()
  "A structurally invalid redeploy leaves the on-device document intact."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-tests--with-apps-dir dir
      (let ((file (expand-file-name "shop.org" dir)))
        (jetpacs-crud-install
         "shop" (concat "#+JETPACS_APP: shop\n#+TITLE: Shop\n\n"
                        "* Items\n:PROPERTIES:\n:COLTYPES: text\n:END:\n\n"
                        "| Item |\n|------|\n| Milk |\n"))
        ;; A redeploy that would merge to a view-less (invalid) document:
        ;; install must not error, keep the file, and still register the old.
        (should (equal (jetpacs-crud-install
                        "shop" "#+JETPACS_APP: shop\n#+TITLE: Shop\n\nno views\n")
                       "shop"))
        (should (string-match-p "Milk" (jetpacs-crud-orgapp--slurp file)))
        (should (jetpacs-crud--app "shop"))))))

(ert-deftest jetpacs-crud-goldens ()
  "The pantry bodies' wire JSON, byte-for-byte."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-register-file (jetpacs-crud-tests--stage "pantry.org"))
    (dolist (pair '(("inventory" . "pantry-inventory.json")
                    ("shopping" . "pantry-shopping.json")))
      (let ((golden (expand-file-name (cdr pair) jetpacs-crud-tests--goldens)))
        (should (file-readable-p golden))
        (should (equal (jetpacs-crud-tests--body-json "pantry" (car pair))
                       (string-trim-right
                        (jetpacs-crud-tests--slurp golden))))))))

(defun jetpacs-crud-tests-regen-goldens ()
  "Regenerate the golden files from the current renderer output.
Only for intentional wire changes; review the diff."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-register-file (jetpacs-crud-tests--stage "pantry.org"))
    (make-directory jetpacs-crud-tests--goldens t)
    (dolist (pair '(("inventory" . "pantry-inventory.json")
                    ("shopping" . "pantry-shopping.json")))
      (with-temp-file (expand-file-name (cdr pair) jetpacs-crud-tests--goldens)
        (insert (jetpacs-crud-tests--body-json "pantry" (car pair)) "\n")))
    (message "Goldens regenerated under %s" jetpacs-crud-tests--goldens)))

(ert-deftest jetpacs-crud-empty-source-renders-empty-state ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "external-source.org"))
           (backend (expand-file-name "stock-backend.org"
                                      (file-name-directory file))))
      (jetpacs-crud-register-file file)
      ;; Wipe the scaffolded table: the view must degrade, not error.
      (with-temp-file backend (insert "* Stock\n\nno table here\n"))
      (let ((view (funcall (plist-get
                            (cdr (assoc "stockroom.stock" jetpacs-shell-views))
                            :builder)
                           nil)))
        (should (null (jetpacs-lint-spec view)))
        (should (string-match-p "No data yet"
                                (json-serialize view :null-object :null
                                                :false-object :false)))))))

;; ─── Mutations ───────────────────────────────────────────────────────────────

(ert-deftest jetpacs-crud-cell-edit-text-and-number ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--cell-pos "pantry" "inventory" 1 2)))
        (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "5")))
          (jetpacs-crud-action-cell-edit
           `((app . "pantry") (view . "inventory") (pos . ,pos)) nil)))
      (should (string-match-p "| *Rice *| *5 *|"
                              (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-cell-edit-number-rejects-garbage ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--cell-pos "pantry" "inventory" 1 2)))
        (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "many")))
          (should-error
           (jetpacs-crud-action-cell-edit
            `((app . "pantry") (view . "inventory") (pos . ,pos)) nil)
           :type 'user-error))
        ;; Unchanged on rejection.
        (should (string-match-p "| *Rice *| *2 *|"
                                (jetpacs-crud-tests--slurp file)))))))

(ert-deftest jetpacs-crud-cell-edit-date-rejects-garbage ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--cell-pos "pantry" "inventory" 1 3)))
        (cl-letf (((symbol-function 'read-string)
                   (lambda (&rest _) "next tuesday")))
          (should-error
           (jetpacs-crud-action-cell-edit
            `((app . "pantry") (view . "inventory") (pos . ,pos)) nil)
           :type 'user-error))))))

(ert-deftest jetpacs-crud-cell-edit-enum ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--cell-pos "pantry" "inventory" 1 4)))
        (cl-letf (((symbol-function 'completing-read)
                   (lambda (&rest _) "High")))
          (jetpacs-crud-action-cell-edit
           `((app . "pantry") (view . "inventory") (pos . ,pos)) nil)))
      (should (string-match-p "| *Rice *| *2 *| *2026-09-01 *| *High *|"
                              (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-cell-toggle-checkbox ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--cell-pos "pantry" "inventory" 1 5)))
        (jetpacs-crud-action-cell-toggle
         `((app . "pantry") (view . "inventory") (pos . ,pos)) nil))
      (should (string-match-p "| *Rice *|.*| *\\[X\\] *|"
                              (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-row-add ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((inputs (list "Beans" "4" "2027-01-01")))
        (cl-letf (((symbol-function 'read-string)
                   (lambda (&rest _) (pop inputs)))
                  ((symbol-function 'completing-read)
                   (lambda (&rest _) "High")))
          (jetpacs-crud-action-row-add
           '((app . "pantry") (view . "inventory")) nil)))
      (should (string-match-p
               "| *Beans *| *4 *| *2027-01-01 *| *High *| *\\[ \\] *|"
               (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-row-menu-delete ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--cell-pos "pantry" "inventory" 2 1)))
        (cl-letf (((symbol-function 'completing-read)
                   (lambda (&rest _) "Delete row")))
          (jetpacs-crud-action-row-menu
           `((app . "pantry") (view . "inventory") (pos . ,pos)) nil)))
      (should-not (string-match-p "Milk" (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-checkbox-toggle ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--item-pos "pantry" "shopping"
                                               "Olive oil")))
        (jetpacs-crud-action-checkbox-toggle
         `((app . "pantry") (view . "shopping") (pos . ,pos)) nil))
      (should (string-match-p "- \\[X\\] Olive oil"
                              (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-item-add ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "Bread")))
        (jetpacs-crud-action-item-add
         '((app . "pantry") (view . "shopping")) nil))
      (should (string-match-p "- \\[X\\] Coffee\n- \\[ \\] Bread"
                              (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-row-add-into-scaffolded-backend ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "external-source.org"))
           (backend (expand-file-name "stock-backend.org"
                                      (file-name-directory file))))
      (jetpacs-crud-register-file file)
      (let ((inputs (list "Bolt" "12")))
        (cl-letf (((symbol-function 'read-string)
                   (lambda (&rest _) (pop inputs))))
          (jetpacs-crud-action-row-add
           '((app . "stockroom") (view . "stock")) nil)))
      (should (string-match-p "| *Bolt *| *12 *|"
                              (jetpacs-crud-tests--slurp backend))))))

;; ─── Records views (headings + property drawers) ────────────────────────────

(defun jetpacs-crud-tests--record-pos (id view-name title)
  "Position of the record whose ITEM is TITLE in ID's VIEW-NAME."
  (let* ((spec (jetpacs-crud--app id))
         (view (jetpacs-crud--view spec view-name)))
    (plist-get
     (cl-find title (jetpacs-crud--scan-records spec view)
              :key (lambda (r)
                     (alist-get "ITEM" (plist-get r :fields) nil nil #'equal))
              :test #'equal)
     :pos)))

(ert-deftest jetpacs-crud-records-parse-and-scan ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "crm.org" "people.org")))
      (jetpacs-crud-register-file file)
      (let* ((spec (jetpacs-crud--app "crm"))
             (people (jetpacs-crud--view spec "people")))
        ;; %todo upcased to the special property; labels kept.
        (should (equal (mapcar #'car (plist-get people :schema))
                       '("ITEM" "TODO" "Phone" "Tier" "DEADLINE")))
        (should (equal (cdr (assoc "TODO" (plist-get people :schema)))
                       "Status"))
        ;; Only the direct children of *Contacts are records.
        (let ((records (jetpacs-crud--scan-records spec people)))
          (should (= (length records) 2))
          (should (equal (mapcar (lambda (r)
                                   (alist-get "ITEM" (plist-get r :fields)
                                              nil nil #'equal))
                                 records)
                         '("Ada Lovelace" "Grace Hopper")))
          ;; Fields ride org-entry-get: specials and drawer props alike.
          (let ((ada (car records)))
            (should (equal (alist-get "TODO" (plist-get ada :fields)
                                      nil nil #'equal)
                           "ACTIVE"))
            (should (equal (alist-get "Phone" (plist-get ada :fields)
                                      nil nil #'equal)
                           "555-0100"))))
        ;; The filtered view sees only Gold-tier records.
        (let ((gold (jetpacs-crud--view spec "gold")))
          (should (equal (mapcar (lambda (r)
                                   (alist-get "ITEM" (plist-get r :fields)
                                              nil nil #'equal))
                                 (jetpacs-crud--scan-records spec gold))
                         '("Ada Lovelace"))))
        ;; Inline records: children of the view heading in the app doc.
        (let ((scratch (jetpacs-crud--view spec "scratch")))
          (should (= (length (jetpacs-crud--scan-records spec scratch)) 1)))))))

(ert-deftest jetpacs-crud-records-views-lint-clean ()
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-register-file (jetpacs-crud-tests--stage "crm.org" "people.org"))
    (dolist (entry jetpacs-shell-views)
      (let ((view (funcall (plist-get (cdr entry) :builder) nil)))
        (should (null (jetpacs-lint-spec view)))
        (should (jetpacs-render-to-json view))))))

(ert-deftest jetpacs-crud-field-edit-preserves-record-body ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file))))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Ada Lovelace")))
        (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "555-0111")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (pos . ,pos) (prop . "Phone")) nil)))
      (let ((content (jetpacs-crud-tests--slurp data)))
        (should (string-match-p ":Phone: *555-0111" content))
        (should (string-match-p "Notes about Ada that must survive" content))
        (should (string-match-p "Prose before any heading" content))))))

(ert-deftest jetpacs-crud-field-edit-todo-uses-real-keywords ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file)))
           (offered nil))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Grace Hopper")))
        (cl-letf (((symbol-function 'completing-read)
                   (lambda (_prompt collection &rest _)
                     (setq offered collection) "LOST")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (pos . ,pos) (prop . "TODO")) nil)))
      ;; The file's own #+TODO: sequence, not a hardcoded one.
      (should (member "LEAD" offered))
      (should (member "LOST" offered))
      (should (string-match-p "^\\*\\* LOST Grace Hopper"
                              (jetpacs-crud-tests--slurp data))))))

(ert-deftest jetpacs-crud-field-edit-enum-from-prop-all ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file)))
           (offered nil))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Grace Hopper")))
        (cl-letf (((symbol-function 'completing-read)
                   (lambda (_prompt collection &rest _)
                     (setq offered collection) "Bronze")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (pos . ,pos) (prop . "Tier")) nil)))
      ;; Options came from the file's Tier_ALL declaration.
      (should (member "Bronze" offered))
      (should (string-match-p ":Tier: *Bronze"
                              (jetpacs-crud-tests--slurp data))))))

(ert-deftest jetpacs-crud-field-edit-deadline-writes-planning-line ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file))))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Ada Lovelace")))
        (cl-letf (((symbol-function 'read-string)
                   (lambda (&rest _) "2027-01-01")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (pos . ,pos) (prop . "DEADLINE"))
           nil)))
      (should (string-match-p "DEADLINE: <2027-01-01"
                              (jetpacs-crud-tests--slurp data))))))

(ert-deftest jetpacs-crud-field-edit-title-keeps-todo-keyword ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file))))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Ada Lovelace")))
        (cl-letf (((symbol-function 'read-string)
                   (lambda (&rest _) "Ada King")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (pos . ,pos) (prop . "ITEM")) nil)))
      (should (string-match-p "^\\*\\* ACTIVE Ada King"
                              (jetpacs-crud-tests--slurp data))))))

(ert-deftest jetpacs-crud-record-add-lands-inside-source-subtree ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file))))
      (jetpacs-crud-register-file file)
      ;; Adding is a two-step flow: crud.record.add composes a form
      ;; sheet whose inputs write jetpacs-ui-state under add_<PROP>,
      ;; and crud.record.add.submit consumes that state.
      (jetpacs-crud-action-record-add
       '((app . "crm") (view . "people")) nil)
      (jetpacs-ui-state-put "add_ITEM" "Katherine Johnson")
      (jetpacs-ui-state-put "add_TODO" "ACTIVE")
      (jetpacs-ui-state-put "add_Phone" "555-0202")
      (jetpacs-ui-state-put "add_Tier" "Gold")
      (jetpacs-crud-action-record-add-submit
       '((app . "crm") (view . "people")) nil)
      (let ((content (jetpacs-crud-tests--slurp data)))
        ;; New record exists, with its drawer, BEFORE * Unrelated.
        (should (string-match-p "^\\*\\* ACTIVE Katherine Johnson" content))
        (should (string-match-p ":Phone: *555-0202" content))
        (should (< (string-match "Katherine Johnson" content)
                   (string-match "^\\* Unrelated" content)))))))

(ert-deftest jetpacs-crud-field-clear-removes-specials-for-real ()
  "Clearing TODO/DEADLINE must go through org's removal commands —
`org-entry-put' with nil is a silent no-op for special properties."
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file))))
      (jetpacs-crud-register-file file)
      ;; Give Ada a deadline, then clear both her deadline and her state.
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Ada Lovelace")))
        (cl-letf (((symbol-function 'read-string)
                   (lambda (&rest _) "2027-01-01")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (pos . ,pos) (prop . "DEADLINE"))
           nil)))
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Ada Lovelace")))
        (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (pos . ,pos) (prop . "DEADLINE"))
           nil)))
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Ada Lovelace")))
        (cl-letf (((symbol-function 'completing-read) (lambda (&rest _) "")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (pos . ,pos) (prop . "TODO")) nil)))
      (let ((content (jetpacs-crud-tests--slurp data)))
        (should-not (string-match-p "DEADLINE:" content))
        (should (string-match-p "^\\*\\* Ada Lovelace" content))))))

(ert-deftest jetpacs-crud-record-delete-confirmed-and-scoped ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file))))
      (jetpacs-crud-register-file file)
      (let ((pos (jetpacs-crud-tests--record-pos "crm" "people" "Grace Hopper")))
        ;; Declined confirmation deletes nothing.
        (cl-letf (((symbol-function 'y-or-n-p) (lambda (&rest _) nil)))
          (jetpacs-crud-action-record-menu
           `((app . "crm") (view . "people") (pos . ,pos)) nil))
        (should (string-match-p "Grace Hopper" (jetpacs-crud-tests--slurp data)))
        ;; Confirmed deletes exactly that subtree.
        (cl-letf (((symbol-function 'y-or-n-p) (lambda (&rest _) t)))
          (jetpacs-crud-action-record-menu
           `((app . "crm") (view . "people") (pos . ,pos)) nil)))
      (let ((content (jetpacs-crud-tests--slurp data)))
        (should-not (string-match-p "Grace Hopper" content))
        (should (string-match-p "Ada Lovelace" content))
        (should (string-match-p "^\\* Unrelated" content))))))

(ert-deftest jetpacs-crud-records-schema-required ()
  (let ((text "#+JETPACS_APP: bad\n\n* View\n:PROPERTIES:\n:KIND: records\n:END:\n"))
    (let ((file (make-temp-file "crud-noschema" nil ".org" text)))
      (should-error (jetpacs-crud-parse-app file) :type 'user-error))))

;; ─── Validation boundary ─────────────────────────────────────────────────────

(ert-deftest jetpacs-crud-rejects-unknown-app-and-view ()
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-register-file (jetpacs-crud-tests--stage "pantry.org"))
    (should-error (jetpacs-crud-action-cell-edit
                   '((app . "nope") (view . "inventory") (pos . 1)) nil)
                  :type 'user-error)
    (should-error (jetpacs-crud-action-cell-edit
                   '((app . "pantry") (view . "nope") (pos . 1)) nil)
                  :type 'user-error)
    (should-error (jetpacs-crud-action-cell-edit
                   '((app . "pantry") (view . "inventory")) nil)
                  :type 'user-error)))

;; ─── FILTER query engine ─────────────────────────────────────────────────────

(ert-deftest jetpacs-crud-query-parse-shapes ()
  "The three FILTER input shapes normalize to org-ql sexps."
  ;; Empty / blank → no query (every record).
  (should (null (jetpacs-crud--parse-query nil)))
  (should (null (jetpacs-crud--parse-query "   ")))
  ;; A sexp is read and its bare argument symbols stringified.
  (should (equal (jetpacs-crud--parse-query "(todo NEXT)") '(todo "NEXT")))
  (should (equal (jetpacs-crud--parse-query "(property \"Tier\" \"Gold\")")
                 '(property "Tier" "Gold")))
  ;; Filter tokens AND together; comma splits any-of.
  (should (equal (jetpacs-crud--parse-query "todo:NEXT tags:work,home")
                 '(and (todo "NEXT") (tags "work" "home"))))
  (should (equal (jetpacs-crud--parse-query "priority:A") '(priority "A")))
  ;; Bare words become substring (regexp) matches.
  (should (equal (jetpacs-crud--parse-query "foo bar")
                 '(and (regexp "foo") (regexp "bar"))))
  ;; A malformed sexp signals rather than matching nothing silently.
  (should-error (jetpacs-crud--parse-query "(unbalanced")
                :type 'user-error))

(defmacro jetpacs-crud-tests--in-org (content &rest body)
  "Run BODY in an `org-mode' temp buffer holding CONTENT.
A TODO/NEXT/DONE keyword sequence is in force for state tests."
  (declare (indent 1) (debug (form body)))
  `(with-temp-buffer
     (let ((org-todo-keywords '((sequence "TODO" "NEXT" "|" "DONE")))
           (org-mode-hook nil))
       (insert ,content)
       (org-mode)
       (goto-char (point-min))
       ,@body)))

(defun jetpacs-crud-tests--match-at (title filter)
  "Non-nil when the heading titled TITLE matches FILTER string.
Assumes the current buffer is an org buffer positioned anywhere."
  (goto-char (point-min))
  (re-search-forward (concat "^\\*+ .*" (regexp-quote title)))
  (org-back-to-heading t)
  (jetpacs-crud--entry-matches-p (jetpacs-crud--parse-query filter)))

(ert-deftest jetpacs-crud-query-interpreter ()
  "The built-in interpreter covers the common org-ql subset."
  (jetpacs-crud-tests--in-org
      "* NEXT Alpha :work:
:PROPERTIES:
:Tier: Gold
:END:
body mentions xylophone here
* DONE Beta :home:
* TODO Gamma :work:home:
** [#A] Delta
"
    ;; todo, with and without keywords
    (should (jetpacs-crud-tests--match-at "Alpha" "(todo \"NEXT\")"))
    (should-not (jetpacs-crud-tests--match-at "Beta" "(todo \"NEXT\")"))
    (should (jetpacs-crud-tests--match-at "Alpha" "(todo)"))     ; any not-done
    (should-not (jetpacs-crud-tests--match-at "Beta" "(todo)"))  ; DONE
    (should (jetpacs-crud-tests--match-at "Beta" "(done)"))
    (should-not (jetpacs-crud-tests--match-at "Alpha" "(done)"))
    ;; tags, any-of
    (should (jetpacs-crud-tests--match-at "Alpha" "(tags \"work\")"))
    (should-not (jetpacs-crud-tests--match-at "Beta" "(tags \"work\")"))
    (should (jetpacs-crud-tests--match-at "Gamma" "(tags \"work\" \"home\")"))
    ;; property, value and presence
    (should (jetpacs-crud-tests--match-at "Alpha" "(property \"Tier\" \"Gold\")"))
    (should-not (jetpacs-crud-tests--match-at "Gamma"
                                              "(property \"Tier\" \"Gold\")"))
    (should (jetpacs-crud-tests--match-at "Alpha" "(property \"Tier\")"))
    (should-not (jetpacs-crud-tests--match-at "Gamma" "(property \"Tier\")"))
    ;; regexp over heading + body
    (should (jetpacs-crud-tests--match-at "Alpha" "(regexp \"xylophone\")"))
    (should-not (jetpacs-crud-tests--match-at "Gamma" "(regexp \"xylophone\")"))
    ;; level and priority
    (should (jetpacs-crud-tests--match-at "Alpha" "(level 1)"))
    (should (jetpacs-crud-tests--match-at "Delta" "(level 2)"))
    (should (jetpacs-crud-tests--match-at "Delta" "(priority = \"A\")"))
    (should (jetpacs-crud-tests--match-at "Delta" "(priority > \"B\")")) ; A>B
    ;; boolean composition
    (should (jetpacs-crud-tests--match-at
             "Alpha" "(and (todo \"NEXT\") (tags \"work\"))"))
    (should (jetpacs-crud-tests--match-at "Beta" "(or (done) (tags \"work\"))"))
    (should (jetpacs-crud-tests--match-at "Gamma" "(not (done))"))
    ;; an unsupported term names org-ql rather than matching silently
    (should-error
     (progn (goto-char (point-min))
            (re-search-forward "Alpha")
            (org-back-to-heading t)
            (jetpacs-crud--entry-matches-p '(clocked)))
     :type 'user-error)))

;; ─── Notes (vulpea-backed) views ─────────────────────────────────────────────

(ert-deftest jetpacs-crud-parse-notes ()
  "A notes view parses its kind, both SOURCE shapes, and schema."
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "contacts.org"))
           (spec (jetpacs-crud-parse-app file))
           (people (jetpacs-crud--view spec "people"))
           (team (jetpacs-crud--view spec "team")))
      (should (eq (plist-get people :kind) 'notes))
      (should (eq (plist-get team :kind) 'notes))
      ;; Directory SOURCE → (:dir …) with a trailing slash.
      (should (string-suffix-p "/" (plist-get (plist-get people :source) :dir)))
      ;; file::*Heading SOURCE → (:file … :heading …).
      (should (equal (plist-get (plist-get team :source) :heading) "Team"))
      (should (string-suffix-p "roster.org"
                               (plist-get (plist-get team :source) :file)))
      (should (equal (mapcar #'car (plist-get people :schema))
                     '("ITEM" "Phone" "Tier"))))))

(ert-deftest jetpacs-crud-notes-requires-schema-and-source ()
  "A notes view without SCHEMA or without SOURCE is a format error."
  (let ((no-schema "#+JETPACS_APP: a\n\n* V\n:PROPERTIES:\n:KIND: notes\n\
:SOURCE: vault/\n:END:\n")
        (no-source "#+JETPACS_APP: a\n\n* V\n:PROPERTIES:\n:KIND: notes\n\
:SCHEMA: %ITEM\n:END:\n"))
    (let ((f1 (make-temp-file "crud-notes" nil ".org" no-schema))
          (f2 (make-temp-file "crud-notes" nil ".org" no-source)))
      (should-error (jetpacs-crud-parse-app f1) :type 'user-error)
      (should-error (jetpacs-crud-parse-app f2) :type 'user-error))))

(ert-deftest jetpacs-crud-notes-degrades-without-vulpea ()
  "Without vulpea a notes view renders a placeholder and lints clean —
the runtime still loads and builds on bare jetpacs-core."
  (jetpacs-crud-tests--with-clean-state
    (let ((jetpacs-crud--vulpea nil))   ; force the absent path deterministically
      (jetpacs-crud-register-file (jetpacs-crud-tests--stage "contacts.org"))
      (let* ((entry (assoc "contacts.people" jetpacs-shell-views))
             (view (funcall (plist-get (cdr entry) :builder) nil)))
        (should (null (jetpacs-lint-spec view)))
        (should (jetpacs-render-to-json view))
        ;; The placeholder names the missing dependency.
        (should (string-match-p "vulpea" (format "%S" view)))))))

(ert-deftest jetpacs-crud-notes-crud-roundtrip ()
  "End-to-end notes CRUD over a real vulpea vault (skipped without vulpea).
Uses an isolated, temporary vulpea index (mirrors vulpea's own test
harness) so the run never touches the developer's real note database."
  (skip-unless (require 'vulpea nil t))
  (jetpacs-crud-tests--with-clean-state
    (let* ((jetpacs-crud--vulpea 'unknown)
           (root (make-temp-file "jetpacs-notes" t))
           (vault (expand-file-name "vault/" root))
           (vulpea-directory vault)
           (vulpea-db-location (expand-file-name "vulpea.db" root))
           (vulpea-db--connection nil)
           (app-text (format "#+JETPACS_APP: contacts\n\n* People\n:PROPERTIES:\n\
:KIND: notes\n:SOURCE: %s\n:SCHEMA: %%ITEM(Name) %%Phone\n:END:\n" "vault/"))
           (app-file (expand-file-name "contacts.org" root)))
      (push root jetpacs-crud-tests--temp-dirs)
      (make-directory vault t)
      (with-temp-file app-file (insert app-text))
      (unwind-protect
          (progn
            (vulpea-db)                 ; a fresh, isolated index
            (jetpacs-crud-register-file app-file)
            (let* ((spec (jetpacs-crud--app "contacts"))
                   (view (jetpacs-crud--view spec "people")))
              ;; Add a note through the action, then the scan should see it.
              (let ((typed (list "Ada Lovelace" "555-0100")))
                (cl-letf (((symbol-function 'read-string)
                           (lambda (&rest _) (pop typed))))
                  (jetpacs-crud-action-note-add
                   '((app . "contacts") (view . "people")) nil)))
              (let ((records (jetpacs-crud--scan-notes spec view)))
                (should (= (length records) 1))
                (should (equal (alist-get "ITEM" (plist-get (car records) :fields)
                                          nil nil #'equal)
                               "Ada Lovelace"))
                (should (equal (alist-get "Phone" (plist-get (car records) :fields)
                                          nil nil #'equal)
                               "555-0100"))
                ;; Edit the phone via the note action, addressed by id.
                (let ((id (plist-get (car records) :id)))
                  (cl-letf (((symbol-function 'read-string)
                             (lambda (&rest _) "555-0199")))
                    (jetpacs-crud-action-note-field-edit
                     `((app . "contacts") (view . "people") (id . ,id)
                       (prop . "Phone"))
                     nil))
                  (should (equal (alist-get
                                  "Phone"
                                  (plist-get (car (jetpacs-crud--scan-notes spec view))
                                             :fields)
                                  nil nil #'equal)
                                 "555-0199"))
                  ;; Delete it; the vault empties.
                  (cl-letf (((symbol-function 'y-or-n-p) (lambda (&rest _) t)))
                    (jetpacs-crud-action-note-menu
                     `((app . "contacts") (view . "people") (id . ,id)) nil))
                  (should (null (jetpacs-crud--scan-notes spec view)))))))
        (when (and (boundp 'vulpea-db--connection) vulpea-db--connection)
          (vulpea-db-close))))))

(ert-deftest jetpacs-crud-test-app-parity ()
  "Parse the shared app-parity.org fixture to ensure parser parity."
  (jetpacs-crud-tests--with-clean-state
    (let* ((app-file (jetpacs-crud-tests--stage "app-parity.org"))
           (spec (jetpacs-crud-parse-app app-file)))
      (should (equal (plist-get spec :id) "parity"))
      (should (= (length (plist-get spec :views)) 5))
      (let ((board (nth 0 (plist-get spec :views)))
            (calendar (nth 1 (plist-get spec :views)))
            (gallery (nth 2 (plist-get spec :views)))
            (table (nth 3 (plist-get spec :views)))
            (records (nth 4 (plist-get spec :views))))
        (should (eq (plist-get board :kind) 'board))
        (should (equal (plist-get board :group-by) "TODO"))
        (should (eq (plist-get calendar :kind) 'calendar))
        (should (equal (plist-get calendar :date-field) "SCHEDULED"))
        (should (eq (plist-get gallery :kind) 'gallery))
        (should (equal (plist-get gallery :image-field) "IMAGE"))
        (should (eq (plist-get table :kind) 'table))
        (should (eq (plist-get records :kind) 'records))))))


(provide 'crud-tests)
;;; crud-tests.el ends here
