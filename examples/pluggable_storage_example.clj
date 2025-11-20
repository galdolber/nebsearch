(ns pluggable-storage-example
  "Example demonstrating nebsearch's pluggable storage API.

  Similar to tonsky/persistent-sorted-set's IStorage durability interface,
  nebsearch now supports pluggable storage backends for its B-tree."
  (:require [nebsearch.btree :as bt]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.memory-storage :as memory-storage]
            [nebsearch.storage :as storage]))

(println "\n=== Pluggable Storage API Example ===\n")

;; Example 1: Disk Storage (default durable mode)
(println "1. Disk Storage Example:")
(println "   Creating a B-tree with disk-based storage...")

(let [storage (disk-storage/open-disk-storage "/tmp/test-btree.dat" 128 true)
      btree (bt/open-btree storage)]

  (println "   - Storage type:" (:type (storage/storage-stats storage)))
  (println "   - Initial stats:" (bt/btree-stats btree))

  ;; Insert some data
  (println "\n   - Inserting entries...")
  (let [btree2 (-> btree
                   (bt/bt-insert [100 "doc1"])
                   (bt/bt-insert [200 "doc2"])
                   (bt/bt-insert [300 "doc3"]))]

    (println "   - After inserts (not saved yet):")
    (println "     Root offset:" (:root-offset btree2))

    ;; IMPORTANT: Storage is explicit! Changes are NOT saved until you call save
    (println "\n   - Explicitly saving to disk...")
    (disk-storage/set-root-offset! storage (:root-offset btree2))
    (storage/save storage)

    (println "   - Saved!")
    (println "   - Storage stats:" (storage/storage-stats storage))

    ;; Search for an entry
    (println "\n   - Searching for entry at position 200...")
    (let [result (bt/bt-search btree2 200)]
      (println "     Found:" result))

    ;; Clean up
    (storage/close storage)
    (println "   - Closed storage\n")))

;; Example 2: Memory Storage (for testing)
(println "2. Memory Storage Example:")
(println "   Creating a B-tree with in-memory storage...")

(let [storage (memory-storage/create-memory-storage)
      btree (bt/open-btree storage)]

  (println "   - Storage type:" (:type (storage/storage-stats storage)))

  ;; Insert data
  (let [btree2 (-> btree
                   (bt/bt-insert [10 "item1"])
                   (bt/bt-insert [20 "item2"])
                   (bt/bt-insert [30 "item3"])
                   (bt/bt-insert [40 "item4"])
                   (bt/bt-insert [50 "item5"]))]

    (println "   - Inserted 5 entries")
    (memory-storage/set-root-offset! storage (:root-offset btree2))

    ;; Get all entries
    (println "   - All entries:" (take 10 (bt/bt-seq btree2)))

    ;; Get range
    (println "   - Range [20-40]:" (bt/bt-range btree2 20 40))

    ;; Storage stats
    (println "   - Storage stats:" (storage/storage-stats storage))
    (println "   - Node count:" (count @(.-*storage storage)))

    (storage/close storage)
    (println "   - Closed storage\n")))

;; Example 3: Bulk Operations
(println "3. Bulk Insert Example:")
(println "   Creating B-tree and bulk inserting 1000 entries...")

(let [storage (memory-storage/create-memory-storage)
      btree (bt/open-btree storage)
      ;; Generate 1000 entries
      entries (mapv (fn [i] [(* i 10) (str "doc-" i)]) (range 1000))]

  (println "   - Generated" (count entries) "entries")

  (let [btree2 (bt/bt-bulk-insert btree entries)]
    (println "   - Bulk inserted!")
    (println "   - B-tree stats:" (bt/btree-stats btree2))
    (println "   - Storage stats:" (storage/storage-stats storage))

    ;; Search for a specific entry
    (println "\n   - Searching for entry at position 5000...")
    (let [result (bt/bt-search btree2 5000)]
      (println "     Found:" result))

    ;; Get a range
    (println "   - Getting range [4990-5010]...")
    (let [range-results (bt/bt-range btree2 4990 5010)]
      (println "     Results:" range-results))

    (storage/close storage)
    (println "   - Closed storage\n")))

;; Example 4: Demonstrating explicit save semantics
(println "4. Explicit Save Semantics:")
(println "   Showing that changes are not saved until explicit save...")

(let [file-path "/tmp/test-explicit-save.dat"
      storage (disk-storage/open-disk-storage file-path 128 true)
      btree (bt/open-btree storage)]

  ;; Insert data but don't save
  (let [btree2 (-> btree
                   (bt/bt-insert [1 "a"])
                   (bt/bt-insert [2 "b"])
                   (bt/bt-insert [3 "c"]))]

    (println "   - Inserted 3 entries (not saved yet)")
    (println "   - Current root offset in memory:" (:root-offset btree2))
    (println "   - Root offset in storage:" (disk-storage/get-root-offset storage))

    ;; Now save explicitly
    (println "\n   - Calling explicit save...")
    (disk-storage/set-root-offset! storage (:root-offset btree2))
    (storage/save storage)

    (println "   - Saved!")
    (println "   - Root offset in storage after save:" (disk-storage/get-root-offset storage))

    ;; Close and reopen to verify persistence
    (storage/close storage)
    (println "\n   - Closed and reopening...")

    (let [storage2 (disk-storage/open-disk-storage file-path 128 false)
          btree3 (bt/open-btree storage2)]

      (println "   - Reopened from disk")
      (println "   - Root offset after reopen:" (:root-offset btree3))
      (println "   - All entries:" (take 10 (bt/bt-seq btree3)))

      (storage/close storage2)
      (println "   - Verified persistence!\n"))))

(println "=== All examples completed! ===\n")

;; Key Takeaways:
(println "Key Takeaways:")
(println "1. Storage is now pluggable via the IStorage protocol")
(println "2. Changes are NOT automatically saved - you must call save explicitly")
(println "3. Disk storage persists to files, memory storage is for testing")
(println "4. API is similar to persistent-sorted-set's IStorage durability")
(println "5. Use set-root-offset! before save to persist the current tree state")
