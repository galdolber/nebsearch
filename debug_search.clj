(require '[nebsearch.core :as neb])
(require '[nebsearch.btree :as bt])

(println "Debug Search Test")
(println "==================\n")

(def path "/tmp/debug-search.dat")
(doseq [suffix [""  ".meta" ".versions"]]
  (.delete (java.io.File. (str path suffix))))

(def idx (neb/init {:durable? true :index-path path}))
(def idx2 (neb/search-add idx {"doc1" "hello world"}))

(println "After adding doc1:")
(println "  Index string:" (pr-str (:index idx2)))
(println "  Index length:" (count (:index idx2)))
(println "  IDs:" (:ids idx2))
(println "  B-tree entries:" (bt/bt-seq (:data idx2)))

(println "\nSearching for 'hello':")
;; Manually trace through search logic
(def index (:index idx2))
(def data (:data idx2))
(println "  Looking for 'hello' in index:" (pr-str index))
(def positions (vec (for [i (range (count index))
                          :when (.startsWith (subs index i) "hello")]
                     i)))
(println "  Positions found:" positions)

(when (seq positions)
  (def pos (first positions))
  (println "  First position:" pos)
  (println "  Searching B-tree for entries <= [" (inc pos) " nil]")
  (def all-entries (bt/bt-seq data))
  (println "  All B-tree entries:" all-entries)
  (def matching (filter #(<= (compare % [(inc pos) nil]) 0) all-entries))
  (println "  Matching entries:" matching)
  (def first-match (first (reverse matching)))
  (println "  First match:" first-match)
  (println "  Extracting ID from second element:" (second first-match)))

(def result (neb/search idx2 "hello"))
(println "  Result:" result)

(neb/close idx2)
(System/exit 0)
