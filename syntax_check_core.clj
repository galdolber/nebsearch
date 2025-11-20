(try
  (load-file "src/nebsearch/core.cljc")
  (println "Successfully loaded core.cljc")
  (catch Exception e
    (println "Error loading core.cljc:")
    (println (.getMessage e))
    (.printStackTrace e)))
