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

# Bundle smoke: build the pantry bundle from its fixture, then load it in
# a FRESH Emacs whose load-path has only the jetpacs core — the exact
# situation on the device.  The bundle must self-install and register.
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
emacs -Q --batch -l elisp/build-app-bundle.el -- elisp/test/fixtures/pantry.org "$TMP"
emacs -Q --batch -L jetpacs/emacs/core \
  --eval "(setq user-emacs-directory \"$TMP/emacs-home/\")" \
  --eval "(prefer-coding-system 'utf-8)" \
  -l "$TMP/jetpacs-app-pantry.el" \
  --eval "(progn
            (require 'jetpacs-lint)
            (unless (assoc \"pantry\" jetpacs-apps--registry) (kill-emacs 1))
            (unless (assoc \"pantry.inventory\" jetpacs-shell-views) (kill-emacs 1))
            (unless (assoc \"pantry.shopping\" jetpacs-shell-views) (kill-emacs 1))
            (dolist (entry jetpacs-shell-views)
              (when (jetpacs-lint-spec
                     (funcall (plist-get (cdr entry) :builder) nil))
                (kill-emacs 1)))
            (message \"bundle smoke OK\"))"
echo "All suites passed."
