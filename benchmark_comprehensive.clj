(require '[nebsearch.core :as neb]
         '[nebsearch.memory-storage :as mem-storage]
         '[nebsearch.disk-storage :as disk-storage]
         '[nebsearch.storage :as storage])

(defn format-duration [nanos]
  (cond
    (< nanos 1000) (format "%.2f ns" (double nanos))
    (< nanos 1000000) (format "%.2f μs" (/ nanos 1000.0))
    (< nanos 1000000000) (format "%.2f ms" (/ nanos 1000000.0))
    :else (format "%.2f s" (/ nanos 1000000000.0))))

(defn format-bytes [bytes]
  (cond
    (< bytes 1024) (format "%d B" bytes)
    (< bytes (* 1024 1024)) (format "%.2f KB" (/ bytes 1024.0))
    (< bytes (* 1024 1024 1024)) (format "%.2f MB" (/ bytes 1024.0 1024.0))
    :else (format "%.2f GB" (/ bytes 1024.0 1024.0 1024.0))))

(defn measure-time [f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)]
    {:result result :duration (- end start)}))

(defn measure-memory [f]
  (System/gc)
  (Thread/sleep 100)
  (let [runtime (Runtime/getRuntime)
        before (.totalMemory runtime)
        before-free (.freeMemory runtime)
        before-used (- before before-free)
        result (f)
        _ (System/gc)
        _ (Thread/sleep 100)
        after (.totalMemory runtime)
        after-free (.freeMemory runtime)
        after-used (- after after-free)
        delta (- after-used before-used)]
    {:result result :memory-delta delta}))

(defn generate-docs [n]
  (mapv (fn [i]
          [(str "doc" i)
           (str "content number " i " some additional searchable text here")
           (str "Document " i)])
        (range n)))

(defn cleanup-temp-files []
  (doseq [suffix ["" ".tmp"]]
    (let [f (java.io.File. (str "/tmp/bench-disk" suffix))]
      (when (.exists f) (.delete f)))))

(println "\n╔═══════════════════════════════════════════════════════════════════════╗")
(println "║              NebSearch Comprehensive Performance Benchmark            ║")
(println "╚═══════════════════════════════════════════════════════════════════════╝\n")

(println "Comparing In-Memory vs Durable (Lazy) modes")
(println "Test sizes: 100, 1K, 10K, 100K documents\n")

;; ============================================================================
(println "═══════════════════════════════════════════════════════════════════════")
(println "1. INITIALIZATION")
(println "═══════════════════════════════════════════════════════════════════════")

