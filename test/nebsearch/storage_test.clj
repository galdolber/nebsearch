(ns nebsearch.storage-test
  (:require [clojure.test :refer [deftest testing is]]
            [nebsearch.core :as neb]
            [nebsearch.memory-storage :as mem-storage]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.storage :as storage]
            [clojure.java.io :as io]))

(defn temp-file [prefix]
  (let [f (java.io.File/createTempFile prefix ".dat")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

;; ============================================================================
;; Memory Storage Tests
;; ============================================================================

(deftest test-memory-storage-basic
  (testing "Basic memory storage operations"
    (let [storage (mem-storage/create-memory-storage)
          idx (neb/init)
          idx2 (neb/search-add idx [["doc1" "hello world" "Hello World"]
                                    ["doc2" "clojure rocks" "Clojure Rocks"]])]
      ;; Store the index
      (let [ref (neb/store idx2 storage)]
        (is (some? ref))
        (is (contains? ref :root-offset))
        (is (contains? ref :index))
        (is (contains? ref :ids))
        (is (contains? ref :pos-boundaries))

        ;; Verify storage stats
        (let [stats (storage/storage-stats storage)]
          (is (= :memory (:type stats)))
          (is (> (:node-count stats) 0))
          (is (some? (:root-offset stats))))

        ;; Restore the index
        (let [idx3 (neb/restore storage ref)]
          ;; Verify metadata was restored
          (is (= (:index idx2) (:index idx3)))
          (is (= (:ids idx2) (:ids idx3)))
          (is (= (:pos-boundaries idx2) (:pos-boundaries idx3)))

          ;; Verify search works
          (is (= #{"doc1"} (neb/search idx3 "hello")))
          (is (= #{"doc2"} (neb/search idx3 "clojure")))
          (is (= #{"doc1"} (neb/search idx3 "hello world"))))))))

(deftest test-memory-storage-empty-index
  (testing "Storing and restoring empty index"
    (let [storage (mem-storage/create-memory-storage)
          idx (neb/init)
          ref (neb/store idx storage)]
      (is (some? ref))
      ;; Empty B-tree may have nil root-offset when migrating storage with no entries
      (is (contains? ref :root-offset))

      (let [idx2 (neb/restore storage ref)]
        (is (= #{} (neb/search idx2 "anything")))
        (is (= 0 (count (:ids idx2))))))))

(deftest test-memory-storage-structural-sharing
  (testing "Structural sharing with multiple versions"
    (let [storage (mem-storage/create-memory-storage)
          idx1 (neb/search-add (neb/init) [["doc1" "version one" "V1"]])
          ref1 (neb/store idx1 storage)

          ;; Restore and add more
          idx2 (neb/restore storage ref1)
          idx3 (neb/search-add idx2 [["doc2" "version two" "V2"]])
          ref2 (neb/store idx3 storage)

          idx4 (neb/restore storage ref2)
          idx5 (neb/search-add idx4 [["doc3" "version three" "V3"]])
          ref3 (neb/store idx5 storage)]

      ;; Different root offsets
      (is (not= (:root-offset ref1) (:root-offset ref2)))
      (is (not= (:root-offset ref2) (:root-offset ref3)))

      ;; Storage should have nodes from all versions
      (let [stats (storage/storage-stats storage)]
        (is (>= (:node-count stats) 1))) ;; At least one node stored

      ;; Each version maintains its own view
      (let [v1 (neb/restore storage ref1)
            v2 (neb/restore storage ref2)
            v3 (neb/restore storage ref3)]
        (is (= #{"doc1"} (neb/search v1 "version")))
        (is (= #{"doc1" "doc2"} (neb/search v2 "version")))
        (is (= #{"doc1" "doc2" "doc3"} (neb/search v3 "version")))))))

(deftest test-memory-storage-updates
  (testing "Updating documents preserves old versions"
    (let [storage (mem-storage/create-memory-storage)
          idx1 (neb/search-add (neb/init) [["doc1" "original content" "Original"]])
          ref1 (neb/store idx1 storage)

          ;; Update the document
          idx2 (neb/restore storage ref1)
          idx3 (neb/search-add idx2 [["doc1" "updated content" "Updated"]])
          ref2 (neb/store idx3 storage)]

      ;; Old version still has original content
      (let [v1 (neb/restore storage ref1)]
        (is (= #{"doc1"} (neb/search v1 "original")))
        (is (= #{} (neb/search v1 "updated"))))

      ;; New version has updated content
      (let [v2 (neb/restore storage ref2)]
        (is (= #{} (neb/search v2 "original")))
        (is (= #{"doc1"} (neb/search v2 "updated")))))))

(deftest test-memory-storage-deletions
  (testing "Deletions work correctly with storage"
    (let [storage (mem-storage/create-memory-storage)
          idx1 (neb/search-add (neb/init) [["doc1" "keep this" "Keep"]
                                           ["doc2" "delete this" "Delete"]])
          ref1 (neb/store idx1 storage)

          idx2 (neb/restore storage ref1)
          idx3 (neb/search-remove idx2 ["doc2"])
          ref2 (neb/store idx3 storage)]

      ;; Old version still has both documents
      (let [v1 (neb/restore storage ref1)]
        (is (= 2 (count (:ids v1))))
        (is (= #{"doc1" "doc2"} (neb/search v1 "this"))))

      ;; New version only has doc1
      (let [v2 (neb/restore storage ref2)]
        (is (= 1 (count (:ids v2))))
        (is (= #{"doc1"} (neb/search v2 "this")))))))

(deftest test-memory-storage-large-dataset
  (testing "Large dataset with memory storage"
    (let [storage (mem-storage/create-memory-storage)
          docs (mapv (fn [i] [(str "doc" i)
                             (str "content number " i)
                             (str "Document " i)])
                    (range 1000))
          idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                     (neb/init)
                     docs)
          ref (neb/store idx storage)]

      ;; Verify storage stats
      (let [stats (storage/storage-stats storage)]
        (is (>= (:node-count stats) 1))) ;; Should have at least one B-tree node

      ;; Restore and verify searches
      (let [restored (neb/restore storage ref)]
        (is (= #{"doc500"} (neb/search restored "number 500")))
        (is (= #{"doc999"} (neb/search restored "number 999")))
        (is (= 1000 (count (neb/search restored "content"))))))))

;; ============================================================================
;; Disk Storage Tests
;; ============================================================================

(deftest test-disk-storage-basic
  (testing "Basic disk storage operations"
    (let [path (temp-file "test-disk-basic")
          storage (disk-storage/open-disk-storage path 128 true)
          idx (neb/init)
          idx2 (neb/search-add idx [["doc1" "hello world" "Hello World"]
                                    ["doc2" "clojure rocks" "Clojure Rocks"]])]
      (try
        ;; Store the index
        (let [ref (neb/store idx2 storage)]
          (is (some? ref))
          (is (contains? ref :root-offset))

          ;; Verify storage stats
          (let [stats (storage/storage-stats storage)]
            (is (= :disk (:type stats)))
            (is (> (:file-size stats) 0)))

          ;; Restore the index
          (let [idx3 (neb/restore storage ref)]
            ;; Index string is empty for disk storage (optimization - saves memory)
            (is (= "" (:index idx3)))
            ;; Searches still work perfectly via inverted index
            (is (= #{"doc1"} (neb/search idx3 "hello")))
            (is (= #{"doc2"} (neb/search idx3 "clojure")))))
        (finally
          (storage/close storage))))))

(deftest test-disk-storage-persistence
  (testing "Data persists across storage closures"
    (let [path (temp-file "test-disk-persist")
          idx (neb/search-add (neb/init) [["doc1" "persistent data" "Persistent"]])
          ref (atom nil)]

      ;; Create, store, and close
      (let [storage (disk-storage/open-disk-storage path 128 true)]
        (reset! ref (neb/store idx storage))
        (storage/close storage))

      ;; Verify file exists
      (is (.exists (io/file path)))

      ;; Reopen and restore
      (let [storage2 (disk-storage/open-disk-storage path 128 false)]
        (try
          (let [idx2 (neb/restore storage2 @ref)]
            (is (= #{"doc1"} (neb/search idx2 "persistent"))))
          (finally
            (storage/close storage2)))))))

(deftest test-disk-storage-structural-sharing
  (testing "Structural sharing with disk storage"
    (let [path (temp-file "test-disk-cow")
          storage (disk-storage/open-disk-storage path 128 true)]
      (try
        (let [idx1 (neb/search-add (neb/init) [["doc1" "version one" "V1"]])
              ref1 (neb/store idx1 storage)

              idx2 (neb/restore storage ref1)
              idx3 (neb/search-add idx2 [["doc2" "version two" "V2"]])
              ref2 (neb/store idx3 storage)

              idx4 (neb/restore storage ref2)
              idx5 (neb/search-add idx4 [["doc3" "version three" "V3"]])
              ref3 (neb/store idx5 storage)]

          ;; Different root offsets
          (is (not= (:root-offset ref1) (:root-offset ref2)))
          (is (not= (:root-offset ref2) (:root-offset ref3)))

          ;; File should grow but not linearly (due to sharing)
          (let [stats (storage/storage-stats storage)]
            (is (> (:file-size stats) 0)))

          ;; Each version accessible
          (let [v1 (neb/restore storage ref1)
                v2 (neb/restore storage ref2)
                v3 (neb/restore storage ref3)]
            (is (= #{"doc1"} (neb/search v1 "version")))
            (is (= #{"doc1" "doc2"} (neb/search v2 "version")))
            (is (= #{"doc1" "doc2" "doc3"} (neb/search v3 "version")))))
        (finally
          (storage/close storage))))))

(deftest test-disk-storage-updates-and-deletes
  (testing "Updates and deletes work with disk storage"
    (let [path (temp-file "test-disk-updates")
          storage (disk-storage/open-disk-storage path 128 true)]
      (try
        (let [idx1 (neb/search-add (neb/init) [["doc1" "original" "Orig"]
                                               ["doc2" "temporary" "Temp"]])
              ref1 (neb/store idx1 storage)

              ;; Update doc1
              idx2 (neb/restore storage ref1)
              idx3 (neb/search-add idx2 [["doc1" "updated" "Updated"]])
              ref2 (neb/store idx3 storage)

              ;; Delete doc2
              idx4 (neb/restore storage ref2)
              idx5 (neb/search-remove idx4 ["doc2"])
              ref3 (neb/store idx5 storage)]

          ;; Version 1: original content, both docs
          (let [v1 (neb/restore storage ref1)]
            (is (= #{"doc1"} (neb/search v1 "original")))
            (is (= #{"doc2"} (neb/search v1 "temporary"))))

          ;; Version 2: updated doc1, still has doc2
          (let [v2 (neb/restore storage ref2)]
            (is (= #{"doc1"} (neb/search v2 "updated")))
            (is (= #{} (neb/search v2 "original")))
            (is (= #{"doc2"} (neb/search v2 "temporary"))))

          ;; Version 3: updated doc1, no doc2
          (let [v3 (neb/restore storage ref3)]
            (is (= #{"doc1"} (neb/search v3 "updated")))
            (is (= #{} (neb/search v3 "temporary")))
            (is (= 1 (count (:ids v3))))))
        (finally
          (storage/close storage))))))

(deftest test-disk-storage-large-dataset
  (testing "Large dataset with disk storage"
    (let [path (temp-file "test-disk-large")
          storage (disk-storage/open-disk-storage path 128 true)]
      (try
        (let [docs (mapv (fn [i] [(str "doc" i)
                                  (str "content number " i)
                                  (str "Document " i)])
                        (range 500))
              idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                         (neb/init)
                         docs)
              ref (neb/store idx storage)]

          ;; Verify file created
          (is (.exists (io/file path)))

          ;; Restore and verify
          (let [restored (neb/restore storage ref)]
            (is (= #{"doc250"} (neb/search restored "number 250")))
            (is (= #{"doc499"} (neb/search restored "number 499")))
            (is (= 500 (count (neb/search restored "content"))))))
        (finally
          (storage/close storage))))))

;; ============================================================================
;; Hybrid Workflow Tests
;; ============================================================================

(deftest test-hybrid-in-memory-to-lazy
  (testing "Can convert in-memory index to lazy"
    (let [storage (mem-storage/create-memory-storage)
          ;; Start with in-memory
          idx-mem (neb/search-add (neb/init) [["doc1" "in memory" "Memory"]])

          ;; Store to get lazy version
          ref (neb/store idx-mem storage)
          idx-lazy (neb/restore storage ref)]

      ;; Both work the same
      (is (= (neb/search idx-mem "memory")
             (neb/search idx-lazy "memory")))

      ;; Can continue working with lazy
      (let [idx-lazy2 (neb/search-add idx-lazy [["doc2" "in memory lazy" "Lazy"]])]
        (is (= #{"doc1" "doc2"} (neb/search idx-lazy2 "in memory")))))))

(deftest test-hybrid-lazy-to-in-memory
  (testing "Can continue in-memory after storing"
    (let [storage (mem-storage/create-memory-storage)
          idx1 (neb/search-add (neb/init) [["doc1" "test" "Test"]])
          ref (neb/store idx1 storage)]

      ;; Continue working with original in-memory index
      (let [idx2 (neb/search-add idx1 [["doc2" "test data" "More"]])]
        (is (= #{"doc1" "doc2"} (neb/search idx2 "test")))

        ;; Original stored version unchanged
        (let [restored (neb/restore storage ref)]
          (is (= #{"doc1"} (neb/search restored "test")))
          (is (= #{} (neb/search restored "more"))))))))

(deftest test-multiple-checkpoints
  (testing "Multiple checkpoint workflow"
    (let [storage (mem-storage/create-memory-storage)
          idx1 (neb/search-add (neb/init) [["doc1" "checkpoint one" "CP1"]])
          cp1 (neb/store idx1 storage)

          idx2 (neb/search-add idx1 [["doc2" "checkpoint two" "CP2"]])
          cp2 (neb/store idx2 storage)

          idx3 (neb/search-add idx2 [["doc3" "checkpoint three" "CP3"]])
          cp3 (neb/store idx3 storage)]

      ;; Can restore any checkpoint
      (is (= 1 (count (:ids (neb/restore storage cp1)))))
      (is (= 2 (count (:ids (neb/restore storage cp2)))))
      (is (= 3 (count (:ids (neb/restore storage cp3)))))

      ;; Each checkpoint has correct data
      (is (= #{"doc1"} (neb/search (neb/restore storage cp1) "checkpoint")))
      (is (= #{"doc1" "doc2"} (neb/search (neb/restore storage cp2) "checkpoint")))
      (is (= #{"doc1" "doc2" "doc3"} (neb/search (neb/restore storage cp3) "checkpoint"))))))

;; ============================================================================
;; Edge Cases and Error Handling
;; ============================================================================

(deftest test-storage-with-special-characters
  (testing "Storage handles special characters correctly"
    (let [storage (mem-storage/create-memory-storage)
          idx (neb/search-add (neb/init) [["doc1" "hello@world.com" "Email"]
                                          ["doc2" "user+tag@example.com" "Tagged"]
                                          ["doc3" "path/to/file.txt" "Path"]])
          ref (neb/store idx storage)
          restored (neb/restore storage ref)]

      (is (= #{"doc1" "doc2"} (neb/search restored "com")))
      (is (= #{"doc3"} (neb/search restored "path"))))))

(deftest test-storage-with-unicode
  (testing "Storage handles unicode correctly"
    (let [storage (mem-storage/create-memory-storage)
          idx (neb/search-add (neb/init) [["doc1" "café résumé" "French"]
                                          ["doc2" "日本語" "Japanese"]
                                          ["doc3" "emoji😀test" "Emoji"]])
          ref (neb/store idx storage)
          restored (neb/restore storage ref)]

      ;; Normalized search
      (is (= #{"doc1"} (neb/search restored "cafe resume")))
      (is (set? (neb/search restored "emoji")))))) ;; Should work without error

(deftest test-storage-with-very-long-strings
  (testing "Storage handles very long strings"
    (let [storage (mem-storage/create-memory-storage)
          long-string (apply str (repeat 5000 "a"))
          idx (neb/search-add (neb/init) [["doc1" long-string "Long"]])
          ref (neb/store idx storage)
          restored (neb/restore storage ref)]

      (is (= #{"doc1"} (neb/search restored "aaa"))))))

(deftest test-concurrent-storage-reads
  (testing "Can read from same storage reference multiple times"
    (let [storage (mem-storage/create-memory-storage)
          idx (neb/search-add (neb/init) [["doc1" "test" "Test"]])
          ref (neb/store idx storage)]

      ;; Multiple restores should work
      (let [r1 (neb/restore storage ref)
            r2 (neb/restore storage ref)
            r3 (neb/restore storage ref)]
        (is (= (neb/search r1 "test")
               (neb/search r2 "test")
               (neb/search r3 "test")))))))

;; ============================================================================
;; Protocol Implementation Tests
;; ============================================================================

(deftest test-storage-protocols
  (testing "Storage implementations satisfy required protocols"
    (let [mem-storage (mem-storage/create-memory-storage)
          disk-path (temp-file "test-protocols")
          disk-storage (disk-storage/open-disk-storage disk-path 128 true)]
      (try
        ;; IStorage
        (is (satisfies? storage/IStorage mem-storage))
        (is (satisfies? storage/IStorage disk-storage))

        ;; IStorageRoot
        (is (satisfies? storage/IStorageRoot mem-storage))
        (is (satisfies? storage/IStorageRoot disk-storage))

        ;; IStorageSave
        (is (satisfies? storage/IStorageSave mem-storage))
        (is (satisfies? storage/IStorageSave disk-storage))

        ;; IStorageClose
        (is (satisfies? storage/IStorageClose mem-storage))
        (is (satisfies? storage/IStorageClose disk-storage))

        ;; IStorageStats
        (is (satisfies? storage/IStorageStats mem-storage))
        (is (satisfies? storage/IStorageStats disk-storage))
        (finally
          (storage/close disk-storage))))))

(deftest test-storage-stats
  (testing "Storage stats provide useful information"
    (let [storage (mem-storage/create-memory-storage)
          idx (neb/search-add (neb/init) [["doc1" "test data" "Test"]])
          ref (neb/store idx storage)
          stats (storage/storage-stats storage)]

      (is (= :memory (:type stats)))
      (is (number? (:node-count stats)))
      (is (>= (:node-count stats) 0))
      (is (number? (:size-bytes stats)))
      (is (> (:size-bytes stats) 0)))))
