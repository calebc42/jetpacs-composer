#!/bin/sh
# One-time setup for the vulpea-backed test suites.
#
# Installs the ELPA packages vulpea needs (emacsql, s, dash) plus org-ql
# into the running user's package dir — $ELPA_DIR when set, else
# ~/.emacs.d/elpa, the same dir crud-tests.el activates.  vulpea itself is
# never installed from an archive here: point $VULPEA_DIR at a checkout:
#
#   sh elisp/test/install-test-deps.sh
#   VULPEA_DIR=~/pkb/resources/emacs/vulpea sh elisp/test/run-tests.sh
set -e
emacs -Q --batch --eval "(progn
  (require 'package)
  (when (getenv \"ELPA_DIR\")
    (setq package-user-dir (expand-file-name (getenv \"ELPA_DIR\"))))
  (add-to-list 'package-archives
               '(\"melpa\" . \"https://melpa.org/packages/\") t)
  (package-initialize)
  (package-refresh-contents)
  (dolist (pkg '(emacsql s dash org-ql))
    (unless (package-installed-p pkg)
      (package-install pkg)))
  (message \"test deps ready under %s\" package-user-dir))"
