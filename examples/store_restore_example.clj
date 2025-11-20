(ns store-restore-example
  "Example demonstrating nebsearch's store/restore API.

  Similar to tonsky/persistent-sorted-set, nebsearch supports:
  - Dynamic persistence - store indexes at any point
  - Lazy restoration - nodes loaded on-demand
  - Structural sharing - multiple versions share unchanged nodes
  - Transparent operations - lazy indexes work like in-memory ones"
  (:require [nebsearch.core :as neb]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.memory-storage :as memory-storage]))

(println "\n=== Store/Restore API Example ===\n")

;; Example 1: Basic store and restore
(println "1. Basic Store and Restore:")
(println "   Creating a normal in-memory search index...")

(def idx (neb/init))
(def idx2 (neb/search-add idx [["doc1" "hello world" "Hello World Document"]
                                ["doc2" "clojure programming" "Clojure Programming Guide"]
                                ["doc3" "functional programming" "Functional Programming Basics"]]))

(println "   - Created index with 3 documents")
(println "   - Index type:" (type (:data idx2)))
(println "   - Searching 'programming':" (neb/search idx2 "programming"))

;; Store the index and get a reference
(println "\n   - Storing index to memory storage...")
(def storage (memory-storage/create-memory-storage))
(def ref1 (neb/store idx2 storage))

(println "   - Stored! Reference:" ref1)
(println "   - Storage stats:" (nebsearch.storage/storage-stats storage))

;; Restore the index (lazy)
(println "\n   - Restoring index from storage (lazy loading)...")
(def idx-lazy (neb/restore storage ref1))

(println "   - Restored! Index type:" (type (:data idx-lazy)))
(println "   - Searching 'programming' on lazy index:" (neb/search idx-lazy "programming"))
(println "   ✓ Works transparently!\n")

;; Example 2: Structural Sharing
(println "2. Structural Sharing with Multiple Versions:")
(println "   Demonstrating COW semantics and node reuse...")

;; Make changes to the lazy index
(def idx3 (neb/search-add idx-lazy [["doc4" "lazy evaluation" "Lazy Evaluation in Clojure"]]))
(println "   - Added 'doc4' to lazy index")

;; Store again - should share most nodes with ref1
(def ref2 (neb/store idx3 storage))
(println "   - Stored as new version")
(println "   - Reference 1 root offset:" (:root-offset ref1))
(println "   - Reference 2 root offset:" (:root-offset ref2))
(println "   - Storage stats:" (nebsearch.storage/storage-stats storage))
(println "   - Node count shows structural sharing!")

;; Make more changes
(def idx4 (neb/search-add idx3 [["doc5" "persistent data structures" "Persistent Data Structures"]]))
(def ref3 (neb/store idx4 storage))

(println "\n   - Added 'doc5' and stored as ref3")
(println "   - Reference 3 root offset:" (:root-offset ref3))
(println "   - Storage stats:" (nebsearch.storage/storage-stats storage))
(println "   ✓ Only new/changed nodes are stored!\n")

;; Example 3: Time Travel with References
(println "3. Time Travel - Accessing Different Versions:")
(println "   Loading different versions from their references...")

(def version1 (neb/restore storage ref1))
(def version2 (neb/restore storage ref2))
(def version3 (neb/restore storage ref3))

(println "   - Version 1 documents:" (count (:ids version1)))
(println "   - Version 2 documents:" (count (:ids version2)))
(println "   - Version 3 documents:" (count (:ids version3)))

(println "\n   - Searching 'lazy' in version 1:" (neb/search version1 "lazy"))
(println "   - Searching 'lazy' in version 2:" (neb/search version2 "lazy"))
(println "   - Searching 'lazy' in version 3:" (neb/search version3 "lazy"))
(println "   ✓ Can access any version at any time!\n")

;; Example 4: Disk Storage with Persistence
(println "4. Disk Storage - Real Persistence:")
(println "   Using disk storage instead of memory...")

(def disk-path "/tmp/search-index-store-restore.dat")
(def disk-store (disk-storage/open-disk-storage disk-path 128 true))

;; Create a new index
(def idx5 (neb/search-add (neb/init)
                          [["a1" "artificial intelligence" "AI Overview"]
                           ["a2" "machine learning" "ML Basics"]
                           ["a3" "deep learning" "Deep Learning Guide"]]))

(println "   - Created index with 3 AI-related documents")

