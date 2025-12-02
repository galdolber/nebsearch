(require '[nebsearch.core :as neb])
(require '[nebsearch.disk-storage :as disk])

(println "Creating small test index...")
(def docs (mapv (fn [i]
                  [(str "doc" i)
                   (str "word" (mod i 10) " content")
                   (str "Title " i)])
                (range 100)))

(def idx-mem (neb/search-add (neb/init) docs))

(def path "/tmp/debug-word-cache.dat")
(.delete (java.io.File. path))
(def store (disk/open-disk-storage path 128 true))
(def ref (neb/store idx-mem store))
(def idx-disk (neb/restore store ref))

(println "\n=== Testing exact match: 'word0' ===")
(let [result-mem (neb/search idx-mem "word0")
      result-disk (neb/search idx-disk "word0")]
  (println "In-Memory found:" (count result-mem) "docs")
  (println "Disk found:" (count result-disk) "docs"))

(println "\n=== Testing substring match: 'wor' ===")
(let [result-mem (neb/search idx-mem "wor")
      result-disk (neb/search idx-disk "wor")]
  (println "In-Memory found:" (count result-mem) "docs")
  (println "Disk found:" (count result-disk) "docs"))

;; Debug: Check if word cache is being built
(println "\n=== Checking word cache ===")
(let [word-cache-atom (:word-cache (meta idx-disk))]
  (println "Word cache atom exists:" (not (nil? word-cache-atom)))
  (when word-cache-atom
    (let [cache @word-cache-atom]
      (println "Word cache value:" (if cache (str "Set with " (count cache) " words") "nil"))
      (when cache
        (println "Sample words:" (take 10 cache))
        (println "Words containing 'wor':" (filter #(clojure.string/includes? % "wor") cache))))))

(println "\n=== Checking inverted index ===")
(let [inverted (:inverted (meta idx-disk))]
  (println "Inverted index type:" (type inverted))
  (when (instance? nebsearch.btree.DurableBTree inverted)
    (require '[nebsearch.btree :as bt])
    (println "Inverted entries count:" (count (bt/bt-seq inverted)))
    (println "First 5 entries:")
    (doseq [entry (take 5 (bt/bt-seq inverted))]
      (println "  Entry:" entry "| word:" (.-word entry) "| doc-id:" (.-doc-id entry)))))

(.delete (java.io.File. path))
(System/exit 0)
