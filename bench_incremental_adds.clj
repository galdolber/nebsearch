(ns bench-incremental-adds
  (:require [nebsearch.core :as neb]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.storage :as storage]))

(defn measure-time [f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)]
    {:duration (- end start)
     :result result}))

(defn format-duration [nanos]
  (cond
    (< nanos 1000) (format "%.2f ns" (double nanos))
    (< nanos 1000000) (format "%.2f μs" (/ nanos 1000.0))
    (< nanos 1000000000) (format "%.2f ms" (/ nanos 1000000.0))
    :else (format "%.2f s" (/ nanos 1000000000.0))))

(println "═══════════════════════════════════════════════════════════════")
(println "       Incremental Add Performance Benchmark")
(println "═══════════════════════════════════════════════════════════════\n")

;; Test 1: Single doc add to indexes of different sizes
(println "1. SINGLE DOCUMENT ADD (Memory Storage)")
(println "   Measuring O(n) scaling issue with bulk-only approach")
(println "─────────────────────────────────────────────────────────────\n")

(doseq [size [100 1000 5000 10000]]
  (let [;; Build initial index
        initial-data (into {} (map (fn [i] [i (str "doc-" i)]) (range size)))
        idx (neb/search-add (neb/init) initial-data)

        ;; Measure adding one more document
        {:keys [duration]} (measure-time
                            #(neb/search-add idx {999999 "new document"}))]
    (println (format "  Index size: %5d docs → Add 1 doc: %s"
                    size (format-duration duration)))))

;; Test 2: Sequential adds vs batch add
(println "\n\n2. SEQUENTIAL vs BATCH ADD (Memory Storage)")
(println "   1000 documents added one-by-one vs all at once")
(println "─────────────────────────────────────────────────────────────\n")

(let [docs (into {} (map (fn [i] [i (str "doc-" i)]) (range 1000)))]

  ;; Sequential adds (worst case)
  (let [{:keys [duration]} (measure-time
                            (fn []
                              (reduce (fn [idx [id text]]
                                       (neb/search-add idx {id text}))
                                     (neb/init)
                                     docs)))]
    (println (format "  Sequential (1 at a time): %s" (format-duration duration))))

  ;; Batch add (current optimal)
  (let [{:keys [duration]} (measure-time
                            #(neb/search-add (neb/init) docs))]
    (println (format "  Batch (all at once):      %s" (format-duration duration)))))

;; Test 3: Different batch sizes
(println "\n\n3. BATCH SIZE IMPACT (Memory Storage)")
(println "   1000 documents added in different batch sizes")
(println "─────────────────────────────────────────────────────────────\n")

(doseq [batch-size [1 10 50 100 250 500 1000]]
  (let [all-docs (into {} (map (fn [i] [i (str "doc-" i)]) (range 1000)))
        batches (partition-all batch-size all-docs)

        {:keys [duration]} (measure-time
                            (fn []
                              (reduce (fn [idx batch]
                                       (neb/search-add idx (into {} batch)))
                                     (neb/init)
                                     batches)))]
    (println (format "  Batch size: %4d → Total time: %s (%.2f batches)"
                    batch-size
                    (format-duration duration)
                    (/ 1000.0 batch-size)))))

;; Test 4: Disk storage - pre-computed inverted index overhead
(println "\n\n4. INCREMENTAL ADD WITH DISK STORAGE")
(println "   Overhead of pre-computing inverted index on each add")
(println "─────────────────────────────────────────────────────────────\n")

(when (.exists (java.io.File. "/tmp/bench-incremental.dat"))
  (.delete (java.io.File. "/tmp/bench-incremental.dat")))

(let [storage (disk-storage/open-disk-storage "/tmp/bench-incremental.dat" 128 true)

      ;; Build initial index with 1000 docs
      initial-data (into {} (map (fn [i] [i (str "document content number " (mod i 100))])
                                 (range 1000)))
      idx (neb/search-add (neb/init) initial-data)
      ref (neb/store idx storage)
      disk-idx (neb/restore storage ref)]

  (println "  Initial index: 1000 docs stored to disk\n")

  ;; Add 1 document
  (let [{:keys [duration]} (measure-time
                            #(neb/search-add disk-idx {9999 "new document content"}))]
    (println (format "  Add 1 doc (in-memory):  %s" (format-duration duration))))

  ;; Add and persist 1 document
  (let [{:keys [duration]} (measure-time
                            (fn []
                              (let [updated (neb/search-add disk-idx {9999 "new document content"})]
                                (neb/store updated storage))))]
    (println (format "  Add 1 doc + persist:    %s" (format-duration duration))))

  ;; Add 10 documents
  (let [new-docs (into {} (map (fn [i] [(+ 10000 i) (str "new doc " i)]) (range 10)))
        {:keys [duration]} (measure-time
                            #(neb/search-add disk-idx new-docs))]
    (println (format "  Add 10 docs (in-memory): %s" (format-duration duration))))

  ;; Add and persist 10 documents
  (let [new-docs (into {} (map (fn [i] [(+ 10000 i) (str "new doc " i)]) (range 10)))
        {:keys [duration]} (measure-time
                            (fn []
                              (let [updated (neb/search-add disk-idx new-docs)]
                                (neb/store updated storage))))]
    (println (format "  Add 10 docs + persist:   %s" (format-duration duration))))

  (storage/close storage))

;; Test 5: Real-world simulation
(println "\n\n5. REAL-WORLD SIMULATION")
(println "   Simulating continuous document ingestion")
(println "─────────────────────────────────────────────────────────────\n")

(let [;; Start with 5000 documents
      initial-data (into {} (map (fn [i] [i (str "doc-" i)]) (range 5000)))
      idx (neb/search-add (neb/init) initial-data)]

  (println "  Scenario: 5000 existing docs, adding 100 new docs")
  (println)

  ;; Scenario A: User adds docs one at a time (worst case)
  (let [{:keys [duration]} (measure-time
                            (fn []
                              (reduce (fn [idx i]
                                       (neb/search-add idx {(+ 10000 i) (str "new-" i)}))
                                     idx
                                     (range 100))))]
    (println (format "  A. One-by-one (100 calls):    %s (%.2f ms per add)"
                    (format-duration duration)
                    (/ duration 100.0 1000000.0))))

  ;; Scenario B: Micro-batches (buffer 5 docs before adding)
  (let [{:keys [duration]} (measure-time
                            (fn []
                              (reduce (fn [idx batch]
                                       (neb/search-add idx (into {} batch)))
                                     idx
                                     (partition-all 5
                                                   (map (fn [i] [(+ 10000 i) (str "new-" i)])
                                                        (range 100))))))]
    (println (format "  B. Micro-batches (5 docs):    %s (%.2f ms per batch)"
                    (format-duration duration)
                    (/ duration 20.0 1000000.0))))

  ;; Scenario C: Medium batches (buffer 25 docs)
  (let [{:keys [duration]} (measure-time
                            (fn []
                              (reduce (fn [idx batch]
                                       (neb/search-add idx (into {} batch)))
                                     idx
                                     (partition-all 25
                                                   (map (fn [i] [(+ 10000 i) (str "new-" i)])
                                                        (range 100))))))]
    (println (format "  C. Medium batches (25 docs):  %s (%.2f ms per batch)"
                    (format-duration duration)
                    (/ duration 4.0 1000000.0))))

  ;; Scenario D: Single batch (best case with current impl)
  (let [new-docs (into {} (map (fn [i] [(+ 10000 i) (str "new-" i)]) (range 100)))
        {:keys [duration]} (measure-time
                            #(neb/search-add idx new-docs))]
    (println (format "  D. Single batch (100 docs):   %s"
                    (format-duration duration)))))

(println "\n═══════════════════════════════════════════════════════════════")
(println "ANALYSIS:")
(println "")
(println "If Test 1 shows linear scaling (10x docs = 10x time), then")
(println "hybrid insert would provide O(log n) improvement for small adds.")
(println "")
(println "If Test 5 shows scenario A >> scenario D, then buffering or")
(println "hybrid insert would significantly improve real-world performance.")
(println "═══════════════════════════════════════════════════════════════")