;; Store to disk
(def disk-ref (neb/store idx5 disk-store))
(println "   - Stored to disk:" disk-path)
(println "   - Disk reference:" disk-ref)

;; Close the storage
(nebsearch.storage/close disk-store)
(println "   - Closed storage")

;; Reopen and restore
(println "\n   - Reopening storage from disk...")
(def disk-store2 (disk-storage/open-disk-storage disk-path 128 false))
(def idx6 (neb/restore disk-store2 disk-ref))

(println "   - Restored from disk!")
(println "   - Searching 'learning':" (neb/search idx6 "learning"))
(println "   ✓ Survived disk persistence!")

;; Make changes and store again
(def idx7 (neb/search-add idx6 [["a4" "neural networks" "Neural Networks 101"]]))
(def disk-ref2 (neb/store idx7 disk-store2))

(println "\n   - Added 'neural networks' and stored")
(println "   - New reference:" disk-ref2)
(println "   - Can restore either version from disk!")

(nebsearch.storage/close disk-store2)
(println "   - Closed storage\n")

;; Example 5: Mixing In-Memory and Lazy
(println "5. Hybrid Workflow - Mixing In-Memory and Lazy:")
(println "   Start in-memory, store when needed, continue in-memory...")

;; Start with in-memory
(def mem-idx (neb/search-add (neb/init)
                             [["m1" "clojure" "Clojure Docs"]
                              ["m2" "java interop" "Java Interop"]]))

(println "   - Created in-memory index with 2 docs")
(println "   - Type:" (type (:data mem-idx)))

;; Store checkpoint
(def mem-storage (memory-storage/create-memory-storage))
(def checkpoint1 (neb/store mem-idx mem-storage))
(println "   - Stored checkpoint 1")

;; Continue working in-memory
(def mem-idx2 (neb/search-add mem-idx [["m3" "macros" "Clojure Macros"]]))
(println "   - Added doc in-memory (not stored yet)")

;; Store another checkpoint
(def checkpoint2 (neb/store mem-idx2 mem-storage))
(println "   - Stored checkpoint 2")

;; Restore from first checkpoint and continue
(def restored (neb/restore mem-storage checkpoint1))
(println "   - Restored from checkpoint 1 (2 docs)")
(println "   - Can continue from any checkpoint!")

;; Search works on all versions
(println "\n   - Searching 'macros' in checkpoint 1:" (neb/search restored "macros"))
(def restored2 (neb/restore mem-storage checkpoint2))
(println "   - Searching 'macros' in checkpoint 2:" (neb/search restored2 "macros"))
(println "   ✓ Multiple parallel histories!\n")

(nebsearch.storage/close mem-storage)

;; Example 6: Large Index with Lazy Loading
(println "6. Large Index - Demonstrating Lazy Loading:")
(println "   Creating index with 10,000 documents...")

(def large-idx
  (reduce (fn [idx i]
            (neb/search-add idx
                           [[(str "doc-" i)
                             (str "content number " i " with some text")
                             (str "Document " i)]]))
          (neb/init)
          (range 10000)))

(println "   - Created 10,000 documents")

;; Store to memory
(def large-storage (memory-storage/create-memory-storage))
(def large-ref (neb/store large-idx large-storage))

(println "   - Stored to memory storage")
(println "   - Storage stats:" (nebsearch.storage/storage-stats large-storage))

;; Restore lazily
(def large-lazy (neb/restore large-storage large-ref))

(println "\n   - Restored lazily (nodes loaded on-demand)")
(println "   - Searching 'number 5000':" (take 1 (neb/search large-lazy "number 5000")))
(println "   - Searching 'number 9999':" (take 1 (neb/search large-lazy "number 9999")))
(println "   ✓ Only loads nodes needed for each search!\n")

(nebsearch.storage/close large-storage)

(println "=== All examples completed! ===\n")

;; Key Takeaways
(println "Key Takeaways:")
(println "1. Create normal in-memory indexes with (init)")
(println "2. Store at any point with (store index storage) -> returns reference")
(println "3. Restore with (restore storage ref) -> returns lazy index")
(println "4. All operations work transparently on lazy indexes")
(println "5. Structural sharing via COW - only changed nodes are stored")
(println "6. Can access multiple versions simultaneously (time travel)")
(println "7. Works with both memory and disk storage")
(println "8. Lazy loading - only loads nodes when needed")
(println "\nAPI is compatible with persistent-sorted-set's durability pattern!")
