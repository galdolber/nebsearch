(ns nebsearch.core-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [nebsearch.core :as f]))

(def sample-data (into {} (mapv vector (range) (edn/read-string (slurp "data.edn")))))

(deftest test-flex
  (let [flex (f/search-add (f/init) sample-data)
        flex (f/deserialize (f/serialize flex))

        _ (is (= ["30 Nights of Paranormal Activity with the Devil Inside the Girl with the Dragon Tattoo"
                  "The Girl with the Dragon Tattoo"]
                 (mapv sample-data (f/search flex "girl tatto"))))

        _ (is (= ["30 Nights of Paranormal Activity with the Devil Inside the Girl with the Dragon Tattoo"]
                 (mapv sample-data (f/search flex "30 girl tatto"))))

        _ (dotimes [_ 3] ;; test cache
            (is (= ["A Man of Iron"
                    "Iron Man"
                    "Iron Man 2"
                    "The Man with the Iron Fists"
                    "Iron Man 3"
                    "Man of Iron"
                    "The Man in the Iron Mask"]
                   (mapv sample-data (f/search flex "man iron")))))

        _ (is (= {#{"tatto" "girl"} #{118 20110},
                  #{"tatto" "30" "girl"} #{118},
                  #{"man" "iron"} #{475 9434 9432 21523 9433 11379 21465}}
                 (into {} (map (fn [[k v]] [k (:value v)]) @(:cache (meta flex))))))

        ;; update
        _ (is (= ["$ aka Dollars"] (mapv sample-data (f/search flex "aka Dollars"))))
        _ (is (= [] (mapv sample-data (f/search flex "aka Dollars edited"))))
        flex (f/search-add flex {0 "aka Dollars edited"})

        ;; cache after add
        _ (is (= {} @(:cache (meta flex))))

        _ (is (= ["$ aka Dollars"] (mapv sample-data (f/search flex "aka Dollars edited"))))

        ;; cache after add and search
        _ (is (= {#{"aka" "edited" "dollars"} #{0}}
                 (into {} (map (fn [[k v]] [k (:value v)]) @(:cache (meta flex))))))
        ;; delete
        flex (f/search-remove flex [0])

        ;; cache on remove
        _ (is (= {#{"aka" "edited" "dollars"} #{}}
                 (into {} (map (fn [[k v]] [k (:value v)]) @(:cache (meta flex))))))]

    (is (= [] (mapv sample-data (f/search flex "aka Dollars"))))

    (let [g-flex (f/search-gc flex)]
      (is (= 464954 (count (:index flex))))
      (is (= 464921 (count (:index g-flex)))))))

(deftest hashing-consistent-test
  (is
   (= (hash (f/search-add (f/init) sample-data))
      (hash (f/search-add (f/init) sample-data))
      (hash (f/search-add (f/init) sample-data)))))

;; Bug fix tests

(deftest test-consistent-return-values
  "Bug #3: search should always return a set, never nil"
  (let [flex (f/init)]
    ;; Empty search should return empty set
    (is (= #{} (f/search flex nil)))
    (is (= #{} (f/search flex "")))
    (is (= #{} (f/search flex "   ")))

    ;; Search on empty index should return empty set
    (is (= #{} (f/search flex "test")))

    ;; All return values should be sets
    (is (set? (f/search flex nil)))
    (is (set? (f/search flex "")))
    (is (set? (f/search flex "test")))))

(deftest test-deserialize-metadata
  "Bug #9: deserialize should preserve/restore cache metadata"
  (let [flex (f/search-add (f/init) {1 "test" 2 "hello"})
        serialized (f/serialize flex)
        deserialized (f/deserialize serialized)]

    ;; Metadata should exist
    (is (some? (meta deserialized)))
    (is (some? (:cache (meta deserialized))))
    (is (instance? clojure.lang.Atom (:cache (meta deserialized))))

    ;; Should be able to search after deserialize
    (is (= #{1} (f/search deserialized "test")))

    ;; Cache should work
    (is (= #{1} (f/search deserialized "test")))
    (is (= {#{"test"} #{1}} (into {} (map (fn [[k v]] [k (:value v)]) @(:cache (meta deserialized))))))))

(deftest test-find-len-bounds-checking
  "Bug #2: find-len should throw meaningful error when join-char not found"
  (let [flex (f/search-add (f/init) {1 "test"})]
    ;; Valid position should work
    (is (number? (f/find-len (:index flex) 0)))

    ;; Invalid position should throw with meaningful error
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid index position"
         (f/find-len (:index flex) 99999)))))

(deftest test-nil-pointer-safety
  "Bug #1: search should handle nil results from pss/rslice gracefully"
  (let [flex (f/search-add (f/init) {1 "alpha" 2 "beta" 3 "gamma"})]
    ;; These searches should not throw NPE even if some positions don't match
    (is (set? (f/search flex "alpha")))
    (is (set? (f/search flex "zzzz"))) ;; non-existent
    (is (set? (f/search flex "alpha beta")))))

(deftest test-input-validation
  "Bug #8: public functions should validate inputs"
  (let [flex (f/init)]
    ;; search-add should validate inputs
    (is (thrown? AssertionError (f/search-add nil {1 "test"})))
    (is (thrown? AssertionError (f/search-add "not-a-map" {1 "test"})))

    ;; search-remove should validate inputs
    (is (thrown? AssertionError (f/search-remove nil [1])))
    (is (thrown? AssertionError (f/search-remove "not-a-map" [1])))

    ;; search should validate inputs
    (is (thrown? AssertionError (f/search nil "test")))
    (is (thrown? AssertionError (f/search "not-a-map" "test")))))

(deftest test-edge-cases
  "Test various edge cases"
  (let [flex (f/init)]
    ;; Empty additions
    (is (map? (f/search-add flex [])))
    (is (map? (f/search-add flex {})))

    ;; Empty removals
    (is (map? (f/search-remove flex [])))
    (is (map? (f/search-remove flex nil)))

    ;; Special characters in search
    (let [flex2 (f/search-add flex {1 "hello@world.com"})]
      (is (= #{1} (f/search flex2 "hello")))
      (is (= #{1} (f/search flex2 "world")))
      (is (= #{1} (f/search flex2 "com"))))))

(deftest test-performance-optimizations
  "Bug #5 & #7: verify optimized min/max calculations work correctly"
  (let [data (into {} (map-indexed vector ["apple" "banana" "cherry" "date" "elderberry"]))
        flex (f/search-add (f/init) data)]

    ;; Multi-word searches should use optimized path
    (is (set? (f/search flex "apple banana")))
    (is (set? (f/search flex "cherry date elderberry")))

    ;; Results should be correct
    (is (= #{0} (f/search flex "apple")))
    (is (= #{1} (f/search flex "banana")))
    (is (= #{0 1 3} (f/search flex "a"))) ;; apple, banana, and date contain 'a'
    ))

(deftest test-cache-invalidation
  "Verify cache is properly invalidated on updates"
  (let [flex (f/search-add (f/init) {1 "test"})
        _ (f/search flex "test") ;; populate cache
        _ (is (= {#{"test"} #{1}} (into {} (map (fn [[k v]] [k (:value v)]) @(:cache (meta flex))))))

        ;; Add should reset cache
        flex2 (f/search-add flex {2 "hello"})
        _ (is (= {} @(:cache (meta flex2))))

        ;; Remove should update cache entries
        _ (f/search flex2 "test")
        _ (f/search flex2 "hello")
        flex3 (f/search-remove flex2 [1])]

    ;; Cache should be updated, not containing removed id
    (is (= #{} (f/search flex3 "test")))
    (is (= #{2} (f/search flex3 "hello")))))

(deftest test-search-gc-preserves-data
  "Verify search-gc compacts index but preserves searchability"
  (let [data {1 "apple" 2 "banana" 3 "cherry"}
        flex (f/search-add (f/init) data)
        _ (f/search flex "apple") ;; use the index

        ;; Remove some items
        flex2 (f/search-remove flex [2])

        ;; GC should compact
        flex3 (f/search-gc flex2)

        ;; Original size should be larger
        _ (is (< (count (:index flex3)) (count (:index flex2))))

        ;; But search should still work
        _ (is (= #{1} (f/search flex3 "apple")))
        _ (is (= #{3} (f/search flex3 "cherry")))
        _ (is (= #{} (f/search flex3 "banana")))]))  ;; removed

;; Performance and stress tests

(deftest test-large-dataset-performance
  "Test performance with larger dataset"
  (let [;; Create 1000 entries
        large-data (into {} (map (fn [i] [i (str "item-" i "-test")]) (range 1000)))
        start (System/currentTimeMillis)
        flex (f/search-add (f/init) large-data)
        add-time (- (System/currentTimeMillis) start)]

    ;; Should build index quickly (< 1 second for 1000 items)
    (is (< add-time 1000) "Adding 1000 items should take less than 1 second")

    ;; Search should work
    (is (= 1 (count (f/search flex "item-500-test"))))

    ;; Multi-word search
    (let [result (f/search flex "item test")]
      (is (= 1000 (count result)) "All items should match 'item test'"))

    ;; Partial search
    (let [result (f/search flex "item-5")]
      (is (>= (count result) 11) "Should find item-5, item-50, item-51...59, item-500...599"))))

(deftest test-sequential-operations-performance
  "Test many sequential add/remove operations"
  (let [flex (f/init)]
    ;; Add items one by one
    (let [flex2 (reduce (fn [f i]
                          (f/search-add f {i (str "entry-" i)}))
                        flex
                        (range 100))]
      (is (= 100 (count (:ids flex2))))

      ;; Remove half
      (let [flex3 (f/search-remove flex2 (range 0 50))]
        (is (= 50 (count (:ids flex3))))

        ;; Verify only second half remains
        (is (= #{50} (f/search flex3 "entry-50")))
        (is (= #{} (f/search flex3 "entry-25")))))))

(deftest test-cache-effectiveness
  "Test that cache improves repeated searches"
  (let [flex (f/search-add (f/init) (into {} (map-indexed vector (range 1000))))
        cache (:cache (meta flex))]

    ;; First search - cache miss
    (is (= {} @cache))
    (f/search flex "500")
    (is (= 1 (count @cache)))

    ;; Different search - another cache entry
    (f/search flex "600")
    (is (= 2 (count @cache)))

    ;; Multi-word search creates combined cache entry
    (f/search flex "500 600")
    (is (= 3 (count @cache)))

    ;; Verify cache contains expected keys
    (is (contains? @cache #{"500"}))
    (is (contains? @cache #{"600"}))
    (is (contains? @cache #{"500" "600"}))))

(deftest test-gc-effectiveness
  "Test that GC actually reduces index size"
  ;; Disable auto-GC for this test so we can test manual GC
  (binding [f/*auto-gc-threshold* 1.0] ;; 100% threshold = never auto-trigger
    (let [data (into {} (map (fn [i] [i (str "unique-" i "-" (apply str (repeat 50 \x)))]) (range 100)))
          flex (f/search-add (f/init) data)
          initial-size (count (:index flex))]

      ;; Remove 50% of items
      (let [flex2 (f/search-remove flex (range 0 50))
            fragmented-size (count (:index flex2))]

        ;; Size should be same (spaces instead of removed items)
        (is (= initial-size fragmented-size))

        ;; After GC, size should be smaller
        (let [flex3 (f/search-gc flex2)
              compacted-size (count (:index flex3))]

          (is (< compacted-size fragmented-size))
          (is (< compacted-size (* initial-size 0.6))) ;; Should be ~50% + overhead

          ;; Verify search still works
          (is (= #{50} (f/search flex3 "unique-50")))
          (is (= #{} (f/search flex3 "unique-25"))))))))

(deftest test-unicode-handling
  "Test handling of unicode characters"
  (let [unicode-data {1 "café"
                      2 "naïve"
                      3 "résumé"
                      4 "münchen"
                      5 "日本語"
                      6 "emoji😀test"}
        flex (f/search-add (f/init) unicode-data)]

    ;; Accented characters should be normalized
    (is (= #{1} (f/search flex "cafe")))
    (is (= #{2} (f/search flex "naive")))
    (is (= #{3} (f/search flex "resume")))
    (is (= #{4} (f/search flex "munchen")))

    ;; Non-latin scripts
    (is (set? (f/search flex "日本語")))
    (is (set? (f/search flex "emoji😀test")))))

(deftest test-empty-and-whitespace
  "Test various empty and whitespace scenarios"
  (let [flex (f/search-add (f/init) {1 "test" 2 "  spaced  " 3 "tab\ttab"})]

    ;; Empty searches
    (is (= #{} (f/search flex "")))
    (is (= #{} (f/search flex "   ")))
    (is (= #{} (f/search flex "\t\t")))
    (is (= #{} (f/search flex "\n")))

    ;; Whitespace in data should be normalized
    (is (= #{2} (f/search flex "spaced")))
    (is (= #{3} (f/search flex "tab")))))

(deftest test-very-long-strings
  "Test handling of very long strings"
  (let [long-string (apply str (repeat 10000 "a"))
        flex (f/search-add (f/init) {1 long-string 2 "short"})]

    ;; Should handle long strings
    (is (= #{1} (f/search flex "aaa")))
    (is (= #{2} (f/search flex "short")))

    ;; Search for long query
    (let [long-query (apply str (repeat 100 "a"))]
      (is (= #{1} (f/search flex long-query))))))

(deftest test-special-characters
  "Test handling of special characters"
  (let [flex (f/search-add (f/init)
                           {1 "hello@world.com"
                            2 "user+tag@email.com"
                            3 "path/to/file.txt"
                            4 "c++programming"
                            5 "version-2.0.1"
                            6 "100%complete"
                            7 "$$$money$$$"})]

    ;; Special chars should split words
    (is (= #{1} (f/search flex "hello")))
    (is (= #{1} (f/search flex "world")))
    ;; "com" appears in multiple entries
    (is (= #{1 2 6} (f/search flex "com")))

    ;; Plus is kept
    (is (not (empty? (f/search flex "c++"))))

    ;; Dots are kept
    (is (not (empty? (f/search flex "2.0.1"))))

    ;; Numbers work
    (is (= #{5} (f/search flex "2.0.1")))))

(deftest test-boundary-conditions
  "Test boundary conditions"
  (let [flex (f/init)]

    ;; Empty index
    (is (= "" (:index flex)))
    (is (= 0 (count (:ids flex))))
    (is (= 0 (count (:data flex))))

    ;; Single item
    (let [flex1 (f/search-add flex {1 "a"})]
      (is (= #{1} (f/search flex1 "a")))

      ;; Remove single item
      (let [flex2 (f/search-remove flex1 [1])]
        (is (= 0 (count (:ids flex2))))
        (is (= #{} (f/search flex2 "a")))))

    ;; Add same ID multiple times (should update)
    (let [flex3 (f/search-add flex {1 "first"})
          flex4 (f/search-add flex3 {1 "second"})]
      (is (= 1 (count (:ids flex4))))
      (is (= #{} (f/search flex4 "first")))
      (is (= #{1} (f/search flex4 "second"))))))

(deftest test-update-patterns
  "Test various update patterns"
  (let [flex (f/search-add (f/init) {1 "alpha" 2 "beta" 3 "gamma"})]

    ;; Bulk update - "alpha" substring will match "alpha2"
    (let [flex2 (f/search-add flex {1 "delta" 2 "epsilon"})]
      (is (= #{} (f/search flex2 "alpha")))
      (is (= #{1} (f/search flex2 "delta")))
      (is (= #{2} (f/search flex2 "epsilon")))
      (is (= #{3} (f/search flex2 "gamma"))))

    ;; Interleaved add/remove
    (let [flex3 (-> flex
                    (f/search-remove [2])
                    (f/search-add {4 "delta"})
                    (f/search-remove [3])
                    (f/search-add {5 "epsilon"}))]
      (is (= #{1 4 5} (set (keys (:ids flex3)))))
      (is (= #{1} (f/search flex3 "alpha")))
      (is (= #{4} (f/search flex3 "delta")))
      (is (= #{5} (f/search flex3 "epsilon"))))))

(deftest test-search-result-accuracy
  "Test search result accuracy with complex queries"
  (let [flex (f/search-add (f/init)
                           {1 "the quick brown fox"
                            2 "the lazy dog"
                            3 "quick brown dog"
                            4 "the fox and the dog"})]

    ;; Single word
    (is (= #{1 2 4} (f/search flex "the")))
    (is (= #{1 3} (f/search flex "quick")))

    ;; Two words (intersection)
    (is (= #{1} (f/search flex "quick fox")))
    (is (= #{2 4} (f/search flex "the dog")))
    (is (= #{3} (f/search flex "quick dog")))

    ;; Three words
    (is (= #{1} (f/search flex "the quick fox")))
    (is (= #{} (f/search flex "the quick lazy"))))) ;; no match

(deftest test-serialization-roundtrip
  "Test serialization doesn't lose data"
  (let [original (f/search-add (f/init)
                               (into {} (map-indexed vector (range 100))))
        serialized (f/serialize original)
        deserialized (f/deserialize serialized)]

    ;; Verify structure
    (is (= (count (:ids original)) (count (:ids deserialized))))
    (is (= (count (:data original)) (count (:data deserialized))))

    ;; Verify searches work identically
    (is (= (f/search original "50") (f/search deserialized "50")))
    (is (= (f/search original "25") (f/search deserialized "25")))

    ;; Verify can add after deserialize
    (let [after-add (f/search-add deserialized {100 "new"})]
      (is (= #{100} (f/search after-add "new"))))))

(deftest test-id-types
  "Test various ID types"
  (let [flex (f/search-add (f/init)
                           {1 "number-id"
                            "string-id" "string-key"
                            :keyword-id "keyword-key"
                            [1 2] "vector-id"})]

    ;; All ID types should work
    (is (= #{1} (f/search flex "number")))
    (is (= #{"string-id"} (f/search flex "string-key")))
    (is (= #{:keyword-id} (f/search flex "keyword")))
    (is (= #{[1 2]} (f/search flex "vector")))))

(deftest test-index-fragmentation
  "Test index fragmentation after many operations"
  ;; Disable auto-GC for this test
  (binding [f/*auto-gc-threshold* 1.0] ;; 100% threshold = never auto-trigger
    (let [flex (f/search-add (f/init) (into {} (map-indexed vector (range 100))))]

      ;; Remove every other item
      (let [flex2 (f/search-remove flex (range 0 100 2))
            fragmented-index (:index flex2)]

        ;; Index should contain spaces (fragmentation)
        (is (> (count fragmented-index) 0))

        ;; Count spaces (fragmentation)
        (let [space-count (count (filter #(= % \space) fragmented-index))]
          (is (> space-count 0) "Should have spaces from removed items"))

        ;; After GC, should have fewer/no spaces in content
        (let [flex3 (f/search-gc flex2)
              compacted-index (:index flex3)]

          ;; Should be more compact
          (is (< (count compacted-index) (count fragmented-index))))))))

(deftest test-empty-values
  "Test handling of empty and nil values"
  (let [flex (f/init)]

    ;; Empty string value
    (let [flex1 (f/search-add flex {1 ""})]
      ;; Empty values encode to nothing
      (is (= #{} (f/search flex1 ""))))

    ;; Nil value
    (let [flex2 (f/search-add flex {2 nil})]
      ;; Nil should be handled gracefully
      (is (map? flex2)))))

(deftest test-case-sensitivity
  "Verify searches are case-insensitive"
  (let [flex (f/search-add (f/init)
                           {1 "UPPERCASE"
                            2 "lowercase"
                            3 "MixedCase"})]

    ;; All these should match
    (is (= #{1} (f/search flex "uppercase")))
    (is (= #{1} (f/search flex "UPPERCASE")))
    (is (= #{2} (f/search flex "LOWERCASE")))
    (is (= #{2} (f/search flex "lowercase")))
    (is (= #{3} (f/search flex "mixedcase")))
    (is (= #{3} (f/search flex "MIXEDCASE")))))

(deftest test-word-boundaries
  "Test that searches respect word boundaries correctly"
  (let [flex (f/search-add (f/init)
                           {1 "cat"
                            2 "category"
                            3 "wildcat"
                            4 "scattered"})]

    ;; "cat" should match all items containing "cat" as substring
    (is (= #{1 2 3 4} (f/search flex "cat")))))

(deftest test-duplicate-words
  "Test handling of duplicate words in text"
  (let [flex (f/search-add (f/init)
                           {1 "hello hello world"
                            2 "hello world"
                            3 "world world"})]

    ;; Should match regardless of duplicates
    (is (= #{1 2} (f/search flex "hello")))
    (is (= #{1 2 3} (f/search flex "world")))
    (is (= #{1 2} (f/search flex "hello world")))))

(deftest test-performance-many-ids
  "Test performance characteristics with many IDs"
  (let [;; Create data with shared substrings
        data (into {} (for [i (range 500)]
                        [i (str "common-prefix-" i "-suffix")]))
        flex (f/search-add (f/init) data)]

    ;; Search for common term - should return many results
    (let [results (f/search flex "common")]
      (is (= 500 (count results))))

    ;; Search for specific term - should return one result
    (let [results (f/search flex "prefix-250")]
      (is (= #{250} results)))

    ;; Multi-word with common term should still work
    (let [results (f/search flex "common prefix-250")]
      (is (= #{250} results)))))

;; Optimization tests

(deftest test-lru-cache-eviction
  "Test that LRU cache evicts old entries when exceeding *cache-size*"
  (binding [f/*cache-size* 5] ;; Set small cache for testing
    (let [flex (f/search-add (f/init) (into {} (map-indexed vector (range 100))))
          cache (:cache (meta flex))]

      ;; Perform 10 different searches
      (dotimes [i 10]
        (f/search flex (str i)))

      ;; Cache should have at most 5 entries (evicted to 80% of *cache-size*)
      (is (<= (count @cache) 5))
      (is (>= (count @cache) 4)) ;; Should be ~4 (80% of 5)

      ;; Some entries should have been evicted (started with 10, now have <= 5)
      (is (< (count @cache) 10))

      ;; All cache entries should be valid
      (doseq [[k v] @cache]
        (is (set? k))
        (is (map? v))
        (is (contains? v :value))
        (is (contains? v :access-time))))))

(deftest test-lru-cache-access-time-update
  "Test that accessing cached entries updates their access time"
  (binding [f/*cache-size* 3]
    (let [flex (f/search-add (f/init) (into {} (map-indexed vector (range 100))))
          cache (:cache (meta flex))]

      ;; Search for 0, 1, 2 (fills cache)
      (f/search flex "0")
      (Thread/sleep 10)
      (f/search flex "1")
      (Thread/sleep 10)
      (f/search flex "2")

      ;; All three should be in cache
      (is (= 3 (count @cache)))

      ;; Access "0" again to update its access time
      (Thread/sleep 10)
      (f/search flex "0")

      ;; Now search for "3" (should evict "1", not "0" since we just accessed "0")
      (Thread/sleep 10)
      (f/search flex "3")

      ;; Cache should have evicted oldest to ~80% = 2-3 entries
      (is (<= (count @cache) 3))

      ;; "0" should still be there since we accessed it recently
      (is (contains? @cache #{(str 0)})))))

(deftest test-auto-gc-threshold
  "Test that GC automatically triggers when fragmentation exceeds threshold"
  (binding [f/*auto-gc-threshold* 0.5] ;; 50% fragmentation threshold
    (let [;; Create data with long strings to make fragmentation noticeable
          data (into {} (map (fn [i] [i (str "longword-" i "-" (apply str (repeat 20 \x)))]) (range 50)))
          flex (f/search-add (f/init) data)
          initial-size (count (:index flex))]

      ;; Remove 40% of items (should not trigger auto-GC yet, below 50% threshold)
      (let [flex2 (f/search-remove flex (range 0 20))
            size-after-20 (count (:index flex2))]
        ;; Size should be same (spaces instead of removed items, no auto-GC)
        (is (= initial-size size-after-20)))

      ;; Remove more items to exceed 50% fragmentation threshold
      (let [flex3 (f/search-remove flex (range 0 26))
            size-after-26 (count (:index flex3))]

        ;; Auto-GC should have triggered, so size should be smaller
        (is (< size-after-26 initial-size))

        ;; Verify search still works correctly
        (is (= #{40} (f/search flex3 "longword-40")))
        (is (= #{} (f/search flex3 "longword-10")))))))

(deftest test-auto-gc-disabled-when-low-fragmentation
  "Test that auto-GC doesn't trigger with low fragmentation"
  (binding [f/*auto-gc-threshold* 0.3]
    (let [data (into {} (map (fn [i] [i (str "word-" i)]) (range 50)))
          flex (f/search-add (f/init) data)
          initial-size (count (:index flex))]

      ;; Remove only 10% of items (well below 30% threshold)
      (let [flex2 (f/search-remove flex (range 0 5))
            size-after-remove (count (:index flex2))]

        ;; Auto-GC should NOT have triggered
        (is (= initial-size size-after-remove))

        ;; Index should still contain spaces (fragmentation present)
        (let [space-count (count (filter #(= % \space) (:index flex2)))]
          (is (> space-count 0)))))))

(deftest test-batch-optimization-large-batch
  "Test that large batch adds work correctly (StringBuilder optimization)"
  (binding [f/*batch-threshold* 100]
    (let [;; Create batch larger than threshold
          large-batch (into {} (map (fn [i] [i (str "item-" i)]) (range 150)))
          start (System/currentTimeMillis)
          flex (f/search-add (f/init) large-batch)
          time-taken (- (System/currentTimeMillis) start)]

      ;; Should complete quickly
      (is (< time-taken 500) "Large batch add should be fast")

      ;; All items should be searchable (search for full "item-N" string)
      (is (not (empty? (f/search flex "item"))))
      (is (= 150 (count (f/search flex "item"))))
      ;; Specific searches work
      (is (contains? (f/search flex "item") 0))
      (is (contains? (f/search flex "item") 75))
      (is (contains? (f/search flex "item") 149))

      ;; Should have all items indexed
      (is (= 150 (count (:ids flex)))))))

(deftest test-batch-optimization-small-batch
  "Test that small batch adds work correctly (regular string concatenation)"
  (binding [f/*batch-threshold* 100]
    (let [;; Create batch smaller than threshold
          small-batch (into {} (map (fn [i] [i (str "item-" i)]) (range 50)))
          flex (f/search-add (f/init) small-batch)]

      ;; All items should be searchable (search for full "item-N" string)
      (is (not (empty? (f/search flex "item"))))
      (is (= 50 (count (f/search flex "item"))))
      ;; Specific searches work
      (is (contains? (f/search flex "item") 0))
      (is (contains? (f/search flex "item") 25))
      (is (contains? (f/search flex "item") 49))

      ;; Should have all items indexed
      (is (= 50 (count (:ids flex)))))))

(deftest test-search-result-limit
  "Test that search limit parameter properly limits results"
  (let [;; Create data where many items match
        data (into {} (for [i (range 100)]
                        [i (str "common-word-" i)]))
        flex (f/search-add (f/init) data)]

    ;; Without limit, should return all 100 matches
    (let [results (f/search flex "common")]
      (is (= 100 (count results))))

    ;; With limit=10, should return exactly 10 results
    (let [results (f/search flex "common" {:limit 10})]
      (is (= 10 (count results)))
      (is (set? results)))

    ;; With limit=1, should return exactly 1 result
    (let [results (f/search flex "common" {:limit 1})]
      (is (= 1 (count results))))

    ;; With limit=0, should return empty set
    (let [results (f/search flex "common" {:limit 0})]
      (is (= 0 (count results))))

    ;; Limit larger than results should return all results
    (let [results (f/search flex "word-50" {:limit 100})]
      (is (= 1 (count results)))
      (is (= #{50} results)))))

(deftest test-search-limit-with-cache
  "Test that search limit works correctly with caching"
  (let [data (into {} (for [i (range 100)]
                        [i (str "test-" i)]))
        flex (f/search-add (f/init) data)]

    ;; First search with limit=5 (cache miss)
    (let [results1 (f/search flex "test" {:limit 5})]
      (is (= 5 (count results1))))

    ;; Second search with limit=5 (cache hit)
    (let [results2 (f/search flex "test" {:limit 5})]
      (is (= 5 (count results2))))

    ;; Search without limit (should use same cache, but return different count)
    (let [results3 (f/search flex "test")]
      (is (= 100 (count results3))))

    ;; Search with different limit (should use same cache)
    (let [results4 (f/search flex "test" {:limit 10})]
      (is (= 10 (count results4))))))

(deftest test-dynamic-configuration
  "Test that dynamic configuration parameters can be changed"
  ;; Test changing cache size
  (is (= 1000 f/*cache-size*)) ;; Default value
  (binding [f/*cache-size* 500]
    (is (= 500 f/*cache-size*)))

  ;; Test changing auto-GC threshold
  (is (= 0.3 f/*auto-gc-threshold*)) ;; Default 30%
  (binding [f/*auto-gc-threshold* 0.5]
    (is (= 0.5 f/*auto-gc-threshold*)))

  ;; Test changing batch threshold
  (is (= 100 f/*batch-threshold*)) ;; Default 100
  (binding [f/*batch-threshold* 200]
    (is (= 200 f/*batch-threshold*))))
