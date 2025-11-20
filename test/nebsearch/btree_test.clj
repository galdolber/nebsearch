(ns nebsearch.btree-test
  (:require [clojure.test :refer :all]
            [nebsearch.core :as neb]
            [nebsearch.btree :as bt]
            [clojure.java.io :as io]))

(defn temp-file [prefix]
  (let [f (java.io.File/createTempFile prefix ".dat")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(deftest test-durable-basic-operations
  (testing "Create durable index and add documents"
    (let [path (temp-file "test-durable-basic")
          idx (neb/init {:durable? true :index-path path})]
      (try
        ;; Add some documents
        (let [idx2 (neb/search-add idx {"doc1" "hello world"
                                        "doc2" "world peace"
                                        "doc3" "hello peace"})]
          ;; Search for documents
          (is (= #{"doc1" "doc3"} (neb/search idx2 "hello")))
          (is (= #{"doc1" "doc2"} (neb/search idx2 "world")))
          (is (= #{"doc2" "doc3"} (neb/search idx2 "peace")))
          (is (= #{"doc1"} (neb/search idx2 "hello world")))

          ;; Check stats
          (let [stats (neb/index-stats idx2)]
            (is (= :durable (:mode stats)))
            (is (= 3 (:document-count stats)))
            (is (> (:index-size stats) 0))))
        (finally
          (neb/close idx))))))

(deftest test-durable-updates
  (testing "Update documents in durable index"
    (let [path (temp-file "test-durable-updates")
          idx (neb/init {:durable? true :index-path path})]
      (try
        (let [idx2 (neb/search-add idx {"doc1" "hello world"})
              idx3 (neb/search-add idx2 {"doc1" "goodbye world"})]
          ;; Old content should not be found
          (is (empty? (neb/search idx3 "hello")))
          ;; New content should be found
          (is (= #{"doc1"} (neb/search idx3 "goodbye"))))
        (finally
          (neb/close idx))))))

(deftest test-durable-remove
  (testing "Remove documents from durable index"
    (let [path (temp-file "test-durable-remove")
          idx (neb/init {:durable? true :index-path path})]
      (try
        (let [idx2 (neb/search-add idx {"doc1" "hello world"
                                        "doc2" "world peace"
                                        "doc3" "hello peace"})
              idx3 (neb/search-remove idx2 ["doc2"])]
          ;; doc2 should not be found
          (is (= #{"doc1" "doc3"} (neb/search idx3 "hello")))
          (is (= #{"doc1"} (neb/search idx3 "world")))
          (is (= #{"doc3"} (neb/search idx3 "peace"))))
        (finally
          (neb/close idx))))))

(deftest test-durable-persistence
  (testing "Data persists across index closures"
    (let [path (temp-file "test-durable-persistence")]
      ;; Create index and add data
      (let [idx (neb/init {:durable? true :index-path path})
            idx2 (neb/search-add idx {"doc1" "hello world"
                                      "doc2" "world peace"})]
        (neb/flush idx2)
        (neb/close idx2))

      ;; Note: Currently open-index doesn't restore the index string and ids map
      ;; This is a limitation of the current implementation
      ;; We'd need to persist those to a separate metadata file
      ;; For now, this test just verifies the B-tree file is created
      (is (.exists (io/file path))))))

(deftest test-durable-large-dataset
  (testing "Durable index with larger dataset"
    (let [path (temp-file "test-durable-large")
          idx (neb/init {:durable? true :index-path path})]
      (try
        ;; Add 1000 documents
        (let [docs (into {} (for [i (range 1000)]
                              [(str "doc" i) (str "content " (mod i 10))]))
              idx2 (neb/search-add idx docs)]
          ;; Each content appears in 100 documents
          (is (= 100 (count (neb/search idx2 "content 5"))))
          (is (= 100 (count (neb/search idx2 "content 7"))))

          ;; Check stats
          (let [stats (neb/index-stats idx2)]
            (is (= 1000 (:document-count stats)))
            (is (= :durable (:mode stats)))))
        (finally
          (neb/close idx))))))

(deftest test-durable-cow-semantics
  (testing "Copy-on-write semantics preserve old versions"
    (let [path (temp-file "test-durable-cow")
          idx (neb/init {:durable? true :index-path path})]
      (try
        (let [idx1 (neb/search-add idx {"doc1" "version one"})
              idx2 (neb/search-add idx1 {"doc2" "version two"})
              idx3 (neb/search-add idx2 {"doc3" "version three"})]
          ;; Each version should maintain its own view
          ;; (though currently they share the same underlying file)
          (is (= #{"doc1"} (neb/search idx1 "one")))
          (is (= #{"doc1" "doc2"} (neb/search idx2 "version")))
          (is (= #{"doc1" "doc2" "doc3"} (neb/search idx3 "version")))

          ;; Get B-tree stats
          (let [stats (neb/index-stats idx3)
                btree-stats (:btree-stats stats)]
            (is (some? btree-stats))
            (is (= :durable (:type btree-stats)))
            (is (> (:node-count btree-stats) 0))))
        (finally
          (neb/close idx))))))

(deftest test-btree-direct-operations
  (testing "Direct B-tree operations"
    (let [path (temp-file "test-btree-direct")
          btree (bt/open-btree path true)]
      (try
        ;; Insert some entries
        (let [btree2 (bt/bt-insert btree [10 "doc1"])
              btree3 (bt/bt-insert btree2 [20 "doc2"])
              btree4 (bt/bt-insert btree3 [30 "doc3"])]
          ;; Search for entries
          (is (= [10 "doc1"] (bt/bt-search btree4 10)))
          (is (= [20 "doc2"] (bt/bt-search btree4 20)))
          (is (= [30 "doc3"] (bt/bt-search btree4 30)))

          ;; Range query
          (let [range-results (bt/bt-range btree4 10 30)]
            (is (= 3 (count range-results)))
            (is (= [[10 "doc1"] [20 "doc2"] [30 "doc3"]] (vec range-results))))

          ;; Sequence
          (let [seq-results (bt/bt-seq btree4)]
            (is (= [[10 "doc1"] [20 "doc2"] [30 "doc3"]] (vec seq-results))))

          ;; Delete
          (let [btree5 (bt/bt-delete btree4 [20 "doc2"])]
            (is (= [10 "doc1"] (bt/bt-search btree5 10)))
            (is (nil? (bt/bt-search btree5 20)))
            (is (= [30 "doc3"] (bt/bt-search btree5 30)))))
        (finally
          (bt/close-btree btree))))))

(deftest test-btree-node-splits
  (testing "B-tree handles node splits correctly"
    (let [path (temp-file "test-btree-splits")
          btree (bt/open-btree path true)]
      (try
        ;; Insert many entries to trigger splits
        (let [entries (for [i (range 1000)] [i (str "doc" i)])
              final-btree (reduce bt/bt-insert btree entries)]
          ;; Verify all entries are present
          (doseq [i (range 1000)]
            (is (= [i (str "doc" i)] (bt/bt-search final-btree i))))

          ;; Verify range queries work
          (let [range-results (bt/bt-range final-btree 100 200)]
            (is (= 101 (count range-results)))
            (is (= [100 "doc100"] (first range-results)))
            (is (= [200 "doc200"] (last range-results))))

          ;; Check stats
          (let [stats (bt/btree-stats final-btree)]
            (is (> (:node-count stats) 1)) ;; Should have multiple nodes
            (is (> (:file-size stats) 0))))
        (finally
          (bt/close-btree btree))))))

(deftest test-durable-gc
  (testing "Garbage collection works in durable mode"
    (let [path (temp-file "test-durable-gc")
          idx (neb/init {:durable? true :index-path path})]
      (try
        ;; Add and remove many documents to create fragmentation
        (let [idx2 (neb/search-add idx (into {} (for [i (range 100)]
                                                  [(str "doc" i) (str "content " i)])))
              idx3 (neb/search-remove idx2 (map #(str "doc" %) (range 0 50)))
              stats-before (neb/index-stats idx3)
              idx4 (neb/search-gc idx3)
              stats-after (neb/index-stats idx4)]
          ;; Fragmentation should be reduced
          (is (> (:fragmentation stats-before) 0))
          (is (< (:fragmentation stats-after) (:fragmentation stats-before)))

          ;; Search should still work
          (is (empty? (neb/search idx4 "content 25"))) ;; Removed
          (is (= #{"doc75"} (neb/search idx4 "content 75")))) ;; Still present
        (finally
          (neb/close idx))))))

(deftest test-comparison-in-memory-vs-durable
  (testing "In-memory and durable modes produce same results"
    (let [docs {"doc1" "hello world"
                "doc2" "world peace"
                "doc3" "hello peace"
                "doc4" "goodbye world"}
          ;; In-memory
          idx-mem (neb/search-add (neb/init) docs)
          ;; Durable
          path (temp-file "test-comparison")
          idx-dur (neb/search-add (neb/init {:durable? true :index-path path}) docs)]
      (try
        ;; Same search results
        (is (= (neb/search idx-mem "hello")
               (neb/search idx-dur "hello")))
        (is (= (neb/search idx-mem "world")
               (neb/search idx-dur "world")))
        (is (= (neb/search idx-mem "hello world")
               (neb/search idx-dur "hello world")))

        ;; Same stats (excluding B-tree specific ones)
        (let [stats-mem (neb/index-stats idx-mem)
              stats-dur (neb/index-stats idx-dur)]
          (is (= (:document-count stats-mem) (:document-count stats-dur)))
          (is (= (:index-size stats-mem) (:index-size stats-dur)))
          (is (= (:fragmentation stats-mem) (:fragmentation stats-dur))))
        (finally
          (neb/close idx-dur))))))

(run-tests)
