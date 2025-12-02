#!/usr/bin/env bb
;; Build full Wikipedia index and save to disk

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[nebsearch.core :as neb]
         '[nebsearch.disk-storage :as disk])

(defn parse-wikipedia-abstracts
  "Parse Wikipedia abstracts XML format using streaming to avoid loading entire file.
   Each abstract is in format: <doc><title>...</title><abstract>...</abstract></doc>"
  [file max-docs]
  (println "Parsing Wikipedia abstracts...")
  (with-open [rdr (io/reader file)]
    (let [docs (atom [])
          current-doc (atom nil)
          doc-count (atom 0)]
      (doseq [line (line-seq rdr)]
        (when (or (nil? max-docs) (< @doc-count max-docs))
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
                  (when (zero? (mod @doc-count 10000))
                    (println (format "  Parsed %d documents..." @doc-count))))))
            (reset! current-doc nil))))
      (println (format "Found %d articles" (count @docs)))
      (vec @docs))))

(defn build-and-save-index! [docs index-path]
  (println "\n" (str/join "" (repeat 80 "=")))
  (println "Building full Wikipedia index...")
  (println (str/join "" (repeat 80 "=")))

  (let [start-parse (System/nanoTime)]
    (println (format "\nTotal documents: %d" (count docs)))
    (let [total-size (reduce + (map #(count (nth % 1)) docs))]
      (println (format "Total text size: %.2f MB" (/ total-size 1024.0 1024.0))))

    ;; Build index
    (println "\nBuilding index...")
    (let [start-build (System/nanoTime)
          idx (neb/init)
          idx2 (neb/search-add idx docs)
          end-build (System/nanoTime)
          build-time-ms (/ (- end-build start-build) 1e6)
          build-time-s (/ build-time-ms 1000.0)]

      (println (format "  Build time:        %.2f s" build-time-s))
      (println (format "  Throughput:        %.0f docs/sec" (/ (count docs) build-time-s)))

      ;; Save to disk
      (println "\nSaving index to disk...")
      (let [start-store (System/nanoTime)
            storage (disk/open-disk-storage index-path 256 true)
            ref (neb/store idx2 storage)
            end-store (System/nanoTime)
            store-time-ms (/ (- end-store start-store) 1e6)
            index-size (.length (io/file index-path))]

        ;; Save the reference map so we can restore later
        (spit (str index-path ".ref") (pr-str ref))

        (println (format "  Store time:        %.2f s" (/ store-time-ms 1000.0)))
        (println (format "  Index file size:   %.2f MB" (/ index-size 1024.0 1024.0)))
        (println (format "  Compression ratio: %.1fx"
                        (/ (reduce + (map #(count (nth % 1)) docs))
                           (double index-size))))

        (println "\n" (str/join "" (repeat 80 "=")))
        (println "✓ Index build complete!")
        (println (str/join "" (repeat 80 "=")))
        (println (format "\nIndex saved to: %s" index-path))
        (println (format "Reference saved to: %s" (str index-path ".ref")))
        (println (format "Total time: %.2f seconds" (/ (- end-store start-parse) 1e9)))
        (println "\nYou can now run: clj search_wikipedia.clj")

        {:build-time-s build-time-s
         :store-time-s (/ store-time-ms 1000.0)
         :index-size-mb (/ index-size 1024.0 1024.0)
         :total-docs (count docs)}))))

(defn -main [& args]
  (let [dataset-path (or (first args) "dataset.txt")
        index-path (or (second args) "wikipedia.idx")
        max-docs (if (> (count args) 2)
                  (Integer/parseInt (nth args 2))
                  Integer/MAX_VALUE)]

    (println "Wikipedia Index Builder")
    (println (str/join "" (repeat 80 "=")))
    (println "Dataset:" dataset-path)
    (println "Index output:" index-path)
    (println "Max documents:" (if (= max-docs Integer/MAX_VALUE) "ALL" max-docs))

    ;; Check if dataset exists
    (when-not (.exists (io/file dataset-path))
      (println "\n❌ Error: Dataset file not found:" dataset-path)
      (System/exit 1))

    ;; Parse and build
    (let [docs (parse-wikipedia-abstracts dataset-path max-docs)]
      (when (empty? docs)
        (println "\n❌ Error: No documents parsed from dataset")
        (System/exit 1))

      (build-and-save-index! docs index-path))

    (println "\n✓ Done!")
    (System/exit 0)))

;; Run if executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

;; For clojure
(when-not (System/getProperty "babashka.file")
  (apply -main *command-line-args*))
