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
                 @(:cache (meta flex))))

        ;; update
        _ (is (= ["$ aka Dollars"] (mapv sample-data (f/search flex "aka Dollars"))))
        _ (is (= [] (mapv sample-data (f/search flex "aka Dollars edited"))))
        flex (f/search-add flex {0 "aka Dollars edited"})

        ;; cache after add
        _ (is (= {} @(:cache (meta flex))))

        _ (is (= ["$ aka Dollars"] (mapv sample-data (f/search flex "aka Dollars edited"))))

        ;; cache after add and search
        _ (is (= {#{"aka" "edited" "dollars"} #{0}}
                 @(:cache (meta flex))))
        ;; delete
        flex (f/search-remove flex [0])

        ;; cache on remove
        _ (is (= {#{"aka" "edited" "dollars"} #{}}
                 @(:cache (meta flex))))]

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
    (is (= {#{"test"} #{1}} @(:cache (meta deserialized))))))

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
        _ (is (= {#{"test"} #{1}} @(:cache (meta flex))))

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
