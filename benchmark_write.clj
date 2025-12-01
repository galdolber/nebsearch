(require '[nebsearch.core :as neb])
(require '[nebsearch.disk-storage :as disk])
(require '[nebsearch.storage :as storage])

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
  (mapv (fn [i] [(str "doc" i) (str "content " i " some additional text") (str "Title " i)])
        (range n)))

(defn cleanup-files [path]
  (.delete (java.io.File. path)))

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
    (let [[idx time] (measure-time #(neb/search-add (neb/init) docs))]
      (println (format "In-Memory:  %12s  (%s per doc)"
                       (format-duration time)
                       (format-duration (quot time n)))))

    ;; Disk storage with timing breakdown
    (let [start-total (System/nanoTime)
          store (disk/open-disk-storage path 128 true)
          after-init (System/nanoTime)

          ;; Time the add operation
          idx (neb/search-add (neb/init) docs)
          after-add (System/nanoTime)

          ;; Time the store operation
          ref (neb/store idx store)
          after-store (System/nanoTime)

          ;; Save to disk
          _ (storage/save store)
          after-save (System/nanoTime)

          total-time (- after-save start-total)
          init-time (- after-init start-total)
          add-time (- after-add after-init)
          store-time (- after-store after-add)
          save-time (- after-save after-store)]

      (println (format "Disk:       %12s  (%s per doc)"
                       (format-duration (+ store-time save-time))
                       (format-duration (quot (+ store-time save-time) n))))
      (println (format "  Init:     %12s" (format-duration init-time)))
      (println (format "  Add:      %12s" (format-duration add-time)))
      (println (format "  Store:    %12s" (format-duration store-time)))
      (println (format "  Save:     %12s" (format-duration save-time)))
      (println (format "  Total:    %12s" (format-duration total-time)))

      (storage/close store)
      (cleanup-files path))))

(println "\n════════════════════════════════════════")
(println "Benchmark complete!")
(println "════════════════════════════════════════\n")

(System/exit 0)