(let [{:keys [duration]} (measure-time #(neb/init))]
  (println (format "In-Memory init:          %15s" (format-duration duration))))

(let [{:keys [duration]} (measure-time #(mem-storage/create-memory-storage))]
  (println (format "Memory Storage init:     %15s" (format-duration duration))))

(cleanup-temp-files)
(let [{:keys [duration]} (measure-time #(disk-storage/open-disk-storage "/tmp/bench-disk" 128 true))]
  (println (format "Disk Storage init:       %15s" (format-duration duration)))
  (storage/close (:result (measure-time #(disk-storage/open-disk-storage "/tmp/bench-disk" 128 false)))))

;; ============================================================================
(println "\n═══════════════════════════════════════════════════════════════════════")
(println "2. BULK ADD OPERATIONS (adding documents to empty index)")
(println "═══════════════════════════════════════════════════════════════════════\n")

(doseq [n [100 1000 10000 100000]]
  (println (format "--- %d documents ---" n))
  (let [docs (generate-docs n)]

    ;; In-Memory
    (let [{:keys [duration]} (measure-time
                              (fn []
                                (reduce (fn [idx doc]
                                         (neb/search-add idx [doc]))
                                       (neb/init)
                                       docs)))]
      (println (format "  In-Memory:             %15s  (%.0f docs/sec)"
                      (format-duration duration)
                      (/ (* n 1e9) duration))))

    ;; Durable with Memory Storage
    (let [storage (mem-storage/create-memory-storage)
          {:keys [duration result]} (measure-time
                                     (fn []
                                       (let [idx (reduce (fn [idx doc]
                                                          (neb/search-add idx [doc]))
                                                        (neb/init)
                                                        docs)]
                                         (neb/store idx storage))))]
      (println (format "  Durable (Mem Store):   %15s  (%.0f docs/sec)"
                      (format-duration duration)
                      (/ (* n 1e9) duration)))
      (let [stats (storage/storage-stats storage)]
        (println (format "    Storage: %d nodes, %s"
                        (:node-count stats)
                        (format-bytes (:size-bytes stats))))))

    ;; Durable with Disk Storage (only up to 10K for speed)
    (when (<= n 10000)
      (cleanup-temp-files)
      (let [storage (disk-storage/open-disk-storage "/tmp/bench-disk" 128 true)
            {:keys [duration result]} (measure-time
                                       (fn []
                                         (let [idx (reduce (fn [idx doc]
                                                            (neb/search-add idx [doc]))
                                                          (neb/init)
                                                          docs)]
                                           (neb/store idx storage))))]
        (println (format "  Durable (Disk Store):  %15s  (%.0f docs/sec)"
                        (format-duration duration)
                        (/ (* n 1e9) duration)))
        (let [stats (storage/storage-stats storage)]
          (println (format "    Storage: %s on disk"
                          (format-bytes (:file-size stats)))))
        (storage/close storage))))
  (println))

;; ============================================================================
(println "═══════════════════════════════════════════════════════════════════════")
(println "3. SEARCH OPERATIONS (100 queries on populated index)")
(println "═══════════════════════════════════════════════════════════════════════\n")

(doseq [n [100 1000 10000 100000]]
  (println (format "--- %d documents ---" n))
  (let [docs (generate-docs n)
        queries (repeatedly 100 #(str "content number " (rand-int n)))]

    ;; In-Memory
    (let [idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                     (neb/init)
                     docs)
          {:keys [duration]} (measure-time
                              (fn []
                                (doseq [q queries]
                                  (neb/search idx q))))]
      (println (format "  In-Memory:             %15s  (%.0f queries/sec)"
                      (format-duration duration)
                      (/ (* 100 1e9) duration))))

    ;; Durable with Memory Storage
    (let [storage (mem-storage/create-memory-storage)
          idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                     (neb/init)
                     docs)
          ref (neb/store idx storage)
          idx-lazy (neb/restore storage ref)
          {:keys [duration]} (measure-time
                              (fn []
                                (doseq [q queries]
                                  (neb/search idx-lazy q))))]
      (println (format "  Durable (Mem Store):   %15s  (%.0f queries/sec)"
                      (format-duration duration)
                      (/ (* 100 1e9) duration))))

    ;; Durable with Disk Storage (only up to 10K)
    (when (<= n 10000)
      (cleanup-temp-files)
      (let [storage (disk-storage/open-disk-storage "/tmp/bench-disk" 128 true)
            idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                       (neb/init)
                       docs)
            ref (neb/store idx storage)
            idx-lazy (neb/restore storage ref)
            {:keys [duration]} (measure-time
                                (fn []
                                  (doseq [q queries]
                                    (neb/search idx-lazy q))))]
        (println (format "  Durable (Disk Store):  %15s  (%.0f queries/sec)"
                        (format-duration duration)
                        (/ (* 100 1e9) duration)))
        (storage/close storage))))
  (println))

;; ============================================================================
(println "═══════════════════════════════════════════════════════════════════════")
(println "4. UPDATE OPERATIONS (updating 10% of documents)")
(println "═══════════════════════════════════════════════════════════════════════\n")

(doseq [n [100 1000 10000]]
  (println (format "--- %d documents, updating %d ---" n (quot n 10)))
  (let [docs (generate-docs n)
        updates (mapv (fn [i]
                       [(str "doc" i)
                        (str "UPDATED content " i)
                        (str "Updated " i)])
                     (range 0 (quot n 10)))]

    ;; In-Memory
    (let [idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                     (neb/init)
                     docs)
          {:keys [duration]} (measure-time
                              (fn []
                                (neb/search-add idx updates)))]
      (println (format "  In-Memory:             %15s" (format-duration duration))))

    ;; Durable with Memory Storage
    (let [storage (mem-storage/create-memory-storage)
          idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                     (neb/init)
                     docs)
          ref (neb/store idx storage)
          idx-lazy (neb/restore storage ref)
          {:keys [duration]} (measure-time
                              (fn []
                                (let [idx2 (neb/search-add idx-lazy updates)]
                                  (neb/store idx2 storage))))]
      (println (format "  Durable (Mem Store):   %15s" (format-duration duration)))))
  (println))

;; ============================================================================
(println "═══════════════════════════════════════════════════════════════════════")
(println "5. DELETE OPERATIONS (removing 10% of documents)")
(println "═══════════════════════════════════════════════════════════════════════\n")

(doseq [n [100 1000 10000]]
  (println (format "--- %d documents, deleting %d ---" n (quot n 10)))
  (let [docs (generate-docs n)
        to-delete (mapv #(str "doc" %) (range 0 (quot n 10)))]

    ;; In-Memory
    (let [idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                     (neb/init)
                     docs)
          {:keys [duration]} (measure-time
                              (fn []
                                (neb/search-remove idx to-delete)))]
      (println (format "  In-Memory:             %15s" (format-duration duration))))

    ;; Durable with Memory Storage
    (let [storage (mem-storage/create-memory-storage)
          idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                     (neb/init)
                     docs)
          ref (neb/store idx storage)
          idx-lazy (neb/restore storage ref)
          {:keys [duration]} (measure-time
                              (fn []
                                (let [idx2 (neb/search-remove idx-lazy to-delete)]
                                  (neb/store idx2 storage))))]
      (println (format "  Durable (Mem Store):   %15s" (format-duration duration)))))
  (println))

;; ============================================================================
(println "═══════════════════════════════════════════════════════════════════════")
(println "6. STORE/RESTORE OPERATIONS")
(println "═══════════════════════════════════════════════════════════════════════\n")

(doseq [n [100 1000 10000 100000]]
  (println (format "--- %d documents ---" n))
  (let [docs (generate-docs n)
        idx (reduce (fn [idx doc] (neb/search-add idx [doc]))
                   (neb/init)
                   docs)]

    ;; Store to Memory Storage
    (let [storage (mem-storage/create-memory-storage)
          {:keys [duration]} (measure-time
                              (fn []
                                (neb/store idx storage)))]
      (println (format "  Store (Memory):        %15s" (format-duration duration)))

      ;; Restore
      (let [ref (neb/store idx storage)
            {:keys [duration]} (measure-time
                                (fn []
                                  (neb/restore storage ref)))]
        (println (format "  Restore (Memory):      %15s" (format-duration duration)))))

    ;; Store to Disk Storage (only up to 10K)
    (when (<= n 10000)
      (cleanup-temp-files)
      (let [storage (disk-storage/open-disk-storage "/tmp/bench-disk" 128 true)
            {:keys [duration]} (measure-time
                                (fn []
                                  (neb/store idx storage)))]
        (println (format "  Store (Disk):          %15s" (format-duration duration)))

        ;; Restore
        (let [ref (neb/store idx storage)
              {:keys [duration]} (measure-time
                                  (fn []
                                    (neb/restore storage ref)))]
          (println (format "  Restore (Disk):        %15s" (format-duration duration))))
        (storage/close storage))))
  (println))

;; ============================================================================
(println "═══════════════════════════════════════════════════════════════════════")
(println "7. MEMORY USAGE")
(println "═══════════════════════════════════════════════════════════════════════\n")

(doseq [n [100 1000 10000 100000]]
  (println (format "--- %d documents ---" n))
  (let [docs (generate-docs n)]

    ;; In-Memory
    (let [{:keys [memory-delta]} (measure-memory
                                  (fn []
                                    (reduce (fn [idx doc]
                                             (neb/search-add idx [doc]))
                                           (neb/init)
                                           docs)))]
      (println (format "  In-Memory:             %15s  (%.2f bytes/doc)"
                      (format-bytes memory-delta)
                      (/ (double memory-delta) n))))

    ;; Durable with Memory Storage
    (let [{:keys [memory-delta]} (measure-memory
                                  (fn []
                                    (let [storage (mem-storage/create-memory-storage)
                                          idx (reduce (fn [idx doc]
                                                       (neb/search-add idx [doc]))
                                                     (neb/init)
                                                     docs)
                                          ref (neb/store idx storage)]
                                      (neb/restore storage ref))))]
      (println (format "  Durable (Mem Store):   %15s  (%.2f bytes/doc)"
                      (format-bytes memory-delta)
                      (/ (double memory-delta) n)))))
  (println))

;; ============================================================================
(println "═══════════════════════════════════════════════════════════════════════")
(println "8. STRUCTURAL SHARING (COW) OVERHEAD")
(println "═══════════════════════════════════════════════════════════════════════\n")

(let [n 10000
      docs (generate-docs n)
      storage (mem-storage/create-memory-storage)
      idx1 (reduce (fn [idx doc] (neb/search-add idx [doc]))
                  (neb/init)
                  docs)
      ref1 (neb/store idx1 storage)
      stats1 (storage/storage-stats storage)]

  (println (format "Initial: %d docs, %d nodes, %s"
                  n
                  (:node-count stats1)
                  (format-bytes (:size-bytes stats1))))

  ;; Add 10% more documents
  (let [more-docs (generate-docs-offset n (quot n 10))
        idx2 (neb/restore storage ref1)
        idx3 (reduce (fn [idx doc] (neb/search-add idx [doc]))
                    idx2
                    more-docs)
        ref2 (neb/store idx3 storage)
        stats2 (storage/storage-stats storage)]

    (println (format "After +10%%: %d docs, %d nodes (+%d), %s (+%s)"
                    (+ n (quot n 10))
                    (:node-count stats2)
                    (- (:node-count stats2) (:node-count stats1))
                    (format-bytes (:size-bytes stats2))
                    (format-bytes (- (:size-bytes stats2) (:size-bytes stats1)))))

    (let [sharing-pct (* 100.0 (/ (- (:node-count stats2) (:node-count stats1))
                                  (double (:node-count stats2))))]
      (println (format "Structural sharing: %.1f%% of nodes reused" (- 100.0 sharing-pct))))))

(defn generate-docs-offset [offset n]
  (mapv (fn [i]
          [(str "doc" (+ offset i))
           (str "content number " (+ offset i) " some additional searchable text here")
           (str "Document " (+ offset i))])
        (range n)))

;; ============================================================================
(println "\n═══════════════════════════════════════════════════════════════════════")
(println "SUMMARY & RECOMMENDATIONS")
(println "═══════════════════════════════════════════════════════════════════════\n")

(println "In-Memory Mode:")
(println "  ✓ Fastest for all operations")
(println "  ✓ Best for: fast searches, development, temporary data")
(println "  ✗ No persistence, higher memory usage\n")

(println "Durable Mode (Memory Storage):")
(println "  ✓ Good performance with persistence")
(println "  ✓ Structural sharing saves memory and I/O")
(println "  ✓ Best for: testing, small-medium datasets, versioning")
(println "  ✗ No disk persistence (data lost on restart)\n")

(println "Durable Mode (Disk Storage):")
(println "  ✓ Full persistence with crash recovery")
(println "  ✓ Can handle datasets larger than RAM")
(println "  ✓ Best for: production, large datasets, long-term storage")
(println "  ✗ Slower than in-memory (I/O overhead)\n")

(cleanup-temp-files)

(println "═══════════════════════════════════════════════════════════════════════")
(println "Benchmark completed!")
(println "═══════════════════════════════════════════════════════════════════════")
