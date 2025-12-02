#!/usr/bin/env bb
;; Quick test to see what's in the index

(require '[clojure.java.io :as io]
         '[nebsearch.core :as neb]
         '[nebsearch.disk-storage :as disk])

(println "Testing Wikipedia index...")

;; Load index
(let [storage (disk/open-disk-storage "wikipedia.idx" 256 false)
      idx (neb/restore storage 0)]

  (println "\nIndex metadata:")
  (println "  Metadata:" (meta idx))

  (println "\nIndex stats:")
  (let [stats (neb/index-stats idx)]
    (println "  Total docs:" (:total-docs stats))
    (println "  Unique words:" (:unique-words stats))
    (println "  Total entries:" (:total-entries stats)))

  (println "\nTrying some searches:")
  (println "  Search 'wikipedia':" (count (neb/search idx "wikipedia")))
  (println "  Search 'computer':" (count (neb/search idx "computer")))
  (println "  Search 'the':" (count (neb/search idx "the")))
  (println "  Search 'a':" (count (neb/search idx "a")))

  (System/exit 0))
