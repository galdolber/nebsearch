(require '[nebsearch.core :as neb])
(require '[me.tonsky.persistent-sorted-set :as pss])

(def idx (neb/search-add (neb/init) {"doc1" "hello world"}))

(println "Index string:" (pr-str (:index idx)))
(println "Data:" (:data idx))
(println "IDs:" (:ids idx))

;; Find "hello" in index
(def hello-pos (clojure.string/index-of (:index idx) "hello"))
(println "\n'hello' found at position:" hello-pos)

;; Try rslice with inc
(println "rslice with [(inc " hello-pos ") nil]:")
(println (first (pss/rslice (:data idx) [(inc hello-pos) nil] nil)))

;; Try rslice without inc  
(println "rslice with [" hello-pos " nil]:")
(println (first (pss/rslice (:data idx) [hello-pos nil] nil)))
