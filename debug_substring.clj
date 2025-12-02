(require '[nebsearch.core :as neb])
(require '[nebsearch.disk-storage :as disk])

(println "Creating test index with 100 docs...")
(def docs (mapv (fn [i]
                  [(str "doc" i)
                   (str "word" (mod i 10) " content")
                   (str "Title " i)])
                (range 100)))

(def path "/tmp/debug-substring.dat")
(.delete (java.io.File. path))
(def store (disk/open-disk-storage path 128 true))
(def idx-mem (neb/search-add (neb/init) docs))
(def ref (neb/store idx-mem store))
(def idx-disk (neb/restore store ref))

(println "\nSearching for 'wor' (substring of word0-word9)...")
(def result (neb/search idx-disk "wor"))
(println "Found:" (count result) "docs")
(println "Sample doc IDs:" (take 10 (sort result)))

;; Debug: Check word cache
(let [word-cache-atom (:word-cache (meta idx-disk))]
  (println "\nWord cache contents:")
  (println "  Cached?" (not (nil? @word-cache-atom)))
  (when @word-cache-atom
    (println "  Total words:" (count @word-cache-atom))
    (println "  Words containing 'wor':" (filter #(clojure.string/includes? % "wor") @word-cache-atom))))

;; Debug: Check inverted index directly
(require '[nebsearch.btree :as bt])
(let [inverted (:inverted (meta idx-disk))]
  (println "\nInverted index entries:")
  (println "  Total entries:" (count (bt/bt-seq inverted)))
  (println "  First 10 entries:")
  (doseq [entry (take 10 (bt/bt-seq inverted))]
    (println "   " entry)))

;; Test hash lookup directly
(println "\nTesting direct hash lookup for 'word0'...")
(let [inverted (:inverted (meta idx-disk))
      word-hash (unchecked-long (.hashCode "word0"))
      matches (bt/bt-range inverted word-hash word-hash)]
  (println "  Word hash:" word-hash)
  (println "  Matches found:" (count matches))
  (doseq [entry (take 5 matches)]
    (println "   " entry)))

(.delete (java.io.File. path))
(System/exit 0)
