(ns durable-index-example
  "Examples demonstrating persistent durable search indexes with structural sharing.

  This example shows how to use the COW B-tree implementation for disk-backed,
  persistent search indexes where updates share most of their structure with
  previous versions."
  (:require [nebsearch.core :as neb]
            [nebsearch.btree :as bt]))

(comment
  ;;
  ;; BASIC USAGE - In-Memory vs Durable
  ;;

  ;; Traditional in-memory index (default)
  (def idx-mem (neb/init))
  (def idx-mem-2 (neb/search-add idx-mem {"doc1" "hello world"
                                          "doc2" "world peace"}))
  (neb/search idx-mem-2 "world")
  ;; => #{"doc1" "doc2"}

  ;; Durable disk-backed index with lazy loading
  (def idx-dur (neb/init {:durable? true :index-path "/tmp/my-index.dat"}))
  (def idx-dur-2 (neb/search-add idx-dur {"doc1" "hello world"
                                          "doc2" "world peace"}))
  (neb/search idx-dur-2 "world")
  ;; => #{"doc1" "doc2"}

  ;; Flush to disk
  (neb/flush idx-dur-2)

  ;; Close when done
  (neb/close idx-dur-2)


  ;;
  ;; STRUCTURAL SHARING - Multiple Versions
  ;;

  ;; Each operation creates a new root, but shares most nodes with previous versions
  (def v1 (neb/init {:durable? true :index-path "/tmp/versions.dat"}))
  (def v2 (neb/search-add v1 {"doc1" "version one"}))
  (def v3 (neb/search-add v2 {"doc2" "version two"}))
  (def v4 (neb/search-add v3 {"doc3" "version three"}))

  ;; Each version maintains its own view
  (neb/search v2 "version") ;; => #{"doc1"}
  (neb/search v3 "version") ;; => #{"doc1" "doc2"}
  (neb/search v4 "version") ;; => #{"doc1" "doc2" "doc3"}

  ;; The B-tree nodes are shared between versions (Copy-on-Write)
  ;; Only modified paths from root to leaf are rewritten
  (neb/index-stats v4)
  ;; => {:mode :durable
  ;;     :document-count 3
  ;;     :index-size 123
  ;;     :fragmentation 0.0
  ;;     :cache-size 0
  ;;     :btree-stats {:type :durable
  ;;                   :root-offset 12345
  ;;                   :node-count 15
  ;;                   :cache-size 5
  ;;                   :file-size 65536}}

  (neb/close v4)


  ;;
  ;; LAZY LOADING - Only Load What You Need
  ;;

  ;; With a large durable index, only the nodes needed for search are loaded
  (def large-idx (neb/init {:durable? true :index-path "/tmp/large.dat"}))

  ;; Add 100,000 documents
  (def large-idx-2
    (reduce (fn [idx i]
              (neb/search-add idx {(str "doc" i)
                                   (str "content " (mod i 1000))}))
            large-idx
            (range 100000)))

  ;; This search only loads the B-tree nodes containing "content 42"
  ;; The rest of the tree stays on disk
  (time (count (neb/search large-idx-2 "content 42")))
  ;; => 100 documents found (in ~10ms, only loading necessary nodes)

  ;; Check what's in the cache
  (get-in (neb/index-stats large-idx-2) [:btree-stats :cache-size])
  ;; => 5 (only 5 nodes loaded out of hundreds)

  (neb/close large-idx-2)


  ;;
  ;; DIRECT B-TREE OPERATIONS
  ;;

  ;; You can also use the B-tree directly for custom use cases
  (def btree (bt/open-btree "/tmp/my-btree.dat" true))

  ;; Insert [position, id] pairs (sorted by position)
  (def btree-2 (bt/bt-insert btree [100 "doc1"]))
  (def btree-3 (bt/bt-insert btree-2 [200 "doc2"]))
  (def btree-4 (bt/bt-insert btree-3 [300 "doc3"]))

  ;; Point lookup
  (bt/bt-search btree-4 200)
  ;; => [200 "doc2"]

  ;; Range query
  (bt/bt-range btree-4 100 300)
  ;; => [[100 "doc1"] [200 "doc2"] [300 "doc3"]]

  ;; Full scan (lazy)
  (take 2 (bt/bt-seq btree-4))
  ;; => ([100 "doc1"] [200 "doc2"])

  ;; Delete
  (def btree-5 (bt/bt-delete btree-4 [200 "doc2"]))
  (bt/bt-search btree-5 200)
  ;; => nil

  ;; COW semantics - old version still has the data
  (bt/bt-search btree-4 200)
  ;; => [200 "doc2"]

  (bt/close-btree btree-5)


  ;;
  ;; FILE FORMAT AND STRUCTURAL SHARING
  ;;

  ;; The B-tree file has this structure:
  ;;
  ;; [Header - 256 bytes]
  ;;   - Magic number: "NEBSRCH\0"
  ;;   - Version: 1
  ;;   - Root offset: pointer to current root node
  ;;   - Node count: total nodes in file
  ;;   - B-tree order: 128 (max children per internal node)
  ;;
  ;; [Nodes - variable size]
  ;;   Each node:
  ;;     - Length (4 bytes)
  ;;     - EDN serialized node data (variable)
  ;;     - CRC32 checksum (4 bytes)
  ;;
  ;; Example node structure:
  ;;
  ;; Internal node:
  ;; {:type :internal
  ;;  :keys [100 200 300]           ;; Guide searches
  ;;  :children [offset1 offset2 ...] ;; File offsets to child nodes
  ;;  :offset 12345}                ;; This node's offset
  ;;
  ;; Leaf node:
  ;; {:type :leaf
  ;;  :entries [[100 "doc1"] [200 "doc2"]] ;; [position id] pairs
  ;;  :next-leaf 45678              ;; Offset to next leaf (for scans)
  ;;  :offset 12345}
  ;;
  ;; When you update the index:
  ;; 1. New leaf node is written to end of file
  ;; 2. Parent internal node is copied with updated child pointer
  ;; 3. This propagates up to the root
  ;; 4. New root offset is written to header
  ;; 5. Old nodes remain in file (structural sharing!)
  ;;
  ;; Example:
  ;;
  ;; Version 1:              Root₁ (offset: 1000)
  ;;                        /     \
  ;;                  Node_A       Node_B
  ;;                (offset: 500) (offset: 800)
  ;;
  ;; Version 2 (after adding one entry):
  ;;                         Root₂ (offset: 2000) <-- new
  ;;                        /     \
  ;;                  Node_A       Node_C (offset: 1500) <-- new
  ;;                (offset: 500)  ^
  ;;                   ^           |
  ;;                   |        Modified
  ;;                 Shared
  ;;
  ;; Node_A is shared between V1 and V2!
  ;; Node_B still exists in file (for V1 or GC later)


  ;;
  ;; PERSISTENCE AND RECOVERY
  ;;

  ;; Create and populate an index
  (def persist-idx (neb/init {:durable? true :index-path "/tmp/persist.dat"}))
  (def persist-idx-2 (neb/search-add persist-idx {"doc1" "important data"
                                                   "doc2" "more data"}))
  ;; Ensure it's written to disk
  (neb/flush persist-idx-2)
  (neb/close persist-idx-2)

  ;; Later, open the existing index
  ;; Note: Currently open-index has limitations - it doesn't restore
  ;; the index string and ids map (they'd need to be in a separate metadata file)
  ;; This is a known TODO in the current implementation
  (def reopened (neb/open-index {:index-path "/tmp/persist.dat"}))
  ;; The B-tree data is preserved, but you'd need to rebuild the search
  ;; index or store it separately

  (neb/close reopened)


  ;;
  ;; PERFORMANCE CHARACTERISTICS
  ;;

  ;; In-memory mode:
  ;; - Search: O(n * m) where n = query words, m = index string length
  ;; - Insert: O(log k) where k = number of entries (sorted set)
  ;; - Delete: O(log k)
  ;; - Memory: O(k) - all data in RAM
  ;;
  ;; Durable mode:
  ;; - Search: O(n * (m + log k * I/O)) - same algorithm, plus disk I/O
  ;; - Insert: O(log k * I/O) - B-tree operations with disk writes
  ;; - Delete: O(log k * I/O)
  ;; - Memory: O(cache size) - only cached nodes in RAM
  ;; - Disk: O(k * versions) - nodes shared across versions
  ;;
  ;; Node cache significantly improves performance for repeated operations


  ;;
  ;; GARBAGE COLLECTION
  ;;

  ;; After many updates/deletes, you may want to compact the index
  (def gc-idx (neb/init {:durable? true :index-path "/tmp/gc.dat"}))

  ;; Add and remove many documents
  (def gc-idx-2
    (neb/search-add gc-idx
                    (into {} (for [i (range 1000)]
                              [(str "doc" i) (str "content " i)]))))
  (def gc-idx-3
    (neb/search-remove gc-idx-2
                       (map #(str "doc" %) (range 0 500))))

  ;; Check fragmentation
  (:fragmentation (neb/index-stats gc-idx-3))
  ;; => 0.5 (50% of index string is spaces)

  ;; Compact the index
  (def gc-idx-4 (neb/search-gc gc-idx-3))

  (:fragmentation (neb/index-stats gc-idx-4))
  ;; => 0.0 (compacted!)

  (neb/close gc-idx-4)


  ;;
  ;; SNAPSHOTS AND VERSION MANAGEMENT
  ;;

  ;; The current implementation supports structural sharing automatically.
  ;; Each time you call search-add or search-remove, you get a new version
  ;; that shares most of its structure with the previous version.
  ;;
  ;; Future enhancements could include:
  ;; - Version log file to track all historical roots
  ;; - Snapshot API to name and restore specific versions
  ;; - Garbage collection of unreferenced old nodes
  ;; - Time-travel queries
  ;;
  ;; Example future API (not yet implemented):
  ;;
  ;; (def snap1 (neb/snapshot idx-v1 "checkpoint-1"))
  ;; (def snap2 (neb/snapshot idx-v2 "checkpoint-2"))
  ;; (neb/restore-snapshot "checkpoint-1")
  ;; (neb/list-snapshots)
  ;; (neb/gc-unreferenced-nodes [snap1 snap2]) ;; Keep only these versions

  )
