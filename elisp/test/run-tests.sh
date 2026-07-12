#!/bin/sh
# Run the jetpacs-crud test suite in batch Emacs (28+).
# On this project's Windows host:  wsl -d Debian -- elisp/test/run-tests.sh
#
# The notes (vulpea) CRUD test skips unless vulpea is reachable.  To run it,
# install its deps once (emacsql dash s, into ~/.emacs.d/elpa) and point at a
# vulpea checkout:  VULPEA_DIR=~/pkb/resources/emacs/vulpea sh elisp/test/run-tests.sh
set -e
cd "$(dirname "$0")/../.." || exit 1

# The ERT suite.
emacs -Q --batch -l elisp/test/crud-tests.el -f ert-run-tests-batch-and-exit

# Bundle smoke: build a bundle from its fixture, then load it in a
# FRESH Emacs whose load-path has only the jetpacs core — the exact
# situation on the device.  The bundle must self-install, register the
# expected number of its own views (core adds shell views of its own,
# so count only the app's), and lint clean on every body.  pantry.org
# is the minimal example; hello-world.org is the kitchen sink (every
# kind, every column type, a scaffolded external source, and a
# vulpea-less notes placeholder).
#
# No EXIT trap here: dash enters an EXIT trap with $?=0 after a set -e
# abort, so a trap would mask smoke failures as success.  Failures are
# collected explicitly and the temp dir is removed on the way out.
TMP=$(mktemp -d)
smoke () { # smoke APP-ID EXPECTED-VIEW-COUNT
  emacs -Q --batch -l elisp/build-app-bundle.el -- "elisp/test/fixtures/$1.org" "$TMP" &&
  emacs -Q --batch -L jetpacs/emacs/core \
    --eval "(setq user-emacs-directory \"$TMP/emacs-home-$1/\")" \
    --eval "(prefer-coding-system 'utf-8)" \
    -l "$TMP/jetpacs-app-$1.el" \
    --eval "(progn
              (require 'jetpacs-lint)
              (unless (assoc \"$1\" jetpacs-apps--registry)
                (message \"smoke: %s did not register\" \"$1\") (kill-emacs 1))
              (unless (= (cl-count-if
                          (lambda (entry) (string-prefix-p \"$1.\" (car entry)))
                          jetpacs-shell-views)
                         $2)
                (message \"smoke: expected $2 views for %s\" \"$1\") (kill-emacs 1))
              (dolist (entry jetpacs-shell-views)
                (let ((problems (jetpacs-lint-spec
                                 (funcall (plist-get (cdr entry) :builder) nil))))
                  (when problems
                    (message \"smoke: %s lints dirty: %S\" (car entry) problems)
                    (kill-emacs 1))))
              (message \"bundle smoke OK: $1\"))"
}
rc=0
smoke pantry 2 || rc=1
smoke hello-world 6 || rc=1
rm -rf "$TMP"
[ "$rc" -eq 0 ] || { echo "bundle smoke FAILED"; exit 1; }
echo "All suites passed."
