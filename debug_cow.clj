(require '[nebsearch.core :as neb])
(require '[nebsearch.btree :as bt])

;; Create durable index
(def idx (neb/init {:durable? true :index-path "/tmp/cow-test.dat"}))
(println "Initial idx root:" (:root-offset (:data idx)))

;; Add doc1
(def idx1 (neb/search-add idx {"doc1" "version one"}))
(println "\nAfter doc1:")
(println "  idx1 root:" (:root-offset (:data idx1)))
(println "  idx1 search 'version':" (neb/search idx1 "version"))
(println "  idx1 index:" (:index idx1))

;; Add doc2
(def idx2 (neb/search-add idx1 {"doc2" "version two"}))
(println "\nAfter doc2:")
(println "  idx2 root:" (:root-offset (:data idx2)))
(println "  idx2 search 'version':" (neb/search idx2 "version"))
(println "  idx1 search 'version' (should still be doc1):" (neb/search idx1 "version"))
(println "  idx2 index:" (:index idx2))

;; Add doc3
(def idx3 (neb/search-add idx2 {"doc3" "version three"}))
(println "\nAfter doc3:")
(println "  idx3 root:" (:root-offset (:data idx3)))
(println "  idx3 search 'version':" (neb/search idx3 "version"))
(println "  idx2 search 'version' (should be doc1,doc2):" (neb/search idx2 "version"))
(println "  idx1 search 'version' (should still be doc1):" (neb/search idx1 "version"))
(println "  idx3 index:" (:index idx3))

;; Check all entries in B-tree
(println "\nAll entries in idx3:")
(doseq [entry (bt/bt-seq (:data idx3))]
  (println "  " entry))

;; Clean up
(neb/close idx3)
(System/exit 0)
