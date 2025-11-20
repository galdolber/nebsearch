(require '[nebsearch.core :as neb])
(require '[clojure.string :as string])

;; Test tokenization
(println "Tokenizing 'content 5':")
(println "  Result:" (neb/default-splitter (neb/default-encoder "content 5")))

(def path "/tmp/debug-large4.dat")
(def idx (neb/init {:durable? true :index-path path}))

;; Add just a few documents to see what happens
(def docs {"doc5" "content 5"
           "doc15" "content 5"
           "doc25" "content 5"
           "doc0" "content 0"
           "doc1" "content 1"})

(println "\nAdding 5 documents...")
(def idx2 (neb/search-add idx docs))

(println "\n:index string:" (:index idx2))
(println "\nSearch for 'content 5':")
(def results (neb/search idx2 "content 5"))
(println "  Found" (count results) "documents")
(println "  Results:" (sort results))

(println "\nSearch for 'content':")
(def results-content (neb/search idx2 "content"))
(println "  Found" (count results-content) "documents")

(println "\nSearch for '5':")
(def results-5 (neb/search idx2 "5"))
(println "  Found" (count results-5) "documents")

(neb/close idx2)
(System/exit 0)
