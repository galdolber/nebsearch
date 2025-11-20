(require '[nebsearch.core :as neb])
(require '[nebsearch.btree :as bt])

;; Create durable index
(def idx (neb/init {:durable? true :index-path "/tmp/cow-test4.dat"}))

;; Add doc1
(def idx1 (neb/search-add idx {"doc1" "version one"}))
(println "\nAfter doc1:")
(println "  idx1 data root:" (:root-offset (:data idx1)))
(println "  idx1 bt-seq:" (vec (bt/bt-seq (:data idx1))))

;; Add doc2
(def idx2 (neb/search-add idx1 {"doc2" "version two"}))
(println "\nAfter doc2:")
(println "  idx2 data root:" (:root-offset (:data idx2)))
(println "  idx2 bt-seq:" (vec (bt/bt-seq (:data idx2))))
(println "  idx1 data root (should still be 256):" (:root-offset (:data idx1)))
(println "  idx1 bt-seq (should still be [[0 doc1]]):" (vec (bt/bt-seq (:data idx1))))

;; Clean up
(neb/close idx2)
(System/exit 0)
