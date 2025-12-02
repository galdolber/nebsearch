(require '[nebsearch.core :as neb])
(require '[nebsearch.disk-storage :as disk])
(require '[nebsearch.btree :as bt])
(require '[nebsearch.entries :as entries])

(println "Testing InvertedEntry hash storage...")

;; Create small test
(def docs [["doc1" "word0 content" "Title"]])

(def path "/tmp/debug-hash.dat")
(.delete (java.io.File. path))
(def store (disk/open-disk-storage path 128 true))
(def idx (neb/search-add (neb/init) docs))
(def ref (neb/store idx store))
(def idx-disk (neb/restore store ref))

;; Inspect inverted entries
(let [inverted (:inverted (meta idx-disk))]
  (println "\nInverted entries:")
  (doseq [entry (bt/bt-seq inverted)]
    (println "  Entry:" entry)
    (println "    Type:" (type entry))
    (let [e entry]
      (println "    Index 0 (hash?):" (nth e 0))
      (println "    Index 1 (doc-id?):" (nth e 1))
      (when (>= (count entry) 3)
        (println "    Index 2 (word?):" (nth e 2)))))

  ;; Try hash lookup
  (let [test-word "word0"
        word-hash (unchecked-long (.hashCode test-word))]
    (println "\nHash lookup test:")
    (println "  Word:" test-word)
    (println "  Computed hash:" word-hash)
    (println "  Range query result:" (bt/bt-range inverted word-hash word-hash))))

(.delete (java.io.File. path))
(System/exit 0)
