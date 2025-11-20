(require '[nebsearch.core :as neb])

;; Test in-memory
(def idx-mem (neb/search-add (neb/init) {"doc1" "hello world"
                                          "doc2" "world peace"
                                          "doc3" "hello peace"}))

(println "In-memory search 'hello':" (neb/search idx-mem "hello"))
(println "In-memory search 'world':" (neb/search idx-mem "world"))
(println "In-memory index:" (:index idx-mem))
(println "In-memory data:" (take 10 (:data idx-mem)))
(println "In-memory ids:" (:ids idx-mem))

;; Test durable
(def idx-dur (neb/search-add (neb/init {:durable? true :index-path "/tmp/debug.dat"})
                               {"doc1" "hello world"
                                "doc2" "world peace"
                                "doc3" "hello peace"}))

(println "\nDurable search 'hello':" (neb/search idx-dur "hello"))
(println "Durable search 'world':" (neb/search idx-dur "world"))
(println "Durable index:" (:index idx-dur))
(require '[nebsearch.btree :as bt])
(println "Durable data:" (take 10 (bt/bt-seq (:data idx-dur))))
(println "Durable ids:" (:ids idx-dur))

(neb/close idx-dur)
