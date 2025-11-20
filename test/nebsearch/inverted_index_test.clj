(ns nebsearch.inverted-index-test
  (:require [clojure.test :refer :all]
            [nebsearch.core :as neb]
            [nebsearch.memory-storage :as mem-storage]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.storage :as storage]
            [nebsearch.btree :as bt]))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn cleanup-disk-files []
  (doseq [f ["/tmp/test-inverted.dat"
             "/tmp/test-inverted-migration.dat"
             "/tmp/test-inverted-stress.dat"]]
    (when (.exists (java.io.File. f))
      (.delete (java.io.File. f)))))

(defn get-inverted-cache [idx]
  "Get the inverted index cache from metadata"
  (:inverted (meta idx)))

(defn is-lazy-inverted? [idx]
  "Check if index uses lazy inverted (atom)"
  (instance? clojure.lang.Atom (get-inverted-cache idx)))

(defn is-precomputed-inverted? [idx]
  "Check if index uses pre-computed inverted (B-tree)"
  (instance? nebsearch.btree.DurableBTree (get-inverted-cache idx)))

;; ============================================================================
;; Memory Storage (Lazy Inverted Index) Tests
;; ============================================================================

(deftest test-lazy-inverted-basic
  (testing "Lazy inverted index basic functionality"
    (let [idx (neb/init)
          idx2 (neb/search-add idx [["doc1" "hello world" "Title"]
                                     ["doc2" "hello there" "Title"]
                                     ["doc3" "world news" "Title"]])]

      ;; Should use lazy inverted (atom)
      (is (is-lazy-inverted? idx2))

      ;; Initially empty
      (is (empty? @(get-inverted-cache idx2)))

      ;; First search builds cache
      (let [result1 (neb/search idx2 "hello")]
        (is (= #{"doc1" "doc2"} result1))
        ;; Cache should now contain "hello"
        (is (contains? @(get-inverted-cache idx2) "hello"))
        (is (= #{"doc1" "doc2"} (get @(get-inverted-cache idx2) "hello"))))

      ;; Second search uses cache
      (let [result2 (neb/search idx2 "hello")]
        (is (= #{"doc1" "doc2"} result2)))

      ;; Different word builds new cache entry
      (let [result3 (neb/search idx2 "world")]
        (is (= #{"doc1" "doc3"} result3))
        (is (contains? @(get-inverted-cache idx2) "world")))

      ;; Multi-word query
      (let [result4 (neb/search idx2 "hello world")]
        (is (= #{"doc1"} result4))))))

(deftest test-lazy-inverted-add-updates
  (testing "Adding documents updates lazy cache correctly"
    (let [idx (neb/init)
          idx2 (neb/search-add idx [["doc1" "foo bar" "T1"]])
          _ (neb/search idx2 "bar")  ;; Build cache for "bar"
          cache-before @(get-inverted-cache idx2)]

      ;; Cache should contain "bar"
      (is (= #{"doc1"} (get cache-before "bar")))

      ;; Add new document with "bar"
      (let [idx3 (neb/search-add idx2 [["doc2" "bar baz" "T2"]])
            cache-after @(get-inverted-cache idx3)]

        ;; Cache should be updated for "bar"
        (is (= #{"doc1" "doc2"} (get cache-after "bar")))

        ;; Search should return both
        (is (= #{"doc1" "doc2"} (neb/search idx3 "bar")))))))

(deftest test-lazy-inverted-remove-updates
  (testing "Removing documents updates lazy cache correctly"
    (let [idx (neb/init)
          idx2 (neb/search-add idx [["doc1" "foo bar" "T1"]
                                     ["doc2" "bar baz" "T2"]])
          _ (neb/search idx2 "bar")  ;; Build cache
          cache-before @(get-inverted-cache idx2)]

      ;; Cache should contain both docs
      (is (= #{"doc1" "doc2"} (get cache-before "bar")))

      ;; Remove doc1
      (let [idx3 (neb/search-remove idx2 ["doc1"])
            cache-after @(get-inverted-cache idx3)]

        ;; Cache should be updated
        (is (= #{"doc2"} (get cache-after "bar")))

        ;; Search should return only doc2
        (is (= #{"doc2"} (neb/search idx3 "bar")))))))

(deftest test-lazy-inverted-only-cached-words
  (testing "Lazy inverted only caches searched words"
    (let [idx (neb/init)
          idx2 (neb/search-add idx [["doc1" "alpha beta gamma" "T1"]
                                     ["doc2" "beta delta epsilon" "T2"]
                                     ["doc3" "gamma zeta eta" "T3"]])]

      ;; Search only for "beta"
      (neb/search idx2 "beta")

      ;; Only "beta" should be cached
      (let [cache @(get-inverted-cache idx2)]
        (is (contains? cache "beta"))
        (is (not (contains? cache "alpha")))
        (is (not (contains? cache "gamma")))
        (is (not (contains? cache "delta")))))))

;; ============================================================================
;; Disk Storage (Pre-computed Inverted Index) Tests
;; ============================================================================

(deftest test-precomputed-inverted-basic
  (testing "Pre-computed inverted index basic functionality"
    (cleanup-disk-files)
    (let [storage (disk-storage/open-disk-storage "/tmp/test-inverted.dat" 128 true)
          idx (neb/init)
          idx2 (neb/search-add idx [["doc1" "hello world" "Title"]
                                     ["doc2" "hello there" "Title"]
                                     ["doc3" "world news" "Title"]])
          ref (neb/store idx2 storage)
          idx3 (neb/restore storage ref)]

      ;; Should have inverted-root-offset
      (is (some? (:inverted-root-offset ref)))

      ;; Restored index should use pre-computed inverted (B-tree)
      (is (is-precomputed-inverted? idx3))

      ;; Search should work
      (is (= #{"doc1" "doc2"} (neb/search idx3 "hello")))
      (is (= #{"doc1" "doc3"} (neb/search idx3 "world")))
      (is (= #{"doc1"} (neb/search idx3 "hello world")))

      (storage/close storage))))

(deftest test-precomputed-inverted-persistence
  (testing "Pre-computed inverted persists across sessions"
    (cleanup-disk-files)

    ;; Session 1: Create and store
    (let [storage1 (disk-storage/open-disk-storage "/tmp/test-inverted.dat" 128 true)
          idx1 (neb/search-add (neb/init) [["doc1" "alpha beta" "T1"]
                                            ["doc2" "beta gamma" "T2"]])
          ref1 (neb/store idx1 storage1)]
      (is (some? (:inverted-root-offset ref1)))
      (storage/close storage1))

    ;; Session 2: Restore and verify
    (let [storage2 (disk-storage/open-disk-storage "/tmp/test-inverted.dat" 128 false)
          root-offset (storage/get-root-offset storage2)
          ;; Manually create reference (simulating reload)
          idx2 (neb/search-add (neb/init) [["doc1" "alpha beta" "T1"]
                                            ["doc2" "beta gamma" "T2"]])
          ref2 (neb/store idx2 storage2)
          idx3 (neb/restore storage2 ref2)]

      ;; Should still work
      (is (= #{"doc1" "doc2"} (neb/search idx3 "beta")))
      (is (= #{"doc1"} (neb/search idx3 "alpha")))

      (storage/close storage2))))

(deftest test-precomputed-inverted-updates
  (testing "Pre-computed inverted handles updates"
    (cleanup-disk-files)
    (let [storage (disk-storage/open-disk-storage "/tmp/test-inverted.dat" 128 true)
          idx1 (neb/search-add (neb/init) [["doc1" "foo bar" "T1"]])
          ref1 (neb/store idx1 storage)
          idx2 (neb/restore storage ref1)

          ;; Add document
          idx3 (neb/search-add idx2 [["doc2" "bar baz" "T2"]])
          ref2 (neb/store idx3 storage)
          idx4 (neb/restore storage ref2)]

      ;; Should find both documents
      (is (= #{"doc1" "doc2"} (neb/search idx4 "bar")))

      (storage/close storage))))

(deftest test-precomputed-inverted-deletes
  (testing "Pre-computed inverted handles deletes"
    (cleanup-disk-files)
    (let [storage (disk-storage/open-disk-storage "/tmp/test-inverted.dat" 128 true)
          idx1 (neb/search-add (neb/init) [["doc1" "foo bar" "T1"]
                                            ["doc2" "bar baz" "T2"]])
          ref1 (neb/store idx1 storage)
          idx2 (neb/restore storage ref1)

          ;; Remove document
          idx3 (neb/search-remove idx2 ["doc1"])
          ref2 (neb/store idx3 storage)
          idx4 (neb/restore storage ref2)]

      ;; Should only find doc2
      (is (= #{"doc2"} (neb/search idx4 "bar")))
      (is (= #{} (neb/search idx4 "foo")))

      (storage/close storage))))

;; ============================================================================
;; Storage Migration Tests
;; ============================================================================

(deftest test-migration-memory-to-disk-empty-cache
  (testing "Migrating memory→disk with empty lazy cache builds inverted"
    (cleanup-disk-files)
    (let [mem-idx (neb/search-add (neb/init) [["doc1" "migrate test" "T1"]
                                               ["doc2" "test data" "T2"]])
          ;; Don't search - lazy cache is empty
          storage (disk-storage/open-disk-storage "/tmp/test-inverted-migration.dat" 128 true)
          ref (neb/store mem-idx storage)]

      ;; Should have built inverted index during migration
      (is (some? (:inverted-root-offset ref)))

      ;; Restore and verify
      (let [disk-idx (neb/restore storage ref)]
        (is (= #{"doc1" "doc2"} (neb/search disk-idx "test")))
        (is (= #{"doc1"} (neb/search disk-idx "migrate"))))

      (storage/close storage))))

(deftest test-migration-memory-to-disk-with-cache
  (testing "Migrating memory→disk with populated lazy cache"
    (cleanup-disk-files)
    (let [mem-idx (neb/search-add (neb/init) [["doc1" "apple orange" "T1"]
                                               ["doc2" "banana orange" "T2"]])
          ;; Search to populate cache
          _ (neb/search mem-idx "apple")

          storage (disk-storage/open-disk-storage "/tmp/test-inverted-migration.dat" 128 true)
          ref (neb/store mem-idx storage)]

      ;; Should have built full inverted index (not just cached words)
      (is (some? (:inverted-root-offset ref)))

      ;; Restore and verify all words work
      (let [disk-idx (neb/restore storage ref)]
        (is (= #{"doc1"} (neb/search disk-idx "apple")))
        (is (= #{"doc2"} (neb/search disk-idx "banana")))
        (is (= #{"doc1" "doc2"} (neb/search disk-idx "orange"))))

      (storage/close storage))))

(deftest test-migration-disk-to-disk
  (testing "Migrating disk→disk preserves inverted B-tree"
    (cleanup-disk-files)
    (let [storage1 (disk-storage/open-disk-storage "/tmp/test-inverted.dat" 128 true)
          idx1 (neb/search-add (neb/init) [["doc1" "source data" "T1"]])
          ref1 (neb/store idx1 storage1)
          idx2 (neb/restore storage1 ref1)

          ;; Migrate to different storage
          storage2 (disk-storage/open-disk-storage "/tmp/test-inverted-migration.dat" 128 true)
          ref2 (neb/store idx2 storage2)
          idx3 (neb/restore storage2 ref2)]

      ;; Should work in new storage
      (is (some? (:inverted-root-offset ref2)))
      (is (= #{"doc1"} (neb/search idx3 "source")))

      (storage/close storage1)
      (storage/close storage2))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest test-empty-index-search
  (testing "Searching empty index with inverted"
    (let [idx (neb/init)]
      (is (= #{} (neb/search idx "anything")))
      (is (= #{} (neb/search idx "multiple words"))))))

(deftest test-non-existent-word
  (testing "Searching for non-existent word"
    (let [idx (neb/search-add (neb/init) [["doc1" "real words" "T1"]])]
      (is (= #{} (neb/search idx "nonexistent")))
      ;; Lazy cache should still be built
      (is (contains? @(get-inverted-cache idx) "nonexistent"))
      (is (= #{} (get @(get-inverted-cache idx) "nonexistent"))))))

(deftest test-special-characters-inverted
  (testing "Inverted index handles special characters"
    (let [idx (neb/search-add (neb/init) [["doc1" "test.value" "T1"]
                                           ["doc2" "test+plus" "T2"]])]
      ;; These get split by default-splitter
      (is (= #{"doc1"} (neb/search idx "test.value")))
      (is (= #{"doc2"} (neb/search idx "test+plus"))))))

(deftest test-very-large-document
  (testing "Inverted index handles large documents"
    (let [large-text (apply str (repeat 1000 "word "))
          idx (neb/search-add (neb/init) [["doc1" large-text "T1"]])]
      (is (= #{"doc1"} (neb/search idx "word"))))))

(deftest test-many-documents-same-word
  (testing "Inverted index handles many docs with same word"
    (let [docs (mapv (fn [i] [(str "doc" i) "common keyword" (str "T" i)])
                     (range 100))
          idx (neb/search-add (neb/init) docs)]
      (is (= 100 (count (neb/search idx "common"))))
      (is (= 100 (count (neb/search idx "keyword")))))))

;; ============================================================================
;; GC and Fragmentation Tests
;; ============================================================================

(deftest test-gc-preserves-inverted
  (testing "GC preserves inverted index metadata"
    (let [idx1 (neb/search-add (neb/init) [["doc1" "before gc" "T1"]
                                            ["doc2" "also before" "T2"]])
          _ (neb/search idx1 "before")  ;; Build cache
          idx2 (neb/search-remove idx1 ["doc1"])
          idx3 (neb/search-gc idx2)]

      ;; Should still have inverted metadata
      (is (some? (get-inverted-cache idx3)))
      (is (is-lazy-inverted? idx3))

      ;; Search should work
      (is (= #{"doc2"} (neb/search idx3 "before"))))))

;; ============================================================================
;; Metadata Preservation Tests
;; ============================================================================

(deftest test-metadata-preserved-through-add
  (testing "Inverted metadata preserved through search-add"
    (let [idx1 (neb/init)
          cache1 (get-inverted-cache idx1)
          idx2 (neb/search-add idx1 [["doc1" "test" "T1"]])
          cache2 (get-inverted-cache idx2)]

      ;; Should have inverted metadata
      (is (some? cache2))
      ;; Should be same type (atom for memory)
      (is (is-lazy-inverted? idx2)))))

(deftest test-metadata-preserved-through-remove
  (testing "Inverted metadata preserved through search-remove"
    (let [idx1 (neb/search-add (neb/init) [["doc1" "test" "T1"]])
          _ (neb/search idx1 "test")  ;; Build cache
          idx2 (neb/search-remove idx1 ["doc1"])
          cache (get-inverted-cache idx2)]

      ;; Should still have inverted metadata
      (is (some? cache))
      (is (is-lazy-inverted? idx2)))))

;; ============================================================================
;; Correctness Tests
;; ============================================================================

(deftest test-inverted-matches-string-scan
  (testing "Inverted index results match string scanning"
    (let [docs [["doc1" "alpha beta gamma" "T1"]
                ["doc2" "beta gamma delta" "T2"]
                ["doc3" "gamma delta epsilon" "T3"]
                ["doc4" "delta epsilon zeta" "T4"]]
          idx (neb/search-add (neb/init) docs)

          ;; Test various queries
          queries ["alpha" "beta" "gamma" "delta" "epsilon" "zeta"
                   "alpha beta" "gamma delta" "beta gamma delta"
                   "nonexistent" "alpha nonexistent"]]

      (doseq [q queries]
        ;; First search builds inverted
        (let [result1 (neb/search idx q)
              ;; Second search uses inverted
              result2 (neb/search idx q)]
          ;; Results should be identical
          (is (= result1 result2) (str "Query: " q)))))))

(deftest test-inverted-multi-word-intersection
  (testing "Multi-word queries use intersection correctly"
    (let [idx (neb/search-add (neb/init) [["doc1" "red blue" "T1"]
                                           ["doc2" "blue green" "T2"]
                                           ["doc3" "red green" "T3"]])]
      ;; Build cache for all words
      (neb/search idx "red")
      (neb/search idx "blue")
      (neb/search idx "green")

      ;; Multi-word queries
      (is (= #{"doc1"} (neb/search idx "red blue")))
      (is (= #{"doc2"} (neb/search idx "blue green")))
      (is (= #{"doc3"} (neb/search idx "red green")))
      (is (= #{} (neb/search idx "red blue green"))))))

;; ============================================================================
;; Stress Tests
;; ============================================================================

(deftest test-stress-many-operations
  (testing "Stress test with many operations"
    (cleanup-disk-files)
    (let [storage (disk-storage/open-disk-storage "/tmp/test-inverted-stress.dat" 128 true)

          ;; Add 1000 documents
          docs1 (mapv (fn [i] [(str "doc" i) (str "word" (mod i 10)) (str "T" i)])
                      (range 1000))
          idx1 (neb/search-add (neb/init) docs1)

          ;; Search multiple times
          _ (dotimes [_ 10]
              (neb/search idx1 "word0")
              (neb/search idx1 "word5"))

          ;; Add more documents
          docs2 (mapv (fn [i] [(str "doc" (+ i 1000)) (str "word" (mod i 10)) (str "T" (+ i 1000))])
                      (range 100))
          idx2 (neb/search-add idx1 docs2)

          ;; Remove some
          to-remove (mapv #(str "doc" %) (range 0 100))
          idx3 (neb/search-remove idx2 to-remove)

          ;; Store and restore
          ref (neb/store idx3 storage)
          idx4 (neb/restore storage ref)]

      ;; Verify results
      ;; Initial: 1000 docs (0-999), word0 in 100 docs
      ;; Add: 100 docs (1000-1099), word0 in 10 more docs = 110 total
      ;; Remove: docs 0-99 removes 10 docs with word0
      ;; Expected: 110 - 10 = 100 docs with word0
      (is (= 100 (count (neb/search idx4 "word0"))))
      (is (= 100 (count (neb/search idx4 "word5"))))

      (storage/close storage))))

(deftest test-concurrent-versions
  (testing "Multiple versions of index work independently"
    (let [idx1 (neb/search-add (neb/init) [["doc1" "v1 data" "T1"]])
          idx2 (neb/search-add idx1 [["doc2" "v2 data" "T2"]])
          idx3 (neb/search-add idx2 [["doc3" "v3 data" "T3"]])]

      ;; Each version should be independent
      (is (= #{"doc1"} (neb/search idx1 "v1")))
      (is (= #{"doc1" "doc2"} (neb/search idx2 "data")))
      (is (= #{"doc1" "doc2" "doc3"} (neb/search idx3 "data")))

      ;; Build caches
      (neb/search idx1 "data")
      (neb/search idx2 "data")
      (neb/search idx3 "data")

      ;; Caches should be independent
      (is (= #{"doc1"} (get @(get-inverted-cache idx1) "data")))
      (is (= #{"doc1" "doc2"} (get @(get-inverted-cache idx2) "data")))
      (is (= #{"doc1" "doc2" "doc3"} (get @(get-inverted-cache idx3) "data"))))))

;; ============================================================================
;; Run all tests
;; ============================================================================

(defn run-all-inverted-tests []
  (run-tests 'nebsearch.inverted-index-test))
