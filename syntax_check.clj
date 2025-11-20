(try
  (load-file "src/nebsearch/btree.cljc")
  (println "Successfully loaded btree.cljc")
  (catch Exception e
    (println "Error loading btree.cljc:")
    (println (.getMessage e))
    (.printStackTrace e)))
