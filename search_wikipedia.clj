#!/usr/bin/env bb
;; Interactive Wikipedia search with lazy disk loading

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[nebsearch.core :as neb]
         '[nebsearch.disk-storage :as disk])

(defn format-time [nanos]
  (cond
    (< nanos 1000) (format "%.0f ns" (double nanos))
    (< nanos 1000000) (format "%.2f μs" (/ nanos 1000.0))
    (< nanos 1000000000) (format "%.2f ms" (/ nanos 1000000.0))
    :else (format "%.2f s" (/ nanos 1000000000.0))))

(defn load-index [index-path]
  (println "\n" (str/join "" (repeat 80 "=")))
  (println "Loading Wikipedia index from disk...")
  (println (str/join "" (repeat 80 "=")))
  (println "Index file:" index-path)

  (let [file (io/file index-path)
        ref-file (io/file (str index-path ".ref"))]
    (cond
      (not (.exists file))
      (do
        (println "\n❌ Error: Index file not found:" index-path)
        (println "\nPlease build the index first:")
        (println "  clj build_wikipedia_index.clj")
        (System/exit 1))

      (not (.exists ref-file))
      (do
        (println "\n❌ Error: Reference file not found:" (str index-path ".ref"))
        (println "\nPlease rebuild the index:")
        (println "  clj build_wikipedia_index.clj")
        (System/exit 1))

      :else
      (let [file-size (.length file)]
        (println (format "Index size: %.2f MB" (/ file-size 1024.0 1024.0)))

        (println "\nLoading index (lazy, doesn't load all data)...")
        (let [start-load (System/nanoTime)
              storage (disk/open-disk-storage index-path 256 false)
              ref (read-string (slurp (str index-path ".ref")))
              idx (neb/restore storage ref)
              end-load (System/nanoTime)
              load-time (- end-load start-load)]

          (println (format "✓ Index loaded in %s" (format-time load-time)))
          (println "\nNote: The index is lazy-loaded - only metadata is in memory.")
          (println "      Actual data will be loaded on-demand during searches.")

          {:idx idx
           :storage storage
           :load-time-ns load-time
           :file-size-mb (/ file-size 1024.0 1024.0)})))))

(defn search-and-display [idx query]
  (println "\n" (str/join "" (repeat 80 "-")))
  (println "Searching for:" (pr-str query))
  (println (str/join "" (repeat 80 "-")))

  ;; First search (cold cache)
  (println "\n[COLD CACHE - First-time search]")
  (let [start-cold (System/nanoTime)
        results-cold (neb/search idx query)
        end-cold (System/nanoTime)
        cold-time (- end-cold start-cold)]

    (println (format "⏱  Search time: %s" (format-time cold-time)))
    (println (format "📊 Results found: %d documents" (count results-cold)))

    ;; Second search (warm cache)
    (println "\n[WARM CACHE - Repeated search]")
    (let [start-warm (System/nanoTime)
          results-warm (neb/search idx query)
          end-warm (System/nanoTime)
          warm-time (- end-warm start-warm)]

      (println (format "⏱  Search time: %s" (format-time warm-time)))
      (println (format "🔥 Cache speedup: %.1fx faster" (/ (double cold-time) warm-time)))

      ;; Display results
      (if (empty? results-warm)
        (println "\n❌ No results found")

        (do
          (println (format "\n📄 Showing top %d results:" (min 10 (count results-warm))))
          (println (str/join "" (repeat 80 "-")))

          (doseq [[i doc-id] (map-indexed vector (take 10 results-warm))]
            (println (format "%d. %s" (inc i) doc-id)))

          (when (> (count results-warm) 10)
            (println (format "\n... and %d more results" (- (count results-warm) 10))))))

      {:query query
       :cold-time-ns cold-time
       :warm-time-ns warm-time
       :results-count (count results-warm)
       :speedup (/ (double cold-time) warm-time)})))

(defn interactive-search [idx]
  (println "\n" (str/join "" (repeat 80 "=")))
  (println "🔍 INTERACTIVE WIKIPEDIA SEARCH")
  (println (str/join "" (repeat 80 "=")))
  (println "\nCommands:")
  (println "  - Type any search query to search")
  (println "  - Type 'quit' or 'exit' to exit")
  (println "  - Type 'stats' to show statistics")
  (println)

  (loop [queries []]
    (print "Search> ")
    (flush)
    (if-let [input (read-line)]
      (let [trimmed (str/trim input)]
        (cond
          (empty? trimmed)
          (recur queries)

          (or (= trimmed "quit") (= trimmed "exit"))
          (do
            (println "\n👋 Goodbye!")
            queries)

          (= trimmed "stats")
          (do
            (println "\n" (str/join "" (repeat 80 "-")))
            (println "STATISTICS")
            (println (str/join "" (repeat 80 "-")))
            (println (format "Total searches: %d" (count queries)))
            (when (seq queries)
              (let [avg-cold (/ (reduce + (map :cold-time-ns queries)) (count queries))
                    avg-warm (/ (reduce + (map :warm-time-ns queries)) (count queries))
                    avg-speedup (/ (reduce + (map :speedup queries)) (count queries))]
                (println (format "Average cold cache time: %s" (format-time avg-cold)))
                (println (format "Average warm cache time: %s" (format-time avg-warm)))
                (println (format "Average cache speedup: %.1fx" avg-speedup))))
            (recur queries))

          :else
          (let [result (search-and-display idx trimmed)]
            (recur (conj queries result)))))

      ;; EOF (Ctrl+D)
      (do
        (println "\n👋 Goodbye!")
        queries))))

(defn -main [& args]
  (let [index-path (or (first args) "wikipedia.idx")
        query (second args)]

    (println "\nWikipedia Search - Lazy Disk Loading Demo")

    ;; Load index (lazy)
    (let [{:keys [idx storage load-time-ns file-size-mb]} (load-index index-path)]

      (println "\n" (str/join "" (repeat 80 "=")))
      (println "INDEX LOADING STATISTICS")
      (println (str/join "" (repeat 80 "=")))
      (println (format "📁 Index file size: %.2f MB" file-size-mb))
      (println (format "⏱  Load time: %s (lazy - only metadata)" (format-time load-time-ns)))
      (println (format "💾 Memory usage: Minimal (lazy loading)"))

      (if query
        ;; Single query mode
        (search-and-display idx query)

        ;; Interactive mode
        (let [queries (interactive-search idx)]
          (when (seq queries)
            (println "\n" (str/join "" (repeat 80 "=")))
            (println "SESSION SUMMARY")
            (println (str/join "" (repeat 80 "=")))
            (println (format "Total searches: %d" (count queries)))
            (let [avg-cold (/ (reduce + (map :cold-time-ns queries)) (count queries))
                  avg-warm (/ (reduce + (map :warm-time-ns queries)) (count queries))
                  avg-speedup (/ (reduce + (map :speedup queries)) (count queries))]
              (println (format "Average cold cache: %s" (format-time avg-cold)))
              (println (format "Average warm cache: %s" (format-time avg-warm)))
              (println (format "Average speedup: %.1fx" avg-speedup)))))))

    (System/exit 0)))

;; Run if executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

;; For clojure
(when-not (System/getProperty "babashka.file")
  (apply -main *command-line-args*))
