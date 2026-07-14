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
(when (getenv "ELPA_DIR")
  (setq package-user-dir (expand-file-name (getenv "ELPA_DIR"))))
(ignore-errors (package-activate-all))
(let ((vulpea-dir (getenv "VULPEA_DIR")))
  (when (and vulpea-dir (file-directory-p vulpea-dir))
    (add-to-list 'load-path vulpea-dir)))

(require 'ert)
(require 'jetpacs-lint)
(require 'jetpacs-crud)
(require 'jetpacs-crud-vulpea)
(require 'jetpacs-crud-orgapp)

;; Isolate the vulpea index for the whole run.  Registration now adopts
;; ids on source files and re-indexes them (`jetpacs-crud-vulpea-ensure-source'),
;; so without a private db location the suite would write the developer's
;; real note database.  Tests that need a fresh index still bind
;; `vulpea-db-location' (and reset the connection) locally; this only moves
;; the default away from ~/.emacs.d.
(setq vulpea-db-location
      (expand-file-name (format "jetpacs-crud-tests-%d.db" (emacs-pid))
                        temporary-file-directory))

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
         (jetpacs-crud--packs (make-hash-table :test 'equal))
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

(defun jetpacs-crud-tests--cell-args (id view-name data-row col)
  "Wire args addressing DATA-ROW (1-based, header excluded) / COL's cell.
The shape the renderer's cell nodes carry: logical (line, col) — the
header is table line 1, so DATA-ROW n is line n+1 — plus the index pos
as the optimistic hint."
  (let* ((spec (jetpacs-crud--app id))
         (view (jetpacs-crud--view spec view-name))
         (table (cdr (jetpacs-crud--source-table spec view)))
         (rows (cl-remove 'hline (plist-get table :rows))))
    `((app . ,id) (view . ,view-name)
      (line . ,(1+ data-row)) (col . ,col)
      (pos . ,(cdr (nth (1- col) (nth data-row rows)))))))

(defun jetpacs-crud-tests--item-args (id view-name text)
  "Wire args addressing the checklist item whose text is TEXT.
The shape the renderer's toggle carries: (idx, text) + the pos hint."
  (let* ((spec (jetpacs-crud--app id))
         (view (jetpacs-crud--view spec view-name))
         (source (jetpacs-crud--view-source spec view))
         (items (jetpacs-crud--with-source (car source)
                  (jetpacs-crud--scan-checklist (cdr source))))
         (idx (cl-position text items :key #'cadr :test #'equal)))
    `((app . ,id) (view . ,view-name)
      (idx . ,idx) (text . ,text)
      (pos . ,(nth 2 (nth idx items))))))

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

(ert-deftest jetpacs-crud-parse-ref-display-field ()
  (let* ((spec (jetpacs-crud-parse-app
                (expand-file-name "parser-parity-ref.org"
                                  jetpacs-crud-tests--fixtures)))
         (view (car (plist-get spec :views))))
    (should (equal (plist-get view :coltypes)
                   '(text (ref "Customers" "NAME"))))))

;; ─── Pack + unknown sources (S4.0: preserve, degrade, fail closed) ──────────

(ert-deftest jetpacs-crud-parse-pack-source ()
  "A well-formed pack: source parses to its (:pack ID :source NAME) shape,
and the FORMAT-4 `#+JETPACS_PACK:' declaration lands on the spec."
  (let* ((spec (jetpacs-crud-parse-app
                (expand-file-name "parser-parity-pack.org"
                                  jetpacs-crud-tests--fixtures)))
         (view (car (plist-get spec :views))))
    (should (equal (plist-get spec :pack)
                   '(:id "glasspane" :min-version "1.0.0")))
    (should (equal (plist-get view :source)
                   '(:pack "glasspane" :source "glasspane.notes")))
    (should (equal (plist-get view :actions)
                   "pack:glasspane/heading.todo-cycle todo(DONE)"))))

(ert-deftest jetpacs-crud-parse-pack-keyword-without-min-version ()
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-tests--with-apps-dir _dir
      (jetpacs-crud-install
       "packless"
       (concat "#+JETPACS_APP: packless\n#+JETPACS_PACK: somepack\n\n"
               "* V\n:PROPERTIES:\n:KIND: records\n:SCHEMA: %ITEM\n:END:\n"))
      (should (equal (plist-get (jetpacs-crud--app "packless") :pack)
                     '(:id "somepack" :min-version nil))))))

(ert-deftest jetpacs-crud-parse-unknown-source-scheme ()
  "A source scheme this runtime doesn't know is preserved, not rejected."
  (let* ((spec (jetpacs-crud-parse-app
                (expand-file-name "parser-parity-unknown-source.org"
                                  jetpacs-crud-tests--fixtures)))
         (view (car (plist-get spec :views))))
    (should (equal (plist-get view :source) '(:unknown "zzz:mystery/feed")))))

(ert-deftest jetpacs-crud-pack-view-degrades-and-fails-closed ()
  "A pack-source view renders the unavailable placeholder and dispatches
no mutation: no FAB, no export affordances, and every wire handler that
could touch a source file signals `user-error' before doing anything."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-register-file
     (jetpacs-crud-tests--stage "parser-parity-pack.org"))
    (let* ((spec (jetpacs-crud--app "packdemo"))
           (view (jetpacs-crud--view spec "linked-notes"))
           (built (jetpacs-crud--build-view "packdemo" "linked-notes" nil))
           (json (json-serialize built
                                 :null-object :null :false-object :false)))
      (should view)
      (should (null (jetpacs-lint-spec built)))
      (should (string-match-p "is unavailable" json))
      (should (string-match-p "pack:glasspane/glasspane.notes" json))
      (should (null (jetpacs-crud--fab spec view)))
      (should (null (jetpacs-crud--export-matrix spec view)))
      (let ((args '((app . "packdemo") (view . "linked-notes"))))
        (should-error (jetpacs-crud-action-record-add args nil)
                      :type 'user-error)
        (should-error (jetpacs-crud-action-apply
                       (append args '((token . "todo") (options . "DONE")
                                      (id . "deadbeef")))
                       nil)
                      :type 'user-error)))))

(ert-deftest jetpacs-crud-unknown-source-view-degrades ()
  "An unknown-scheme source degrades identically (future vocabulary)."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-register-file
     (jetpacs-crud-tests--stage "parser-parity-unknown-source.org"))
    (let* ((spec (jetpacs-crud--app "mystery"))
           (view (jetpacs-crud--view spec "feed"))
           (built (jetpacs-crud--build-view "mystery" "feed" nil))
           (json (json-serialize built
                                 :null-object :null :false-object :false)))
      (should (null (jetpacs-lint-spec built)))
      (should (string-match-p "is unavailable" json))
      (should (null (jetpacs-crud--fab spec view)))
      (should-error (jetpacs-crud-action-record-add
                     '((app . "mystery") (view . "feed")) nil)
                    :type 'user-error))))

;; ─── The pack binding layer (S4.3: fake pack, fail-closed paths) ────────────

(defconst jetpacs-crud-tests--toypack-doc
  (concat "#+JETPACS_APP: toy\n#+JETPACS_APP_FORMAT: 4\n"
          "#+JETPACS_PACK: toypack 1.0\n\n"
          "* Things\n:PROPERTIES:\n:KIND: records\n"
          ":SOURCE: pack:toypack/toypack.things\n"
          ":SCHEMA: %ITEM(Name) %COUNT(Count)\n:COLTYPES: text number\n"
          ":FILTER: hello\n"
          ":ACTIONS: pack:toypack/toy.ping(mode=fast)\n:END:\n")
  "A records view over a test-local pack source with one pack action.")

(defmacro jetpacs-crud-tests--with-toypack (&rest body)
  "Run BODY with a live fake pack: a registered source, action, and
manifest facts, in registries isolated from the suite's real ones.
Binds `calls' to the list of arg-alists the toy action received."
  (declare (indent 0) (debug (body)))
  `(jetpacs-crud-tests--with-clean-state
     (let ((jetpacs--sources (make-hash-table :test 'equal))
           (jetpacs--source-cache (make-hash-table :test 'equal))
           (jetpacs-action-handlers (copy-hash-table jetpacs-action-handlers))
           (jetpacs--action-catalog (copy-hash-table jetpacs--action-catalog))
           (calls nil))
       (ignore calls)
       (provide 'jetpacs-crud-tests-toypack) ; the pack's loadable feature
       (jetpacs-defsource "toypack.things"
         :params '((:name query :type "text"))
         :fields '((:name "ITEM" :type "text") (:name "COUNT" :type "number")
                   (:name "ref" :type "ref"))
         :query (lambda (params)
                  (list (list (cons "ITEM" (or (alist-get 'query params)
                                               "a thing"))
                              (cons "COUNT" "42")
                              (cons "ref" "toy-1")))))
       (jetpacs-defaction "toy.ping"
                          (lambda (args _payload) (push args calls)))
       (jetpacs-crud-pack-register "toypack"
                                   :feature 'jetpacs-crud-tests-toypack
                                   :version "1.0"
                                   :sources '("toypack.things")
                                   :actions '("toy.ping"))
       (jetpacs-crud-tests--with-apps-dir _dir
         (jetpacs-crud-install "toy" jetpacs-crud-tests--toypack-doc)
         ,@body))))

(ert-deftest jetpacs-crud-pack-binding-round-trips-render-and-action ()
  "A registered, available pack renders its source and dispatches its
action: the full render + action round-trip over a fake pack."
  (jetpacs-crud-tests--with-toypack
    (let* ((built (jetpacs-crud--build-view "toy" "things" nil))
           (json (json-serialize built
                                 :null-object :null :false-object :false)))
      (should (null (jetpacs-lint-spec built)))
      ;; The :FILTER: string bound the source's `query' param.
      (should (string-match-p "hello" json))
      (should (string-match-p "42" json))
      (should (string-match-p "crud.pack.action" json))
      (should-not (string-match-p "is unavailable" json))
      ;; Dispatch: declared token + declared action + registered handler.
      (jetpacs-crud-action-pack-apply
       '((app . "toy") (view . "things")
         (token . "pack:toypack/toy.ping") (options . "mode=fast")
         (ref . "toy-1"))
       nil)
      (should (= (length calls) 1))
      (should (equal (alist-get 'mode (car calls)) "fast"))
      (should (equal (alist-get 'ref (car calls)) "toy-1"))
      ;; Bookkeeping keys never reach the pack handler.
      (should-not (assq 'token (car calls)))
      (should-not (assq 'app (car calls))))))

(ert-deftest jetpacs-crud-pack-key-value-filter-binds-named-params ()
  "A key=value :FILTER: binds declared params by name."
  (jetpacs-crud-tests--with-toypack
    (let ((view (jetpacs-crud--view (jetpacs-crud--app "toy") "things")))
      (should (equal (jetpacs-crud--pack-params
                      (plist-put (copy-sequence view) :filter "query=exact")
                      "toypack.things")
                     '((query . "exact"))))
      ;; A non-declared key falls back to the raw-query rule.
      (should (equal (jetpacs-crud--pack-params
                      (plist-put (copy-sequence view) :filter "nope=x")
                      "toypack.things")
                     '((query . "nope=x")))))))

(ert-deftest jetpacs-crud-pack-missing-feature-fails-closed ()
  "An unloadable feature degrades the view and dispatches nothing."
  (jetpacs-crud-tests--with-toypack
    ;; Re-point the pack at a feature that can never load.
    (puthash "toypack"
             (list :feature 'jetpacs-crud-tests-no-such-feature
                   :version "1.0"
                   :sources '("toypack.things") :actions '("toy.ping"))
             jetpacs-crud--packs)
    (let* ((built (jetpacs-crud--build-view "toy" "things" nil))
           (json (json-serialize built
                                 :null-object :null :false-object :false)))
      (should (null (jetpacs-lint-spec built)))
      (should (string-match-p "is unavailable" json))
      (should (string-match-p "not available" json)))
    (should-error (jetpacs-crud-action-pack-apply
                   '((app . "toy") (view . "things")
                     (token . "pack:toypack/toy.ping") (ref . "toy-1"))
                   nil)
                  :type 'user-error)
    (should (null calls))))

(ert-deftest jetpacs-crud-pack-action-vocabulary-is-closed ()
  "Dispatch refuses tokens the view doesn't declare and actions the
manifest doesn't declare — provably nothing runs."
  (jetpacs-crud-tests--with-toypack
    ;; A token the registered view never declared (wire-invented).
    (should-error (jetpacs-crud-action-pack-apply
                   '((app . "toy") (view . "things")
                     (token . "pack:toypack/toy.evil"))
                   nil)
                  :type 'user-error)
    ;; An action outside the manifest's declared list: re-register the
    ;; app with a view declaring it, then dispatch.
    (jetpacs-crud-install
     "toy" (replace-regexp-in-string
            "toy\\.ping(mode=fast)" "toy.undeclared"
            jetpacs-crud-tests--toypack-doc))
    (should-error (jetpacs-crud-action-pack-apply
                   '((app . "toy") (view . "things")
                     (token . "pack:toypack/toy.undeclared"))
                   nil)
                  :type 'user-error)
    (should (null calls))))

(ert-deftest jetpacs-crud-pack-version-gate-fails-closed ()
  "A document demanding a newer pack than installed degrades."
  (jetpacs-crud-tests--with-toypack
    (jetpacs-crud-install
     "toy" (replace-regexp-in-string
            "toypack 1\\.0" "toypack 2.0" jetpacs-crud-tests--toypack-doc))
    (let ((json (json-serialize
                 (jetpacs-crud--build-view "toy" "things" nil)
                 :null-object :null :false-object :false)))
      (should (string-match-p "is unavailable" json))
      (should (string-match-p "needs pack" json)))
    (should-error (jetpacs-crud-action-pack-apply
                   '((app . "toy") (view . "things")
                     (token . "pack:toypack/toy.ping") (options . "mode=fast"))
                   nil)
                  :type 'user-error)
    (should (null calls))))

(ert-deftest jetpacs-crud-pack-duplicate-id-fails-closed ()
  "Two different manifests claiming one pack id serve nothing."
  (jetpacs-crud-tests--with-toypack
    (jetpacs-crud-pack-register "toypack"
                                :feature 'other-feature :version "9.9"
                                :sources '("other.things") :actions nil)
    (let ((json (json-serialize
                 (jetpacs-crud--build-view "toy" "things" nil)
                 :null-object :null :false-object :false)))
      (should (string-match-p "is unavailable" json))
      (should (string-match-p "claimed by two" json)))
    (should-error (jetpacs-crud-action-pack-apply
                   '((app . "toy") (view . "things")
                     (token . "pack:toypack/toy.ping") (options . "mode=fast"))
                   nil)
                  :type 'user-error)
    (should (null calls))))

(ert-deftest jetpacs-crud-pack-register-is-idempotent-for-identical-facts ()
  "Re-registering the exact same manifest (a bundle reload) stays served."
  (jetpacs-crud-tests--with-toypack
    (jetpacs-crud-pack-register "toypack"
                                :feature 'jetpacs-crud-tests-toypack
                                :version "1.0"
                                :sources '("toypack.things")
                                :actions '("toy.ping"))
    (should-not (string-match-p
                 "is unavailable"
                 (json-serialize (jetpacs-crud--build-view "toy" "things" nil)
                                 :null-object :null :false-object :false)))))

(ert-deftest jetpacs-crud-parse-depends ()
  "A valid `#+JETPACS_DEPENDS:' parses to the :depends package list."
  (let ((file (make-temp-file
               "crud-depends" nil ".org"
               "#+JETPACS_APP: contacts\n#+JETPACS_DEPENDS: vulpea org-ql\n\
* People\n:PROPERTIES:\n:KIND: records\n:SCHEMA: %ITEM\n:END:\n")))
    (unwind-protect
        (should (equal (plist-get (jetpacs-crud-parse-app file) :depends)
                       '("vulpea" "org-ql")))
      (delete-file file))))

(ert-deftest jetpacs-crud-parse-depends-rejects-bad-name ()
  "A JETPACS_DEPENDS name outside [a-z][a-z0-9-]* is a format error."
  (let ((file (make-temp-file
               "crud-depends-bad" nil ".org"
               "#+JETPACS_APP: contacts\n#+JETPACS_DEPENDS: vulpea Org-QL\n\
* People\n:PROPERTIES:\n:KIND: records\n:SCHEMA: %ITEM\n:END:\n")))
    (unwind-protect
        (should-error (jetpacs-crud-parse-app file) :type 'user-error)
      (delete-file file))))

(ert-deftest jetpacs-crud-parse-date-reminder-rule ()
  (let ((file (make-temp-file
               "crud-reminder" nil ".org"
               "#+JETPACS_APP: reminders\n#+JETPACS_APP_FORMAT: 2\n* Tasks\n:PROPERTIES:\n:KIND: records\n:SCHEMA: %ITEM %ID %DEADLINE\n:ON: date-field\n:REL: -3d\n:DATEFIELD: DEADLINE\n:END:\n")))
    (unwind-protect
        (let* ((spec (jetpacs-crud-parse-app file))
               (view (car (plist-get spec :views))))
          (should (equal (plist-get view :reminder)
                         '(:date-field "DEADLINE" :relative-days -3))))
      (delete-file file))))

(ert-deftest jetpacs-crud-derives-stable-date-reminder ()
  (let ((spec '(:id "tasks"))
        (view '(:name "all" :title "Tasks" :kind records
                :reminder (:date-field "DEADLINE" :relative-days -3))))
    (cl-letf (((symbol-function 'jetpacs-crud--reminder-records)
               (lambda (&rest _)
                 '((:fields (("ITEM" . "Ship") ("ID" . "task-1")
                             ("DEADLINE" . "<2099-01-10>")))))))
      (let ((reminder (car (jetpacs-crud--view-reminders spec view))))
        (should (equal (alist-get 'id reminder)
                       "crud:tasks:all:task-1:DEADLINE:-3"))
        (should (equal (alist-get 'title reminder) "Ship"))
        (should (= (alist-get 'at_ms reminder)
                   (jetpacs-crud--reminder-time-ms "<2099-01-10>" -3)))))))

(ert-deftest jetpacs-crud-quick-capture-is-append-only-and-spec-scoped ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((dir (make-temp-file "crud-capture" t))
           (app (expand-file-name "app.org" dir))
           (inbox (expand-file-name "inbox.org" dir))
           (decoy (expand-file-name "decoy.org" dir)))
      (unwind-protect
          (progn
            (with-temp-file app
              (insert "#+JETPACS_APP: capture\n#+JETPACS_APP_FORMAT: 2\n#+JETPACS_INBOX: inbox.org\n* Notes\n:PROPERTIES:\n:KIND: records\n:SCHEMA: %ITEM\n:END:\n"))
            (jetpacs-crud-register-file app)
            (cl-letf (((symbol-function 'read-string)
                       (lambda (&rest _) "Remember milk")))
              (jetpacs-crud-action-capture-add
               `((app . "capture") (file . ,decoy)) nil))
            (let ((content (jetpacs-crud-tests--slurp inbox)))
              (should (string-match-p "^\\* Remember milk" content))
              (should (string-match-p ":ID:" content))
              (should (string-match-p ":CREATED:" content)))
            (should-not (file-exists-p decoy)))
        (delete-directory dir t)))))

(ert-deftest jetpacs-crud-dashboard-aggregates-count-sum-and-average ()
  (let* ((records '((:fields (("Region" . "West") ("Amount" . "10")))
                    (:fields (("Region" . "West") ("Amount" . "20")))
                    (:fields (("Region" . "East") ("Amount" . "5")))))
         (groups (jetpacs-crud--dashboard-groups records "Region")))
    (should (equal (mapcar #'car groups) '("West" "East")))
    (should (= (jetpacs-crud--dashboard-value '(count) (cdar groups)) 2))
    (should (= (jetpacs-crud--dashboard-value '(sum "Amount") (cdar groups)) 30))
    (should (= (jetpacs-crud--dashboard-value '(avg "Amount") (cdar groups)) 15.0))))

(ert-deftest jetpacs-crud-gantt-sorts-by-end-then-start-with-undated-last ()
  (let* ((records '((:fields (("ITEM" . "Undated")))
                    (:fields (("ITEM" . "Later")
                              ("DEADLINE" . "<2027-02-10>")))
                    (:fields (("ITEM" . "Sooner")
                              ("SCHEDULED" . "<2027-01-01>")))))
         (sorted (sort records
                       (lambda (a b)
                         (string< (jetpacs-crud--gantt-date-key a)
                                  (jetpacs-crud--gantt-date-key b))))))
    (should (equal (mapcar (lambda (r)
                             (alist-get "ITEM" (plist-get r :fields)
                                        nil nil #'equal))
                           sorted)
                   '("Sooner" "Later" "Undated")))))

(ert-deftest jetpacs-crud-export-csv-neutralizes-formulas-and-quotes ()
  (should
   (equal (jetpacs-crud--matrix-csv
           '(("Name" "Value") ("Ada, Inc." " =2+2") ("Quote" "a\"b")))
          "Name,Value\n\"Ada, Inc.\",' =2+2\nQuote,\"a\"\"b\"\n")))

(ert-deftest jetpacs-crud-export-org-table-adds-header-rule ()
  (should
   (equal (jetpacs-crud--matrix-org-table '(("Name" "Value") ("A|B" "2")))
          "| Name | Value |\n|---+---|\n| A\\vert{}B | 2 |\n")))

(ert-deftest jetpacs-crud-parse-csv-handles-quotes-and-newlines ()
  (should
   (equal (jetpacs-crud--parse-csv
           "Name,Note\r\n\"Ada, Inc.\",\"line 1\nline 2\"\r\n")
          '(("Name" "Note") ("Ada, Inc." "line 1\nline 2")))))

(ert-deftest jetpacs-crud-import-csv-appends-only-after-full-validation ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (cl-letf (((symbol-function 'read-string)
                 (lambda (&rest _)
                   "Item,Qty,Expires,Stock,Restock\nBeans,4,2027-02-03,High,true\n")))
        (jetpacs-crud-action-view-import-csv
         '((app . "pantry") (view . "inventory")) nil))
      (should (string-match-p
               "| *Beans *| *4 *| *2027-02-03 *| *High *| *\\[X\\] *|"
               (jetpacs-crud-tests--slurp file)))
      (let ((before (jetpacs-crud-tests--slurp file)))
        (cl-letf (((symbol-function 'read-string)
                   (lambda (&rest _)
                     "Item,Qty,Expires,Stock,Restock\nGood,2,2027-01-01,Low,false\nBad,nope,2027-01-01,Low,false\n")))
          (should-error
           (jetpacs-crud-action-view-import-csv
            '((app . "pantry") (view . "inventory")) nil)
           :type 'user-error))
        (should (equal before (jetpacs-crud-tests--slurp file)))))))

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
;; hello-world.org is the one document that exercises every FORMAT-2
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
      ;; Eleven views, but eight shell views: the four :GROUP: Tasks members
      ;; collapse into one tabulated destination; the rest (two of them
      ;; :NAV: drawer) each register their own.
      (should (= (length jetpacs-shell-views) 8))
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

(ert-deftest jetpacs-crud-install-first-time-preserves-document ()
  "The first install writes the document, adopting ids for the index.
It skips the merge dance (no data loss) but does normalize the source with
`:ID:'s so the vulpea extractor can index the table — structure and data
are preserved verbatim around them."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-tests--with-apps-dir dir
      (let ((file (expand-file-name "shop.org" dir))
            (text (concat "#+JETPACS_APP: shop\n#+TITLE: Shop\n\n"
                          "* Items\n:PROPERTIES:\n:COLTYPES: text\n:END:\n\n"
                          "| Item |\n|------|\n| Milk |\n")))
        (jetpacs-crud-install "shop" text)
        (let ((written (jetpacs-crud-orgapp--slurp file)))
          (should (string-match-p "#\\+JETPACS_APP: shop" written))
          (should (string-match-p "^\\* Items" written))
          (should (string-match-p ":COLTYPES: text" written))
          (should (string-match-p "| Milk |" written))    ; table data intact
          (when (jetpacs-crud--vulpea-p)                   ; ids adopted for the index
            (should (string-match-p ":ID:" written))))))))

(ert-deftest jetpacs-crud-install-preserves-pack-doc-bytes ()
  "A document whose only view reads a pack: source survives install and
redeploy byte-faithfully — the runtime preserves vocabulary it cannot
serve instead of touching it."
  (jetpacs-crud-tests--with-clean-state
    (jetpacs-crud-tests--with-apps-dir dir
      (let ((text (jetpacs-crud-tests--slurp
                   (expand-file-name "parser-parity-pack.org"
                                     jetpacs-crud-tests--fixtures)))
            (file (expand-file-name "packdemo.org" dir)))
        (jetpacs-crud-install "packdemo" text)
        (should (equal (jetpacs-crud-orgapp--slurp file) text))
        ;; Redeploy the identical document: the merge path must also be
        ;; byte-faithful (nothing adopted, nothing rewritten).
        (jetpacs-crud-install "packdemo" text)
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
      (let ((args (jetpacs-crud-tests--cell-args "pantry" "inventory" 1 2)))
        (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "5")))
          (jetpacs-crud-action-cell-edit
           args nil)))
      (should (string-match-p "| *Rice *| *5 *|"
                              (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-cell-edit-number-rejects-garbage ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((args (jetpacs-crud-tests--cell-args "pantry" "inventory" 1 2)))
        (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "many")))
          (should-error
           (jetpacs-crud-action-cell-edit
            args nil)
           :type 'user-error))
        ;; Unchanged on rejection.
        (should (string-match-p "| *Rice *| *2 *|"
                                (jetpacs-crud-tests--slurp file)))))))

(ert-deftest jetpacs-crud-cell-edit-date-rejects-garbage ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((args (jetpacs-crud-tests--cell-args "pantry" "inventory" 1 3)))
        (cl-letf (((symbol-function 'read-string)
                   (lambda (&rest _) "next tuesday")))
          (should-error
           (jetpacs-crud-action-cell-edit
            args nil)
           :type 'user-error))))))

(ert-deftest jetpacs-crud-cell-edit-enum ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((args (jetpacs-crud-tests--cell-args "pantry" "inventory" 1 4)))
        (cl-letf (((symbol-function 'completing-read)
                   (lambda (&rest _) "High")))
          (jetpacs-crud-action-cell-edit
           args nil)))
      (should (string-match-p "| *Rice *| *2 *| *2026-09-01 *| *High *|"
                              (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-cell-toggle-checkbox ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((args (jetpacs-crud-tests--cell-args "pantry" "inventory" 1 5)))
        (jetpacs-crud-action-cell-toggle
         args nil))
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
      (let ((args (jetpacs-crud-tests--cell-args "pantry" "inventory" 2 1)))
        (cl-letf (((symbol-function 'completing-read)
                   (lambda (&rest _) "Delete row")))
          (jetpacs-crud-action-row-menu
           args nil)))
      (should-not (string-match-p "Milk" (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-checkbox-toggle ()
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((args (jetpacs-crud-tests--item-args "pantry" "shopping"
                                               "Olive oil")))
        (jetpacs-crud-action-checkbox-toggle
         args nil))
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

(defun jetpacs-crud-tests--edit-under (file fn)
  "Simulate an external edit landing between a render and a tap:
run FN in FILE's visiting buffer and save.  Positions any earlier
render carried are stale afterwards; the index has NOT been refreshed."
  (with-current-buffer (find-file-noselect file)
    (org-with-wide-buffer (funcall fn))
    (let ((save-silently t)) (save-buffer))))

(ert-deftest jetpacs-crud-cell-edit-relocates-after-external-edit ()
  "A stale tap edits the same LOGICAL cell after the table moved.
The render-time args (their pos hint now pointing at prose) must land
on Rice's Qty via live re-location, not on whatever sits at the old
offset."
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((args (jetpacs-crud-tests--cell-args "pantry" "inventory" 1 2)))
        ;; The external edit: prose above the table shifts every offset.
        (jetpacs-crud-tests--edit-under file
          (lambda ()
            (goto-char (point-min))
            (search-forward "* Inventory")
            (forward-line 1)
            (insert "Some prose the phone has never seen.\n\n")))
        (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "7")))
          (jetpacs-crud-action-cell-edit args nil)))
      (should (string-match-p "| *Rice *| *7 *|"
                              (jetpacs-crud-tests--slurp file))))))

(ert-deftest jetpacs-crud-cell-edit-vanished-row-errors-cleanly ()
  "A tap addressing a row that no longer exists is a clean user-error.
The file must be untouched — never a write to the wrong place."
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((before (jetpacs-crud-tests--slurp file)))
        (should-error
         (jetpacs-crud-action-cell-edit
          '((app . "pantry") (view . "inventory") (line . 99) (col . 2)) nil)
         :type 'user-error)
        (should (equal before (jetpacs-crud-tests--slurp file)))))))

(ert-deftest jetpacs-crud-checkbox-toggle-relocates-after-external-edit ()
  "A stale toggle finds its item by text after the list gained a row.
An item inserted above shifts idx and pos; the carried text singles
out the intended item — the new neighbour must stay untouched."
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((args (jetpacs-crud-tests--item-args "pantry" "shopping"
                                                 "Olive oil")))
        (jetpacs-crud-tests--edit-under file
          (lambda ()
            (goto-char (point-min))
            (search-forward "- [ ] Olive oil")
            (beginning-of-line)
            (insert "- [ ] Salt\n")))
        (jetpacs-crud-action-checkbox-toggle args nil))
      (let ((text (jetpacs-crud-tests--slurp file)))
        (should (string-match-p "- \\[X\\] Olive oil" text))
        (should (string-match-p "- \\[ \\] Salt" text))))))

(ert-deftest jetpacs-crud-checkbox-toggle-moved-item-errors-cleanly ()
  "A toggle whose item vanished (renamed underneath) is a clean error.
Neither the renamed line nor any other item may flip."
  (jetpacs-crud-tests--with-clean-state
    (let ((file (jetpacs-crud-tests--stage "pantry.org")))
      (jetpacs-crud-register-file file)
      (let ((args (jetpacs-crud-tests--item-args "pantry" "shopping"
                                                 "Olive oil")))
        (jetpacs-crud-tests--edit-under file
          (lambda ()
            (goto-char (point-min))
            (while (search-forward "Olive oil" nil t)
              (replace-match "Olive oil (extra virgin)" t t))))
        (should-error (jetpacs-crud-action-checkbox-toggle args nil)
                      :type 'user-error))
      (let ((text (jetpacs-crud-tests--slurp file)))
        (should (string-match-p "- \\[ \\] Olive oil (extra virgin)" text))
        (should (string-match-p "- \\[X\\] Coffee" text))))))

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
  "Buffer position of the record heading whose ITEM is TITLE in ID's VIEW-NAME.
Records address by `:ID:' now, so this reads the live position from the
source file directly (exercising the resolver's `pos' fallback path)."
  (let* ((spec (jetpacs-crud--app id))
         (view (jetpacs-crud--view spec view-name))
         (file (car (jetpacs-crud--view-source spec view))))
    (jetpacs-crud--with-source file
      ;; POS-ONLY t -> returns a bare position, not a marker.
      (org-find-exact-headline-in-buffer title nil t))))

(defun jetpacs-crud-tests--record-id (id view-name title)
  "The stable `:ID:' of the record whose ITEM is TITLE in ID's VIEW-NAME."
  (let* ((spec (jetpacs-crud--app id))
         (view (jetpacs-crud--view spec view-name)))
    (plist-get
     (cl-find title (jetpacs-crud--scan-records spec view)
              :key (lambda (r)
                     (alist-get "ITEM" (plist-get r :fields) nil nil #'equal))
              :test #'equal)
     :id)))

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

(ert-deftest jetpacs-crud-filter-org-ql-extends ()
  "A FILTER term past the built-in subset filters via org-ql when installed —
in both whole-file and subtree (`::*Heading') scopes."
  (skip-unless (require 'org-ql nil t))
  (jetpacs-crud-tests--with-clean-state
    (let* ((dir (make-temp-file "jetpacs-orgql" t))
           (app (expand-file-name "flagged.org" dir))
           (backing (expand-file-name "people.org" dir)))
      (push dir jetpacs-crud-tests--temp-dirs)
      (with-temp-file backing
        (insert "* Alice :work:urgent:\n* Bob :work:\n* Carol :urgent:\n"
                "* Group\n** Dana :work:urgent:\n** Evan :work:\n"))
      (with-temp-file app
        ;; tags-all is an org-ql predicate the built-in interpreter lacks.
        (insert "#+JETPACS_APP: flagged\n\n"
                "* Flagged\n:PROPERTIES:\n:KIND: records\n"
                ":SOURCE: people.org\n:SCHEMA: %ITEM(Name)\n"
                ":FILTER: (tags-all \"work\" \"urgent\")\n:END:\n"
                "* Grouped\n:PROPERTIES:\n:KIND: records\n"
                ":SOURCE: people.org::*Group\n:SCHEMA: %ITEM(Name)\n"
                ":FILTER: (tags-all \"work\" \"urgent\")\n:END:\n"))
      (jetpacs-crud-register-file app)
      (let* ((spec (jetpacs-crud--app "flagged"))
             (names (lambda (view-name)
                      (mapcar (lambda (r)
                                (alist-get "ITEM" (plist-get r :fields)
                                           nil nil #'equal))
                              (jetpacs-crud--scan-records
                               spec (jetpacs-crud--view spec view-name))))))
        ;; Whole-file scope: only Alice carries both tags at the top level
        ;; (Dana matches the query but is a child of Group, not a record).
        (should (equal (funcall names "flagged") '("Alice")))
        ;; Subtree scope: within *Group, only Dana matches — proving the
        ;; org-ql match set lines up with the narrowed subtree walk.
        (should (equal (funcall names "grouped") '("Dana")))))))

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

(ert-deftest jetpacs-crud-field-edit-by-id-survives-offset-shift ()
  "A record edit addressed by :ID: resolves live, robust to a shifted file.
Prepending content moves every record's offset; a stale `pos' would land
in the wrong place, but id resolution finds the record in the current
file.  This is the id-addressing hardening."
  (skip-unless (require 'vulpea nil t))
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (data (expand-file-name "people.org" (file-name-directory file))))
      (jetpacs-crud-register-file file)
      (let ((id (jetpacs-crud-tests--record-id "crm" "people" "Ada Lovelace")))
        (should (stringp id))
        ;; Externally prepend a heading, shifting every record's offset down.
        (jetpacs-crud-orgapp--write
         data (concat "* Injected decoy\nlots of text that moves offsets\n\n"
                      (jetpacs-crud-orgapp--slurp data)))
        ;; Edit by id only (no pos): resolution must relocate Ada in the new file.
        (cl-letf (((symbol-function 'read-string) (lambda (&rest _) "555-9999")))
          (jetpacs-crud-action-field-edit
           `((app . "crm") (view . "people") (id . ,id) (prop . "Phone")) nil))
        (let ((content (jetpacs-crud-tests--slurp data)))
          (should (string-match-p "Injected decoy" content))     ; the shift stuck
          ;; Ada — the id target — got the new phone; her old one is gone.
          (should (string-match-p ":Phone: *555-9999" content))
          (should-not (string-match-p "555-0100" content))
          (should (string-match-p "Notes about Ada that must survive" content))
          ;; Grace (a sibling record) is untouched — the edit hit exactly one.
          (should (string-match-p ":Phone: *555-0199" content)))))))

(ert-deftest jetpacs-crud-record-detail-includes-fields-and-entry-prose ()
  (jetpacs-crud-tests--with-clean-state
    (let* ((file (jetpacs-crud-tests--stage "crm.org" "people.org"))
           (dialog nil)
           (style nil))
      (jetpacs-crud-register-file file)
      (let ((id (jetpacs-crud-tests--record-id "crm" "people" "Ada Lovelace")))
        (cl-letf (((symbol-function 'jetpacs-send-dialog)
                   (lambda (node &optional dialog-style)
                     (setq dialog node style dialog-style))))
          (jetpacs-crud-action-record-detail
           `((app . "crm") (view . "people") (id . ,id)) nil)))
      (let ((json (json-serialize (jetpacs-render-to-json dialog))))
        (should (equal style "sheet_full"))
        (should (string-match-p "555-0100" json))
        (should (string-match-p "Notes about Ada that must survive" json))
        (should (string-match-p "Duplicate record" json))))))

(ert-deftest jetpacs-crud-record-detail-is-dedicated-typed-composition ()
  (let* ((spec '(:id "typed"))
         (view '(:name "items" :title "Items" :actions "archive"
                 :schema (("ITEM" . "Name") ("State" . "State")
                          ("Done" . "Complete") ("When" . "When")
                          ("Count" . "Count"))
                 :coltypes (text (enum "New" "Done") checkbox date number)))
         (record '(:pos 19
                   :fields (("ITEM" . "Alpha") ("State" . "New")
                            ("Done" . "[X]") ("When" . "<2026-07-12 Sun>")
                            ("Count" . "42"))))
         (node (jetpacs-crud--record-detail-node
                spec view record "*Body* with [[https://example.com][link]]."))
         (json (json-serialize (jetpacs-render-to-json node)))
         (note-node (jetpacs-crud--record-detail-node
                     spec view (plist-put (copy-tree record) :id "note-1")
                     "Note body" t))
         (note-json (json-serialize (jetpacs-render-to-json note-node))))
    (should (null (jetpacs-lint-spec node)))
    (should (string-match-p "\\\"title\\\":\\\"Actions\\\"" json))
    (should (string-match-p "\\\"t\\\":\\\"date_stamp\\\"" json))
    (should (string-match-p "Checked" json))
    (should (string-match-p "\\\"syntax\\\":\\\"org\\\"" json))
    (should (string-match-p "crud.field.edit" json))
    (should (string-match-p "crud.action.apply" json))
    (should (string-match-p "Duplicate record" json))
    (should (null (jetpacs-lint-spec note-node)))
    (should (string-match-p "crud.note.field.edit" note-json))
    (should (string-match-p "Delete note" note-json))
    (should-not (string-match-p "Duplicate record" note-json))))

(ert-deftest jetpacs-crud-checkbox-editor-uses-closed-native-choice ()
  (let (offered)
    (cl-letf (((symbol-function 'completing-read)
               (lambda (_prompt collection &rest _)
                 (setq offered collection)
                 "[X]")))
      (should (equal (jetpacs-crud--prompt-value "Complete" 'checkbox "[ ]")
                     "[X]")))
    (should (equal offered '("[ ]" "[X]")))))

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

(ert-deftest jetpacs-crud-record-add-uses-declared-native-field-widgets ()
  "The CRUD abstraction should compile coltypes into Jetpacs' real DSL nodes."
  (let ((view '(:schema (("ITEM" . "Name") ("Amount" . "Amount")
                         ("Done" . "Done") ("Tier" . "Tier")
                         ("When" . "When"))
                :coltypes (text number checkbox (enum "Low" "High") date)))
        (args '((app . "typed") (view . "items"))))
    (let ((number (jetpacs-render-to-json
                   (jetpacs-crud--add-field-node view "Amount" "Amount" "12.5" nil args)))
          (toggle (jetpacs-render-to-json
                   (jetpacs-crud--add-field-node view "Done" "Done" t nil args)))
          (enum (jetpacs-render-to-json
                 (jetpacs-crud--add-field-node view "Tier" "Tier" "High" nil args)))
          (date (jetpacs-render-to-json
                 (jetpacs-crud--add-field-node view "When" "When" "2026-07-12" nil args))))
      (should (equal (alist-get 'keyboard number) "decimal"))
      (should (equal (alist-get 't toggle) "switch"))
      (should (eq (alist-get 'checked toggle) t))
      (should (equal (alist-get 't enum) "enum_list"))
      (should (equal (append (alist-get 'options enum) nil) '("Low" "High")))
      (should (equal (alist-get 't date) "date_button")))))

(ert-deftest jetpacs-crud-record-add-normalizes-widget-state-for-org ()
  (should (equal (jetpacs-crud--draft-value '(enum "A" "B") ["B"]) "B"))
  (should (equal (jetpacs-crud--draft-value '(enum "A" "B") []) ""))
  (should (equal (jetpacs-crud--draft-value 'checkbox t) "[X]"))
  (should (equal (jetpacs-crud--draft-value 'checkbox :false) "[ ]")))

(ert-deftest jetpacs-crud-record-card-uses-org-native-semantic-vocabulary ()
  (let* ((spec '(:id "work"))
         (view '(:name "tasks"
                 :schema (("ITEM" . "Task") ("TODO" . "State")
                          ("PRIORITY" . "Priority") ("SCHEDULED" . "Start")
                          ("DEADLINE" . "Due") ("TAGS" . "Tags")
                          ("Owner" . "Owner"))
                 :coltypes (text text text date date text text)))
         (record '(:pos 42 :done t
                   :fields (("ITEM" . "Ship release") ("TODO" . "DONE")
                            ("PRIORITY" . "A")
                            ("SCHEDULED" . "<2026-07-10 Fri>")
                            ("DEADLINE" . "<2026-07-12 Sun>")
                            ("TAGS" . ":work:release:")
                            ("Owner" . "Caleb"))))
         (node (jetpacs-crud--record-card spec view record))
         (json (json-serialize (jetpacs-render-to-json node))))
    (should (null (jetpacs-lint-spec node)))
    (should (string-match-p "rich_text" json))
    (should (string-match-p "date_stamp" json))
    (should (string-match-p "#work" json))
    (should (string-match-p "crud.view.search-set" json))
    (should (string-match-p "\\\"strike\\\":true" json))
    ;; Special org fields render once in the semantic header/metadata; ordinary
    ;; schema fields retain the generic editable row.
    (should (string-match-p "Caleb" json))))

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
  "The three FILTER input shapes normalize to org-ql sexps.
The parser now lives in jetpacs core (`jetpacs-org-parse-query');
this pins the contract every :FILTER: drawer relies on."
  ;; Empty / blank → no query (every record).
  (should (null (jetpacs-org-parse-query nil)))
  (should (null (jetpacs-org-parse-query "   ")))
  ;; A sexp is read and its bare argument symbols stringified.
  (should (equal (jetpacs-org-parse-query "(todo NEXT)") '(todo "NEXT")))
  (should (equal (jetpacs-org-parse-query "(property \"Tier\" \"Gold\")")
                 '(property "Tier" "Gold")))
  ;; Filter tokens AND together; comma splits any-of.
  (should (equal (jetpacs-org-parse-query "todo:NEXT tags:work,home")
                 '(and (todo "NEXT") (tags "work" "home"))))
  (should (equal (jetpacs-org-parse-query "priority:A") '(priority "A")))
  ;; Bare words become substring (regexp) matches.
  (should (equal (jetpacs-org-parse-query "foo bar")
                 '(and (regexp "foo") (regexp "bar"))))
  ;; A malformed sexp signals rather than matching nothing silently.
  (should-error (jetpacs-org-parse-query "(unbalanced")
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
  (jetpacs-org-entry-matches-p (jetpacs-org-parse-query filter)))

(ert-deftest jetpacs-crud-query-interpreter ()
  "The built-in interpreter covers the common org-ql subset.
The interpreter now lives in jetpacs core (`jetpacs-org-entry-matches-p');
this pins the FILTER subset the runtime documents in FORMAT.md."
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
            (jetpacs-org-entry-matches-p '(clocked)))
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

(ert-deftest jetpacs-crud-compile-filter-routes-terms ()
  "The FILTER compiler yields an index predicate or an org-ql handoff."
  ;; Empty filter → a predicate that admits everything.
  (pcase (jetpacs-crud--compile-filter nil)
    (`(:pred ,p) (should (functionp p)))
    (other (ert-fail (format "empty filter did not compile to :pred: %S" other))))
  ;; The whole index subset stays on the :pred arm.
  (dolist (tree '((todo "TODO") (done) (tags "work") (priority = "A")
                  (heading "Ada") (regexp "x") (property "Tier" "Gold")
                  (level 1) (scheduled) (deadline)
                  (and (todo "TODO") (not (tags "home")))))
    (should (eq (car (jetpacs-crud--compile-filter tree)) :pred)))
  ;; A term outside the subset is handed to org-ql verbatim.
  (should (equal (jetpacs-crud--compile-filter '(clocked))
                 '(:org-ql (clocked))))
  (should (eq (car (jetpacs-crud--compile-filter '(and (todo "TODO") (clocked))))
              :org-ql)))

(ert-deftest jetpacs-crud-note-index-matcher-covers-subset ()
  "The canonical note matcher evaluates the FILTER subset off a REAL struct.
The matcher itself lives in the core (`jetpacs-org-note-matches-p',
api 1.6.0); this drives it against genuine `make-vulpea-note' structs —
the accessor-contract check the core's mocked tests can't give."
  (skip-unless (require 'vulpea nil t))
  (let* ((org-done-keywords '("DONE"))
         (ada (make-vulpea-note
               :id "n1" :path "/x/ada.org" :level 1 :title "Ada Lovelace"
               :todo "NEXT" :priority ?A :tags '("work" "math")
               :properties '(("Tier" . "Gold"))
               :scheduled "<2027-01-01 Fri>"))
         (bob (make-vulpea-note
               :id "n2" :path "/x/bob.org" :level 1 :title "Bob"
               :todo "DONE" :tags '("home")
               :deadline "<2027-02-10 Wed>")))
    (cl-flet ((m (tree note) (jetpacs-org-note-matches-p tree note)))
      ;; todo / done
      (should (m '(todo "NEXT") ada))
      (should-not (m '(todo "NEXT") bob))
      (should (m '(todo) ada))          ; any not-done
      (should-not (m '(todo) bob))      ; DONE is not "todo"
      (should (m '(done) bob))
      (should-not (m '(done) ada))
      ;; tags (any-of)
      (should (m '(tags "work") ada))
      (should-not (m '(tags "home") ada))
      ;; priority: org urgency A > B, so A satisfies "> B"
      (should (m '(priority = "A") ada))
      (should (m '(priority > "B") ada))
      (should-not (m '(priority = "A") bob))   ; bob has none
      ;; heading / property / level
      (should (m '(heading "Lovelace") ada))
      (should (m '(property "Tier" "Gold") ada))
      (should-not (m '(property "Tier" "Gold") bob))
      (should (m '(level 1) ada))
      ;; planning presence
      (should (m '(scheduled) ada))
      (should-not (m '(scheduled) bob))
      (should (m '(deadline) bob))
      (should-not (m '(deadline) ada))
      ;; boolean composition
      (should (m '(and (todo "NEXT") (tags "work")) ada))
      (should (m '(or (done) (tags "work")) ada))
      (should (m '(not (done)) ada))
      ;; a term outside the subset signals, naming the limit
      (should-error (m '(clocked) ada) :type 'user-error))))

(ert-deftest jetpacs-crud-note-done-detection-survives-unset-keywords ()
  "`done'/`todo' work when `org-done-keywords' is nil (the headless path).
The index scan runs with no org buffer live, so the global keyword list
is unset; done-detection must still recognize a DONE note (fallback to
\"DONE\") and honor a CLOSED stamp.  Regression for a bug the notes
end-to-end drive caught that a keyword-bound unit test had masked."
  (skip-unless (require 'vulpea nil t))
  (let ((org-done-keywords nil)          ; reproduce the real scan condition
        (open (make-vulpea-note :id "o" :path "/x/o.org" :level 1
                                :title "Open" :todo "NEXT"))
        (done (make-vulpea-note :id "d" :path "/x/d.org" :level 1
                                :title "Done" :todo "DONE"))
        (closed (make-vulpea-note :id "c" :path "/x/c.org" :level 1
                                  :title "Closed" :todo "NEXT"
                                  :closed "2027-01-01 12:00:00")))
    (should (jetpacs-org-note-matches-p '(done) done))   ; via "DONE" fallback
    (should (jetpacs-org-note-matches-p '(done) closed)) ; via CLOSED stamp
    (should-not (jetpacs-org-note-matches-p '(done) open))
    (should (jetpacs-org-note-matches-p '(todo) open))     ; not done
    (should-not (jetpacs-org-note-matches-p '(todo) done)) ; DONE is not "todo"
    (should-not (jetpacs-org-note-matches-p '(todo) closed))))

(ert-deftest jetpacs-crud-vulpea-ensure-source-adopts-ids ()
  "ensure-source adds ids, indexes the records, is idempotent, and degrades.
Drives the register-time normalization over a real, isolated vulpea index
(skipped without vulpea)."
  (skip-unless (require 'vulpea nil t))
  (jetpacs-crud-tests--with-clean-state
    (let* ((jetpacs-crud--vulpea 'unknown)
           (root (make-temp-file "jetpacs-ensure" t))
           (vault (expand-file-name "vault/" root))
           (vulpea-directory vault)
           (vulpea-db-location (expand-file-name "vulpea.db" root))
           (vulpea-db--connection nil)
           (src (expand-file-name "tasks.org" vault))
           (spec (list :id "t" :file src))
           (view (list :name "backlog" :title "Backlog" :kind 'records
                       :schema '(("ITEM" . "Task"))
                       :source (list :file src :heading "Backlog"))))
      (push root jetpacs-crud-tests--temp-dirs)
      (make-directory vault t)
      (with-temp-file src
        (insert "#+TITLE: Tasks\n\n* Backlog\n** Alpha\n** Beta\n** Gamma\n"))
      (unwind-protect
          (progn
            (vulpea-db)
            (cl-flet ((records-in-index ()
                        (sort (mapcar #'vulpea-note-title
                                      (vulpea-db-query
                                       (lambda (n)
                                         (equal (vulpea-note-outline-path n)
                                                '("Backlog")))))
                              #'string<)))
              ;; First run adopts ids and the records land in the index.
              (should (jetpacs-crud-vulpea-ensure-source spec view))
              (should (equal (records-in-index) '("Alpha" "Beta" "Gamma")))
              ;; file-level + Backlog + 3 children = 5 ids.
              (should (= 5 (with-temp-buffer
                             (insert-file-contents src)
                             (how-many ":ID:" (point-min) (point-max)))))
              ;; Idempotent: nothing to change the second time.
              (should-not (jetpacs-crud-vulpea-ensure-source spec view))
              ;; Degrades: absent vulpea, it never touches the file.
              (let ((jetpacs-crud--vulpea nil))
                (should-not (jetpacs-crud-vulpea-ensure-source spec view)))))
        (when (and (boundp 'vulpea-db--connection) vulpea-db--connection)
          (vulpea-db-close))))))

(ert-deftest jetpacs-crud-vulpea-extractor-indexes-tables-and-checklists ()
  "The plugin extractor indexes org tables and checkboxes into readable rows.
Drives a real index over an isolated vulpea db (skipped without vulpea)."
  (skip-unless (require 'vulpea nil t))
  (jetpacs-crud-tests--with-clean-state
    (let* ((jetpacs-crud--vulpea 'unknown)
           (jetpacs-crud-vulpea--extractor-registered nil)
           (root (make-temp-file "jetpacs-ex" t))
           (vault (expand-file-name "vault/" root))
           (vulpea-directory vault)
           (vulpea-db-location (expand-file-name "vulpea.db" root))
           (vulpea-db--connection nil)
           (src (expand-file-name "app.org" vault)))
      (push root jetpacs-crud-tests--temp-dirs)
      (make-directory vault t)
      (with-temp-file src
        (insert ":PROPERTIES:\n:ID: aaaaaaaa-0000-0000-0000-000000000001\n:END:\n"
                "#+TITLE: Demo\n\n"
                "* Inventory\n"
                "| Item | Qty |\n|------+-----|\n| Rice | 2 |\n| Milk | 1 |\n\n"
                "* Shopping\n- [ ] eggs\n- [X] bread\n"))
      (unwind-protect
          (progn
            (vulpea-db)
            (should (jetpacs-crud--vulpea-p))      ; registers the extractor
            (vulpea-db-update-file src)
            ;; Tables: the reader returns the scan-plist shape, cells intact.
            (let* ((tables (jetpacs-crud-vulpea-tables src "Inventory"))
                   (tbl (car tables))
                   (rows (plist-get tbl :rows)))
              (should (= (length tables) 1))
              (should (= (plist-get tbl :ncols) 2))
              (should (equal (mapcar #'car (nth 0 rows)) '("Item" "Qty")))  ; header
              (should (eq (nth 1 rows) 'hline))
              (should (equal (mapcar #'car (nth 2 rows)) '("Rice" "2")))
              ;; The pos hint points at the cell text in the file.
              (let ((cell (car (nth 2 rows))))
                (with-temp-buffer
                  (insert-file-contents src)
                  (goto-char (cdr cell))
                  (should (looking-at-p "Rice")))))
            ;; Checklist: (STATE TEXT POS) tuples in document order.
            (let ((items (jetpacs-crud-vulpea-checklist src "Shopping")))
              (should (equal (mapcar (lambda (i) (list (nth 0 i) (nth 1 i))) items)
                             '((" " "eggs") ("X" "bread")))))
            ;; Degrades: readers yield nil when vulpea is absent.
            (let ((jetpacs-crud--vulpea nil))
              (should-not (jetpacs-crud-vulpea-tables src "Inventory"))
              (should-not (jetpacs-crud-vulpea-checklist src "Shopping"))))
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
