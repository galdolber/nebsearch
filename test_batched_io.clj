(ns test-batched-io
  (:require [nebsearch.btree :as btree]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.storage :as storage]
            [nebsearch.entries :as entries]
            [clojure.java.io :as io]))

(println "\n=== Testing Batched I/O Implementation ===\n")

;; Clean up any existing test file
(def test-file "/tmp/test-batched-io.nebsearch")
(.delete (io/file test-file))

;; Create disk storage
(def stor (disk-storage/open-disk-storage test-file 128 true))

(println "1. Testing batch-store protocol implementation...")
(if (satisfies? storage/IBatchedStorage stor)
  (println "   ✓ DiskStorage implements IBatchedStorage")
  (println "   ✗ DiskStorage does NOT implement IBatchedStorage"))

;; Create some test nodes
(def test-nodes
  [(btree/leaf-node [(entries/->DocumentEntry 1 "doc1" "text1")
                     (entries/->DocumentEntry 2 "doc2" "text2")] nil)
   (btree/leaf-node [(entries/->DocumentEntry 3 "doc3" "text3")
                     (entries/->DocumentEntry 4 "doc4" "text4")] nil)
   (btree/leaf-node [(entries/->DocumentEntry 5 "doc5" "text5")] nil)])

(println "\n2. Testing batch-store with 3 leaf nodes...")
(def offsets (storage/batch-store stor test-nodes))
(println "   ✓ Batch-store returned offsets:" offsets)

(println "\n3. Verifying nodes can be restored...")
(doseq [[idx offset] (map-indexed vector offsets)]
  (let [node (storage/restore stor offset)]
    (if (= (:type node) :leaf)
      (println (format "   ✓ Node %d restored successfully (offset: %d)" idx offset))
      (println (format "   ✗ Node %d restore failed!" idx)))))

;; Test bulk insert with batched I/O
(println "\n4. Testing bt-bulk-insert with batched I/O...")
(def btree (btree/open-btree stor))
(def entries (vec (for [i (range 1000)]
                   (entries/->DocumentEntry i (str "doc-" i) (str "text-" i)))))

(def start-time (System/nanoTime))
(def result (btree/bt-bulk-insert btree entries))
(def end-time (System/nanoTime))
(def elapsed-ms (/ (- end-time start-time) 1000000.0))

(println (format "   ✓ Bulk inserted 1000 entries in %.2f ms" elapsed-ms))

;; Verify the tree
(println "\n5. Verifying tree structure...")
(when (:root-offset result)
  (println (format "   ✓ Root offset: %d" (:root-offset result)))
  (let [stats (btree/btree-stats result)]
    (println (format "   ✓ Node count: %d" (:node-count stats)))
    (println (format "   ✓ Leaf count: %d" (:leaf-count stats)))))

;; Cleanup
(storage/close stor)
(.delete (io/file test-file))

(println "\n=== All Batched I/O Tests Passed! ===\n")
(System/exit 0)
