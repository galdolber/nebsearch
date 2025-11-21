(ns run-tests
  (:require [clojure.test :as t]))

(println "\n=== Running Tests ===\n")

;; Load all test namespaces
(require 'nebsearch.core-test)
(require 'nebsearch.storage-test)
(require 'nebsearch.inverted-index-test)

;; Run tests
(let [results (t/run-tests 'nebsearch.core-test
                           'nebsearch.storage-test
                           'nebsearch.inverted-index-test)]
  (println "\n=== Test Summary ===")
  (println (format "Passed:  %d" (:pass results)))
  (println (format "Failed:  %d" (:fail results)))
  (println (format "Errors:  %d" (:error results)))

  (if (and (zero? (:fail results)) (zero? (:error results)))
    (do
      (println "\n✓ All tests passed!")
      (System/exit 0))
    (do
      (println "\n✗ Some tests failed!")
      (System/exit 1))))
