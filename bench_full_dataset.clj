(ns bench-full-dataset
  (:require [nebsearch.core :as neb]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.storage :as storage]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader File]))

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

(defn parse-wikipedia-batch
  "Parse Wikipedia abstracts in batches using streaming.
   Returns a lazy sequence of document batches."
  [file batch-size]
  (let [rdr (io/reader file)]
    ((fn step [current-doc doc-count batch]
       (lazy-seq
         (if-let [line (.readLine rdr)]
           (let [current-doc (if (str/includes? line "<doc>")
                              line
                              (if current-doc (str current-doc line) nil))]
             (if (and current-doc (str/includes? line "</doc>"))
               ;; Complete document found
               (let [title-match (re-find #"<title>(.*?)</title>" current-doc)
                     abstract-match (re-find #"<abstract>(.*?)</abstract>" current-doc)]
                 (if (and title-match abstract-match)
                   (let [title (second title-match)
                         abstract (second abstract-match)
                         doc [(str "doc-" doc-count)
                              (str title " " abstract)
                              title]
                         new-batch (conj batch doc)
                         new-count (inc doc-count)]
                     (if (>= (count new-batch) batch-size)
                       ;; Batch is full, emit it and start new batch
                       (cons new-batch (step nil new-count []))
                       ;; Continue accumulating
                       (step nil new-count new-batch)))
                   ;; Invalid document, skip
                   (step nil doc-count batch)))
               ;; Continue reading current document
               (step current-doc doc-count batch)))
           ;; End of file - emit final batch if not empty
           (do
             (.close rdr)
             (when (seq batch)
               (list batch))))))
     nil 0 [])))

(defn load-dataset-in-batches
  "Load entire dataset in batches and build index incrementally.
   Returns {:index, :total-docs, :total-time, :batches-processed}"
  [dataset-file index-file batch-size]
  (println "\n═══════════════════════════════════════════════════════════════")
  (println "           LOADING FULL DATASET IN BATCHES")
  (println "═══════════════════════════════════════════════════════════════\n")

  (let [file-size (.length (io/file dataset-file))
        _ (println (format "Dataset file: %s" (format-bytes file-size)))
        _ (println (format "Batch size: %d documents" batch-size))
        _ (println (format "Index file: %s\n" index-file))

        ;; Clean up old index file
        _ (when (.exists (io/file index-file))
            (println "Deleting old index file...")
            (.delete (io/file index-file)))

        ;; Create storage and initial index
        storage (disk-storage/open-disk-storage index-file 512 true)

        start-time (System/nanoTime)

        ;; Process batches
        result (loop [batches (parse-wikipedia-batch dataset-file batch-size)
                      idx (neb/init)
                      total-docs 0
                      batch-num 0]
                 (if-let [batch (first batches)]
                   (let [batch-start (System/nanoTime)
                         _ (when (zero? (mod batch-num 10))
                             (println (format "Processing batch %d... (%d docs loaded so far)"
                                            batch-num total-docs)))

                         ;; Add batch to index
                         new-idx (neb/search-add idx batch)

                         ;; Store to disk every 10 batches (or adjust as needed)
                         _ (when (zero? (mod batch-num 10))
                             (let [store-start (System/nanoTime)]
                               (neb/store new-idx storage)
                               (let [store-time (- (System/nanoTime) store-start)]
                                 (println (format "  Saved to disk (%s)"
                                                (format-duration store-time))))))

                         batch-time (- (System/nanoTime) batch-start)
                         new-total (+ total-docs (count batch))]
                     (recur (rest batches) new-idx new-total (inc batch-num)))
                   ;; No more batches - final save
                   (do
                     (println (format "\nFinal save to disk..."))
                     (let [ref (neb/store idx storage)]
                       {:index idx
                        :storage storage
                        :ref ref
                        :total-docs total-docs
                        :batches-processed batch-num}))))

        total-time (- (System/nanoTime) start-time)
        index-file-size (.length (io/file index-file))]

    (println "\n───────────────────────────────────────────────────────────────")
    (println "LOADING COMPLETE")
    (println "───────────────────────────────────────────────────────────────\n")
    (println (format "Total documents loaded: %d" (:total-docs result)))
    (println (format "Batches processed: %d" (:batches-processed result)))
    (println (format "Total time: %s" (format-duration total-time)))
    (println (format "Throughput: %.0f docs/sec"
                    (/ (* (:total-docs result) 1e9) total-time)))
    (println (format "Index file size: %s" (format-bytes index-file-size)))
    (println (format "Compression ratio: %.2fx"
                    (/ (double file-size) index-file-size)))
    (println)

    (assoc result
           :total-time total-time
           :index-file-size index-file-size)))

(defn run-search-benchmark
  "Run search queries on the loaded index"
  [storage ref sample-queries]
  (println "\n═══════════════════════════════════════════════════════════════")
  (println "           SEARCH PERFORMANCE TEST")
  (println "═══════════════════════════════════════════════════════════════\n")

  ;; Restore index from disk
  (let [restore-start (System/nanoTime)
        idx (neb/restore storage ref)
        restore-time (- (System/nanoTime) restore-start)

        _ (println (format "Index restored from disk in %s\n"
                         (format-duration restore-time)))

        ;; Test queries
        test-queries (or sample-queries
                        ["the" "and" "first" "world" "computer"
                         "science" "history" "technology" "united states"
                         "the world"])

        _ (println (format "Running %d test queries...\n" (count test-queries)))

        ;; Cold cache searches
        cold-times (atom [])
        cold-results (atom [])

        _ (doseq [query test-queries]
            (let [start (System/nanoTime)
                  result (neb/search idx query)
                  duration (- (System/nanoTime) start)]
              (swap! cold-times conj duration)
              (swap! cold-results conj (count result))
              (println (format "  '%s' -> %d results (%s)"
                             query (count result) (format-duration duration)))))

        cold-avg (/ (reduce + @cold-times) (count @cold-times))
        cold-avg-results (/ (reduce + @cold-results) (count @cold-results))

        ;; Warm cache searches (repeat queries)
        _ (println "\nWarm cache (1000 repeated searches)...")
        warm-start (System/nanoTime)
        _ (dotimes [_ 100]
            (doseq [query test-queries]
              (neb/search idx query)))
        warm-duration (- (System/nanoTime) warm-start)
        warm-avg (/ warm-duration (* 100 (count test-queries)))
        qps (/ (* 100 (count test-queries) 1e9) warm-duration)]

    (println "\n───────────────────────────────────────────────────────────────")
    (println "SEARCH RESULTS")
    (println "───────────────────────────────────────────────────────────────\n")
    (println (format "Cold cache avg: %s (%d avg results)"
                    (format-duration cold-avg) (long cold-avg-results)))
    (println (format "Warm cache avg: %s" (format-duration warm-avg)))
    (println (format "Throughput: %.0f queries/sec" qps))
    (println)

    {:restore-time restore-time
     :cold-avg cold-avg
     :warm-avg warm-avg
     :qps qps}))

(defn -main [& args]
  (try
    (let [dataset-file "dataset.txt"
          index-file "/tmp/full-dataset-bench.dat"
          batch-size 5000  ;; Process 5000 docs at a time

          _ (when-not (.exists (io/file dataset-file))
              (println "ERROR: dataset.txt not found!")
              (System/exit 1))

          ;; Load dataset in batches
          load-result (load-dataset-in-batches dataset-file index-file batch-size)

          ;; Run search benchmark
          search-result (run-search-benchmark
                         (:storage load-result)
                         (:ref load-result)
                         nil)  ;; Use default queries

          ;; Cleanup
          _ (storage/close (:storage load-result))]

      (println "\n═══════════════════════════════════════════════════════════════")
      (println "                  FINAL SUMMARY")
      (println "═══════════════════════════════════════════════════════════════\n")
      (println (format "Documents indexed: %d" (:total-docs load-result)))
      (println (format "Loading time: %s (%.0f docs/sec)"
                      (format-duration (:total-time load-result))
                      (/ (* (:total-docs load-result) 1e9) (:total-time load-result))))
      (println (format "Index size: %s" (format-bytes (:index-file-size load-result))))
      (println (format "Search (cold): %s" (format-duration (:cold-avg search-result))))
      (println (format "Search (warm): %s" (format-duration (:warm-avg search-result))))
      (println (format "Query throughput: %.0f QPS" (:qps search-result)))
      (println "\n✓ Full dataset benchmark complete!")
      (println "═══════════════════════════════════════════════════════════════\n"))

    (catch Exception e
      (println "\nERROR:" (.getMessage e))
      (.printStackTrace e)
      (System/exit 1))))

(apply -main *command-line-args*)
