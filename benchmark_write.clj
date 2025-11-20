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

(defn cleanup-files [path]
  (doseq [suffix ["" ".meta" ".versions" ".gc-temp" ".gc-temp.meta" ".gc-temp.versions"]]
    (.delete (java.io.File. (str path suffix)))))

(println "\n════════════════════════════════════════")
(println "  NebSearch Write Performance Profiling")
(println "════════════════════════════════════════\n")

;; Test different batch sizes
(doseq [n [100 500 1000 2500 5000]]
  (println (format "\n--- %d documents ---" n))
  (let [docs (generate-docs n)
        path (str "/tmp/bench-write-" n ".dat")]

    (cleanup-files path)

    ;; In-Memory baseline
    (let [[idx time] (measure-time #(neb/search-add (neb/init {}) docs))]
      (println (format "In-Memory:  %12s  (%s per doc)"
                       (format-duration time)
                       (format-duration (quot time n)))))

    ;; Durable with timing breakdown
    (let [start-total (System/nanoTime)
          idx (neb/init {:durable? true :index-path path})
          after-init (System/nanoTime)

          ;; Time the add operation
          idx-with-docs (neb/search-add idx docs)
          after-add (System/nanoTime)

          total-time (- after-add start-total)
          init-time (- after-init start-total)
          add-time (- after-add after-init)]

      (println (format "Durable:    %12s  (%s per doc)"
                       (format-duration add-time)
                       (format-duration (quot add-time n))))
      (println (format "  Init:     %12s" (format-duration init-time)))
      (println (format "  Add:      %12s" (format-duration add-time)))
      (println (format "  Slowdown: %.1fx vs in-memory"
                       (/ (double add-time) (/ (double total-time) 1.0))))

      (neb/close idx-with-docs)
      (cleanup-files path))))

(println "\n════════════════════════════════════════")
(println "Benchmark complete!")
(println "════════════════════════════════════════\n")

(System/exit 0)
