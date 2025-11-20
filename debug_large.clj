(require '[nebsearch.core :as neb])
(require '[nebsearch.btree :as bt])

(def path "/tmp/debug-large.dat")
(def idx (neb/init {:durable? true :index-path path}))

;; Add 100 documents with "content 5"
(def docs (into {} (for [i (range 100)]
                     [(str "doc" (* i 10)) (str "content " 5)])))

(println "Adding" (count docs) "documents...")
(def idx2 (neb/search-add idx docs))

(println "\nSearch results for 'content 5':")
(def results (neb/search idx2 "content 5"))
(println "  Found" (count results) "documents")
(println "  Results:" (sort results))

(println "\nChecking B-tree contents...")
(def all-entries (vec (bt/bt-seq (:data idx2))))
(println "  Total B-tree entries:" (count all-entries))
(println "  First 10 entries:" (take 10 all-entries))
(println "  Last 10 entries:" (take-last 10 all-entries))

(println "\nChecking index string...")
(println "  Index length:" (count (:index idx2)))
(println "  Index (first 100 chars):" (subs (:index idx2) 0 (min 100 (count (:index idx2)))))

(println "\nChecking ids map...")
(println "  IDs count:" (count (:ids idx2)))
(println "  First 10 IDs:" (take 10 (sort (keys (:ids idx2)))))

(neb/close idx2)
(System/exit 0)
