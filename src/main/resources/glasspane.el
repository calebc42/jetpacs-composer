;;; glasspane.el --- Mock glasspane pack -*- lexical-binding: t; -*-
(require 'jetpacs-core)
(jetpacs-defsource "glasspane.org"
  :params '((:name "query" :type "text" :required nil))
  :fields '((:name "ITEM" :type "text")
            (:name "DEADLINE" :type "date")
            (:name "SCHEDULED" :type "date")
            (:name "TAGS" :type "string-list"))
  :query (lambda (p) (list)))
(provide 'glasspane)
;;; glasspane.el ends here
