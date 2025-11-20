(require '[nebsearch.core :as neb])

(def path "/tmp/debug-large2.dat")
(def idx (neb/init {:durable? true :index-path path}))

;; Add 1000 documents exactly like the test
(def docs (into {} (for [i (range 1000)]
                     [(str "doc" i) (str "content " (mod i 10))])))

(println "Adding 1000 documents...")
(def idx2 (neb/search-add idx docs))

(println "\nSearch results for 'content 5':")
(def results-5 (neb/search idx2 "content 5"))
(println "  Found" (count results-5) "documents (expected 100)")

(println "\nSearch results for 'content 7':")
(def results-7 (neb/search idx2 "content 7"))
(println "  Found" (count results-7) "documents (expected 100)")

(println "\nSearch results for 'content 0':")
(def results-0 (neb/search idx2 "content 0"))
(println "  Found" (count results-0) "documents (expected 100)")

(println "\n:ids map size:" (count (:ids idx2)))
(println "B-tree size:" (count (vec (seq (:data idx2)))))

(neb/close idx2)
(System/exit 0)
