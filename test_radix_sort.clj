(ns test-radix-sort
  (:require [nebsearch.btree :as btree]
            [nebsearch.entries :as entries])
  (:import [java.util Arrays]))

(println "\n=== Radix Sort vs TimSort Performance Comparison ===\n")

;; Access private radix-sort function for testing
(def radix-sort-long-keyed!
  (ns-resolve 'nebsearch.btree 'radix-sort-long-keyed!))

(defn test-sort-performance [n-entries]
  (println (format "Testing with %,d entries..." n-entries))

  ;; Generate random DocumentEntry objects with UNIQUE pos values (real-world scenario)
  (let [entries (vec (map (fn [i]
                            (entries/->DocumentEntry
                              i  ; Unique pos value
                              (str "doc-" (rand-int 1000000))
                              nil))
                         (shuffle (range n-entries))))

        ;; Test TimSort (Java Arrays.sort)
        arr1 (to-array entries)
        start1 (System/nanoTime)
        _ (Arrays/sort arr1)
        end1 (System/nanoTime)
        timsort-ms (/ (- end1 start1) 1000000.0)

        ;; Test Radix Sort
        arr2 (to-array entries)
        start2 (System/nanoTime)
        _ (radix-sort-long-keyed! arr2 #(.pos ^nebsearch.entries.DocumentEntry %))
        end2 (System/nanoTime)
        radix-ms (/ (- end2 start2) 1000000.0)

        speedup (/ timsort-ms radix-ms)]

    ;; Verify both sorts produce same result
    (let [sorted1 (vec arr1)
          sorted2 (vec arr2)
          correct? (= sorted1 sorted2)]
      (if correct?
        (println "  ✓ Correctness verified: Both sorts produce identical results")
        (println "  ✗ ERROR: Sorts produce different results!")))

    (println (format "  TimSort:    %.2f ms" timsort-ms))
    (println (format "  Radix Sort: %.2f ms" radix-ms))
    (println (format "  Speedup:    %.2fx faster\n" speedup))

    speedup))

;; Test with various sizes
(println "Small dataset (10K entries):")
(test-sort-performance 10000)

(println "Medium dataset (100K entries):")
(test-sort-performance 100000)

(println "Large dataset (1M entries):")
(def large-speedup (test-sort-performance 1000000))

(println "\n=== Summary ===")
(println (format "Radix sort is O(n) vs TimSort's O(n log n)"))
(println (format "For 1M entries: Radix sort is %.1fx faster!" large-speedup))
(println "Expected theoretical speedup: ~log2(1M) = ~20x")
(println "\nRadix sort performance scales linearly with data size,")
(println "while TimSort grows as O(n log n).")

(System/exit 0)
