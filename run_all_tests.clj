(require '[clojure.test :refer [run-tests]])

;; Load test namespaces
(load-file "test/nebsearch/btree_test.clj")
(load-file "test/nebsearch/core_test.clj")

;; Run all tests
(let [btree-results (run-tests 'nebsearch.btree-test)
      core-results (run-tests 'nebsearch.core-test)
      total-tests (+ (:test btree-results) (:test core-results))
      total-pass (+ (:pass btree-results) (:pass core-results))
      total-fail (+ (:fail btree-results) (:fail core-results))
      total-error (+ (:error btree-results) (:error core-results))]
  (println "\n========================================")
  (println "All Test Results:")
  (println "  Ran:" total-tests "tests")
  (println "  Passed:" total-pass)
  (println "  Failed:" total-fail)
  (println "  Errors:" total-error)
  (println "========================================")
  (System/exit (if (and (zero? total-fail)
                        (zero? total-error))
                 0
                 1)))
