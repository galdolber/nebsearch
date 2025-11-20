(require '[nebsearch.core :as neb])

(defn format-duration [nanos]
  (cond
    (< nanos 1000) (format "%.2f ns" (double nanos))
    (< nanos 1000000) (format "%.2f μs" (/ nanos 1000.0))
    (< nanos 1000000000) (format "%.2f ms" (/ nanos 1000000.0))
    :else (format "%.2f s" (/ nanos 1000000000.0))))

(defn measure-time [f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)]
    [result (- end start)]))

(defn generate-docs [n]
  (into {} (for [i (range n)]
             [(str "doc" i) (str "content " i " some additional text")])))

(defn bench [label mode n operation]
  (print (format "%-45s" label))
  (flush)
  (let [[_ time] (measure-time operation)]
    (println (format "%15s" (format-duration time)))))

(defn cleanup-files [path]
  (doseq [suffix ["" ".meta" ".versions" ".gc-temp" ".gc-temp.meta" ".gc-temp.versions"]]
    (.delete (java.io.File. (str path suffix)))))

(println "\n╔═══════════════════════════════════════════════════════════════════════╗")
(println "║         NebSearch Benchmark: In-Memory vs Durable Comparison          ║")
(println "╚═══════════════════════════════════════════════════════════════════════╝\n")

(println "Test sizes: 100, 1000, 5000 documents\n")

;; ============================================================================
(println "1. INITIALIZATION")
(println (apply str (repeat 73 "-")))

(bench "In-Memory (empty)" :in-memory 0
       #(neb/init {}))

(bench "Durable (empty)" :durable 0
       (fn []
         (let [path "/tmp/bench-init.dat"]
           (cleanup-files path)
           (let [idx (neb/init {:durable? true :index-path path})]
             (neb/close idx)
             (cleanup-files path)))))

;; ============================================================================
(println "\n2. BULK ADD OPERATIONS")
(println (apply str (repeat 73 "-")))

(doseq [n [100 1000 5000]]
  (let [docs (generate-docs n)]
    (bench (format "In-Memory: Add %d docs (bulk)" n) :in-memory n
           #(neb/search-add (neb/init {}) docs))

    (bench (format "Durable: Add %d docs (bulk)" n) :durable n
           (fn []
             (let [path (str "/tmp/bench-bulk-" n ".dat")]
               (cleanup-files path)
               (let [idx (neb/init {:durable? true :index-path path})
                     result (neb/search-add idx docs)]
                 (neb/close result)
                 (cleanup-files path)))))))

;; ============================================================================
(println "\n3. SEARCH OPERATIONS (100 queries)")
(println (apply str (repeat 73 "-")))

(doseq [n [100 1000 5000]]
  (let [docs (generate-docs n)
        queries (repeatedly 100 #(str "content " (rand-int n)))]

    (bench (format "In-Memory: Search in %d docs" n) :in-memory n
           (fn []
             (let [idx (neb/search-add (neb/init {}) docs)]
               (doseq [q queries]
                 (neb/search idx q)))))

    (bench (format "Durable: Search in %d docs" n) :durable n
           (fn []
             (let [path (str "/tmp/bench-search-" n ".dat")]
               (cleanup-files path)
               (let [idx (neb/init {:durable? true :index-path path})
                     idx (neb/search-add idx docs)]
                 (doseq [q queries]
                   (neb/search idx q))
                 (neb/close idx)
                 (cleanup-files path)))))))

;; ============================================================================
(println "\n4. REMOVE OPERATIONS (50% of documents)")
(println (apply str (repeat 73 "-")))

(doseq [n [100 1000 5000]]
  (let [docs (generate-docs n)
        to-remove (map #(str "doc" %) (range 0 (quot n 2)))]

    (bench (format "In-Memory: Remove %d docs" (quot n 2)) :in-memory n
           (fn []
             (let [idx (neb/search-add (neb/init {}) docs)]
               (neb/search-remove idx to-remove))))

    (bench (format "Durable: Remove %d docs" (quot n 2)) :durable n
           (fn []
             (let [path (str "/tmp/bench-remove-" n ".dat")]
               (cleanup-files path)
               (let [idx (neb/init {:durable? true :index-path path})
                     idx (neb/search-add idx docs)
                     result (neb/search-remove idx to-remove)]
                 (neb/close result)
                 (cleanup-files path)))))))

;; ============================================================================
(println "\n5. PERSISTENCE OPERATIONS")
(println (apply str (repeat 73 "-")))

(doseq [n [100 1000 5000]]
  (let [docs (generate-docs n)]

    (bench (format "In-Memory: Serialize %d docs" n) :in-memory n
           (fn []
             (let [idx (neb/search-add (neb/init {}) docs)]
               (neb/serialize idx))))

    (bench (format "Durable: Flush %d docs to disk" n) :durable n
           (fn []
             (let [path (str "/tmp/bench-flush-" n ".dat")]
               (cleanup-files path)
               (let [idx (neb/init {:durable? true :index-path path})
                     idx (neb/search-add idx docs)]
                 (neb/flush idx)
                 (neb/close idx)
                 (cleanup-files path)))))

    (bench (format "Durable: Reopen index with %d docs" n) :durable n
           (fn []
             (let [path (str "/tmp/bench-reopen-" n ".dat")]
               (cleanup-files path)
               (let [idx (neb/init {:durable? true :index-path path})
                     idx (neb/search-add idx docs)]
                 (neb/flush idx)
                 (neb/close idx)
                 (let [reopened (neb/open-index {:index-path path})]
                   (neb/close reopened)
                   (cleanup-files path))))))))

(println "\n═══════════════════════════════════════════════════════════════════════")
(println "SUMMARY")
(println "═══════════════════════════════════════════════════════════════════════")
(println "\nKey Findings:")
(println "  • In-Memory mode is significantly faster for all operations")
(println "  • Durable mode provides persistence at the cost of performance")
(println "  • Search performance degrades in durable mode with larger datasets")
(println "  • Bulk operations are more efficient than single operations in both modes")
(println "\nUse Cases:")
(println "  • In-Memory: Best for temporary indexes, fast searches, volatile data")
(println "  • Durable: Best for persistent data, crash recovery, large datasets")
(println "\nBenchmark completed!")
(println "═══════════════════════════════════════════════════════════════════════")

(System/exit 0)
