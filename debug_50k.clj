(require '[nebsearch.core :as neb])
(require '[nebsearch.disk-storage :as disk])

(println "Creating index with 50K docs (this will take a moment)...")
(def docs (mapv (fn [i]
                  [(str "doc" i)
                   (str "word" (mod i 100) " common content document " (when (< i 1000) (str "rare" i)))
                   (str "Title " i)])
                (range 50000)))

(def path "/tmp/debug-50k.dat")
(.delete (java.io.File. path))
(def store (disk/open-disk-storage path 128 true))
(println "Building index...")
(def idx-mem (neb/search-add (neb/init) docs))
(println "Storing to disk...")
(def ref (neb/store idx-mem store))
(def idx-disk (neb/restore store ref))

(println "\n=== Test 1: Exact match 'word50' ===")
(def result1 (neb/search idx-disk "word50"))
(println "Found:" (count result1) "docs (expected ~500)")

(println "\n=== Test 2: Substring 'wor' ===")
(def result2 (neb/search idx-disk "wor"))
(println "Found:" (count result2) "docs (expected 50000)")

;; Debug word cache
(let [word-cache-atom (:word-cache (meta idx-disk))]
  (println "\n=== Word Cache Debug ===")
  (println "Cached?" (not (nil? @word-cache-atom)))
  (when @word-cache-atom
    (let [cache @word-cache-atom
          wor-words (filter #(clojure.string/includes? % "wor") cache)]
      (println "Total unique words:" (count cache))
      (println "Words containing 'wor':" (count wor-words))
      (println "Sample 'wor' words:" (take 10 wor-words)))))

(println "\n=== Testing one word directly ===")
(require '[nebsearch.btree :as bt])
(let [inverted (:inverted (meta idx-disk))
      test-word "word50"
      word-hash (unchecked-long (.hashCode test-word))
      matches (bt/bt-range inverted word-hash word-hash)]
  (println "Searching for:" test-word)
  (println "Hash:" word-hash)
  (println "Matches found:" (count matches))
  (println "First 5 matches:" (take 5 matches)))

(.delete (java.io.File. path))
(System/exit 0)
