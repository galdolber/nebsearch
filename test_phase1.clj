(require '[nebsearch.core :as neb])
(require '[nebsearch.btree :as bt])

(println "Testing Phase 1: Text in B-tree")
(println "=================================\n")

;; Test 1: Basic add and search
(println "Test 1: Add and search with durable index")
(def path "/tmp/test-phase1.dat")
(doseq [suffix ["" ".meta" ".versions"]]
  (.delete (java.io.File. (str path suffix))))

(def idx (neb/init {:durable? true :index-path path}))
(println "  Created index")

(def idx2 (neb/search-add idx {"doc1" "hello world"
                                "doc2" "test data"
                                "doc3" "hello test"}))
(println "  Added 3 documents")

;; Check B-tree entries
(println "\n  B-tree entries:")
(doseq [entry (bt/bt-seq (:data idx2))]
  (println "   " entry))

;; Test search
(println "\n  Search 'hello':" (neb/search idx2 "hello"))
(println "  Search 'test':" (neb/search idx2 "test"))
(println "  Search 'world':" (neb/search idx2 "world"))
(println "  Search 'hello test':" (neb/search idx2 "hello test"))

;; Test 2: Flush and reopen
(println "\nTest 2: Flush and reopen")
(neb/flush idx2)
(println "  Flushed index")

(neb/close idx2)
(println "  Closed index")

(def idx3 (neb/open-index {:index-path path}))
(println "  Reopened index")

(println "  Document count:" (count (:ids idx3)))
(println "  Search 'hello':" (neb/search idx3 "hello"))
(println "  Search 'test':" (neb/search idx3 "test"))

;; Test 3: Remove
(println "\nTest 3: Remove document")
(def idx4 (neb/search-remove idx3 ["doc1"]))
(println "  Removed doc1")
(println "  Search 'hello':" (neb/search idx4 "hello"))
(println "  Search 'world':" (neb/search idx4 "world"))

(neb/close idx4)
(println "\nPhase 1 basic tests completed!")
(System/exit 0)
