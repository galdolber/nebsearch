(require '[clojure.test :as test])
(require 'nebsearch.core-test)
(require 'nebsearch.storage-test)
(require 'nebsearch.inverted-index-test)

(test/run-tests 'nebsearch.core-test 'nebsearch.storage-test 'nebsearch.inverted-index-test)
