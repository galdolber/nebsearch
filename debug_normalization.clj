(require '[nebsearch.core :as neb])
(require '[nebsearch.disk-storage :as disk])
(require '[nebsearch.btree :as bt])

(println "Testing with larger dataset to find normalization issue...")

;; Use same doc generation as benchmark
(def docs (mapv (fn [i]
                  [(str "doc" i)
                   (str "word" (mod i 100) " common content document " (when (< i 1000) (str "rare" i)))
                   (str "Title " i)])
                (range 100)))  ;; Start with 100 to keep it fast

(def path "/tmp/debug-norm.dat")
(.delete (java.io.File. path))
(def store (disk/open-disk-storage path 128 true))
(def idx (neb/search-add (neb/init) docs))
(def ref (neb/store idx store))
(def idx-disk (neb/restore store ref))

(println "\n=== Checking inverted index ===")
(let [inverted (:inverted (meta idx-disk))
      all-entries (bt/bt-seq inverted)]
  (println "Total inverted entries:" (count all-entries))
  (println "\nFirst 10 entries:")
  (doseq [entry (take 10 all-entries)]
    (println "  " entry "| hash:" (nth entry 0)))

  ;; Check for "word0" specifically
  (let [word0-entries (filter #(= "word0" (nth % 2)) all-entries)]
    (println "\nEntries for 'word0':" (count word0-entries))
    (println "First 5:")
    (doseq [entry (take 5 word0-entries)]
      (println "  " entry))))

(println "\n=== Testing hash lookup for 'word0' ===")
(let [inverted (:inverted (meta idx-disk))
      word-hash (unchecked-long (.hashCode "word0"))
      matches (bt/bt-range inverted word-hash word-hash)]
  (println "Hash:" word-hash)
  (println "Matches:" (count matches))
  (doseq [m (take 5 matches)]
    (println "  " m)))

(println "\n=== Testing search ===")
(def result (neb/search idx-disk "word0"))
(println "Search 'word0' found:" (count result) "docs (expected ~10)")
(println "Sample results:" (take 5 (sort result)))

(.delete (java.io.File. path))
(System/exit 0)
