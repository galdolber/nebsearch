(ns bench-bulk-load
  "Fast bulk loading: Build entire index in memory, then save to disk once.
   This avoids O(n²) inverted index rebuilds during loading."
  (:require [nebsearch.core :as neb]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.storage :as storage]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader File]))

(load-file "bench_full_dataset.clj")

(defn -main [& args]
  (try
    (let [dataset-file "dataset.txt"
          index-file "/tmp/bulk-load-bench.dat"

          _ (when-not (.exists (io/file dataset-file))
              (println "ERROR: dataset.txt not found!")
              (System/exit 1))

          file-size (.length (io/file dataset-file))
          _ (println "\n═══════════════════════════════════════════════════════════════")
          _ (println "           FAST BULK LOAD (Memory → Disk)")
          _ (println "═══════════════════════════════════════════════════════════════\n")
          _ (println (format "Dataset file: %s" (bench-full-dataset/format-bytes file-size)))
          _ (println "Strategy: Build entire index in memory, save to disk once\n")

          ;; Parse all documents using streaming (memory efficient)
          _ (println "Parsing dataset...")
          parse-start (System/nanoTime)
          docs (atom [])
          doc-count (atom 0)

          _ (with-open [rdr (io/reader dataset-file)]
              (let [current-doc (atom nil)]
                (doseq [line (line-seq rdr)]
                  (when (str/includes? line "<doc>")
                    (reset! current-doc line))
                  (when @current-doc
                    (when-not (= line @current-doc)
                      (swap! current-doc str line)))
                  (when (str/includes? line "</doc>")
                    (let [doc-text @current-doc
                          title-match (re-find #"<title>(.*?)</title>" doc-text)
                          abstract-match (re-find #"<abstract>(.*?)</abstract>" doc-text)]
                      (when (and title-match abstract-match)
                        (let [title (second title-match)
                              abstract (second abstract-match)]
                          (swap! docs conj [(str "doc-" @doc-count)
                                           (str title " " abstract)
                                           title])
                          (swap! doc-count inc)
                          (when (zero? (mod @doc-count 50000))
                            (println (format "  Parsed %d documents..." @doc-count))))))
                    (reset! current-doc nil)))))

          parse-time (- (System/nanoTime) parse-start)
          _ (println (format "Parsed %d documents in %s\n" (count @docs) (bench-full-dataset/format-duration parse-time)))

          ;; Build index in memory (single bulk operation)
          _ (println "Building index in memory...")
          build-start (System/nanoTime)
          idx (neb/search-add (neb/init) @docs)
          build-time (- (System/nanoTime) build-start)
          _ (println (format "Built index in %s (%.0f docs/sec)\n"
                           (bench-full-dataset/format-duration build-time)
                           (/ (* (count @docs) 1e9) build-time)))

          ;; Save to disk once
          _ (when (.exists (io/file index-file))
              (.delete (io/file index-file)))
          _ (println "Saving to disk...")
          storage (disk-storage/open-disk-storage index-file 512 true)
          save-start (System/nanoTime)
          ref (neb/store idx storage)
          save-time (- (System/nanoTime) save-start)
          index-size (.length (io/file index-file))

          _ (println (format "Saved to disk in %s" (bench-full-dataset/format-duration save-time)))
          _ (println (format "Index file size: %s" (bench-full-dataset/format-bytes index-size)))
          _ (println (format "Compression ratio: %.2fx\n" (/ (double file-size) index-size)))

          ;; Run search tests
          _ (println "═══════════════════════════════════════════════════════════════")
          _ (println "           SEARCH PERFORMANCE TEST")
          _ (println "═══════════════════════════════════════════════════════════════\n")

          test-queries ["the" "world" "first" "computer" "history"
                       "science" "technology" "united states" "new york"]

          restored-idx (neb/restore storage ref)

          ;; Test searches
          _ (println "Running test queries...")
          search-times (atom [])
          search-counts (atom [])

          _ (doseq [query test-queries]
              (let [start (System/nanoTime)
                    result (neb/search restored-idx query)
                    duration (- (System/nanoTime) start)]
                (swap! search-times conj duration)
                (swap! search-counts conj (count result))
                (println (format "  '%s' -> %d results (%s)"
                               query (count result) (bench-full-dataset/format-duration duration)))))

          avg-search (/ (reduce + @search-times) (count @search-times))
          avg-results (/ (reduce + @search-counts) (count @search-counts))

          _ (storage/close storage)

          total-time (+ parse-time build-time save-time)]

      (println "\n═══════════════════════════════════════════════════════════════")
      (println "                  FINAL RESULTS")
      (println "═══════════════════════════════════════════════════════════════\n")
      (println (format "Total documents: %d" (count @docs)))
      (println (format "Parse time:  %s" (bench-full-dataset/format-duration parse-time)))
      (println (format "Build time:  %s (%.0f docs/sec)"
                      (bench-full-dataset/format-duration build-time)
                      (/ (* (count @docs) 1e9) build-time)))
      (println (format "Save time:   %s" (bench-full-dataset/format-duration save-time)))
      (println (format "Total time:  %s" (bench-full-dataset/format-duration total-time)))
      (println (format "Index size:  %s" (bench-full-dataset/format-bytes index-size)))
      (println (format "Search avg:  %s (%d avg results)"
                      (bench-full-dataset/format-duration avg-search) (long avg-results)))
      (println "\n✓ Bulk load complete!")
      (println "═══════════════════════════════════════════════════════════════\n"))

    (catch Exception e
      (println "\nERROR:" (.getMessage e))
      (.printStackTrace e)
      (System/exit 1))))

(apply -main *command-line-args*)
