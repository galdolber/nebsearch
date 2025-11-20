(require '[nebsearch.core :as neb])
(require '[nebsearch.btree :as bt])

(def path "/tmp/debug-large3.dat")
(def idx (neb/init {:durable? true :index-path path}))

;; Add 1000 documents exactly like the test
(def docs (into {} (for [i (range 1000)]
                     [(str "doc" i) (str "content " (mod i 10))])))

(println "Adding 1000 documents...")
(def idx2 (neb/search-add idx docs))

(println "\nB-tree contents:")
(doseq [entry (bt/bt-seq (:data idx2))]
  (println "  " entry))

(println "\n:index string length:" (count (:index idx2)))
(println ":index string (first 200 chars):" (subs (:index idx2) 0 (min 200 (count (:index idx2)))))

(println "\n:ids map (first 20 entries):")
(doseq [[k v] (take 20 (sort-by val (:ids idx2)))]
  (println "  " k "->" v))

(neb/close idx2)
(System/exit 0)
