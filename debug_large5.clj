(require '[nebsearch.core :as neb])
(require '[clojure.string :as string])

(def path "/tmp/debug-large5.dat")
(def idx (neb/init {:durable? true :index-path path}))

;; Add 1000 documents
(def docs (into {} (for [i (range 1000)]
                     [(str "doc" i) (str "content " (mod i 10))])))

(println "Adding 1000 documents...")
(def idx2 (neb/search-add idx docs))

(println "\nLooking for documents with '5' in the index...")
(println "Index positions containing '5':")
(let [idx-str (:index idx2)]
  (loop [pos 0
         cnt 0]
    (if-let [i (string/index-of idx-str "5" pos)]
      (do
        (when (< cnt 20)  ; Show first 20
          (let [context-start (max 0 (- i 15))
                context-end (min (count idx-str) (+ i 5))
                context (subs idx-str context-start context-end)]
            (println "  Position" i ":" context)))
        (recur (inc i) (inc cnt)))
      (println "\nTotal occurrences of '5':" cnt))))

(neb/close idx2)
(System/exit 0)
