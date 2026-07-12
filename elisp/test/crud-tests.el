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
  (dolist (fixture '("malformed-no-app.org" "malformed-coltype.org"
                     "malformed-dup.org"))
    (should-error (jetpacs-crud-parse-app
                   (expand-file-name fixture jetpacs-crud-tests--fixtures))
                  :type 'user-error)))

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

(provide 'crud-tests)
;;; crud-tests.el ends here
