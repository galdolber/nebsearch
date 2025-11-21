(ns test-disk-storage
  (:require [nebsearch.btree :as btree]
            [nebsearch.disk-storage :as disk]
            [nebsearch.entries :as entries]
            [clojure.java.io :as io]))

(println "\n=== Testing Disk Storage with Both Entry Types ===\n")

;; Clean up
(def test-file "/tmp/test-disk-storage.nebsearch")
(.delete (io/file test-file))

;; Test 1: Document B-tree (long keys)
(println "Test 1: Document B-tree with long keys")
(let [stor (disk/open-disk-storage test-file 128 true)
      btree (btree/open-btree stor)

      ;; Create DocumentEntry objects (long keys)
      entries (vec (for [i (range 100)]
                    (entries/->DocumentEntry
                      (long i)
                      (str "doc-" i)
                      nil)))

      ;; Bulk insert
      result (btree/bt-bulk-insert btree entries)]

  (println (format "  ✓ Inserted %d DocumentEntry objects" (count entries)))
  (println (format "  ✓ Root offset: %d" (:root-offset result)))

  ;; Verify we can read back
  (let [root-node (nebsearch.storage/restore stor (:root-offset result))]
    (println (format "  ✓ Root node type: %s" (:type root-node)))
    (println (format "  ✓ Root has %d children" (count (:children root-node))))
    (println (format "  ✓ First key type: %s" (class (first (:keys root-node))))))

  (nebsearch.storage/close stor))

(.delete (io/file test-file))
(println)

;; Test 2: Inverted index B-tree (string keys)
(println "Test 2: Inverted index B-tree with string keys")
(let [stor (disk/open-disk-storage test-file 128 true)
      btree (btree/open-btree stor)

      ;; Create InvertedEntry objects (string keys)
      words ["apple" "banana" "cherry" "date" "elderberry"
             "fig" "grape" "honeydew" "kiwi" "lemon"]
      entries (vec (for [word words
                         doc-id (range 10)]
                    (entries/->InvertedEntry word (str "doc-" doc-id))))

      ;; Bulk insert
      result (btree/bt-bulk-insert btree entries)]

  (println (format "  ✓ Inserted %d InvertedEntry objects" (count entries)))
  (println (format "  ✓ Root offset: %d" (:root-offset result)))

  ;; Verify we can read back
  (let [root-node (nebsearch.storage/restore stor (:root-offset result))]
    (println (format "  ✓ Root node type: %s" (:type root-node)))
    (when (= (:type root-node) :internal)
      (println (format "  ✓ Root has %d children" (count (:children root-node))))
      (println (format "  ✓ First key type: %s" (class (first (:keys root-node))))))
    (when (= (:type root-node) :leaf)
      (println (format "  ✓ Root has %d entries" (count (:entries root-node))))))

  (nebsearch.storage/close stor))

(.delete (io/file test-file))
(println)

(println "=== Analysis ===\n")
(println "The B-tree needs to handle TWO use cases:")
(println "1. Document B-tree: Long keys (pos field) - PRIMARY use case")
(println "2. Inverted index: String keys (word field) - SECONDARY use case")
(println)
(println "Current issue:")
(println "- Optimizing for longs breaks inverted index")
(println "- Supporting both adds overhead to the hot path")
(println)
(println "Possible solutions:")
(println "1. Two separate B-tree implementations (long vs string)")
(println "2. Hash string keys to longs for storage (lossy but fast)")
(println "3. Use protocol dispatch (small overhead)")
(println "4. Separate inverted index from B-tree (different structure)")
(println "5. Accept small overhead for type checking")

(System/exit 0)
