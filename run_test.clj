(require '[clojure.test :refer [run-tests]])

;; Add src and test to classpath
(def class-loader (.getContextClassLoader (Thread/currentThread)))

;; Load test namespace
(load-file "test/nebsearch/btree_test.clj")

;; Run tests
(let [results (run-tests 'nebsearch.btree-test)]
  (println "\n========================================")
  (println "Test Results:")
  (println "  Ran:" (:test results) "tests")
  (println "  Passed:" (:pass results))
  (println "  Failed:" (:fail results))
  (println "  Errors:" (:error results))
  (println "========================================")
  (System/exit (if (and (zero? (:fail results))
                        (zero? (:error results)))
                 0
                 1)))
