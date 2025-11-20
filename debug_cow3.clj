(require '[nebsearch.core :as neb])

;; Create durable index
(def idx (neb/init {:durable? true :index-path "/tmp/cow-test3.dat"}))

;; Add doc1
(def idx1 (neb/search-add idx {"doc1" "version one"}))
(println "\nAfter doc1:")
(println "  idx1 :index =" (pr-str (:index idx1)))
(println "  idx1 :ids =" (pr-str (:ids idx1)))
(println "  idx1 search 'version':" (neb/search idx1 "version"))

;; Add doc2
(def idx2 (neb/search-add idx1 {"doc2" "version two"}))
(println "\nAfter doc2:")
(println "  idx2 :index =" (pr-str (:index idx2)))
(println "  idx2 :ids =" (pr-str (:ids idx2)))
(println "  idx1 :index (should be unchanged) =" (pr-str (:index idx1)))
(println "  idx1 :ids (should be unchanged) =" (pr-str (:ids idx1)))
(println "  idx2 search 'version':" (neb/search idx2 "version"))
(println "  idx1 search 'version' (should still be doc1):" (neb/search idx1 "version"))

;; Clean up
(neb/close idx2)
(System/exit 0)
