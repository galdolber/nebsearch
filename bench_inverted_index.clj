(ns bench-inverted-index
  (:require [nebsearch.core :as neb]
            [nebsearch.memory-storage :as mem-storage]
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
(println "         Inverted Index Performance Benchmark")
(println "═══════════════════════════════════════════════════════════════\n")

;; Generate test data
(println "Generating 10,000 documents...")
(def docs (mapv (fn [i]
                 [(str "doc" i)
                  (str "content number " (mod i 100) " item " i)
                  (str "Title " i)])
               (range 10000)))

;; Test 1: Memory Storage (Lazy Inverted Index)
(println "\n1. MEMORY STORAGE (Lazy Inverted Index)")
(println "─────────────────────────────────────────────────────────────\n")
(let [idx (neb/search-add (neb/init) docs)]

  ;; First search - should build inverted index
  (print "  First search 'number'... ")
  (flush)
  (let [{:keys [duration result]} (measure-time #(neb/search idx "number"))]
    (println (format "%s (%d results)" (format-duration duration) (count result))))

  ;; Second search - should use cached inverted index
  (print "  Second search 'number'... ")
  (flush)
  (let [{:keys [duration result]} (measure-time #(neb/search idx "number"))]
    (println (format "%s (%d results)" (format-duration duration) (count result))))

  ;; Search for specific number
  (print "  Search 'number 42'... ")
  (flush)
  (let [{:keys [duration result]} (measure-time #(neb/search idx "number 42"))]
    (println (format "%s (%d results)" (format-duration duration) (count result))))

  ;; Search different word
  (print "  First search 'content'... ")
  (flush)
  (let [{:keys [duration result]} (measure-time #(neb/search idx "content"))]
    (println (format "%s (%d results)" (format-duration duration) (count result))))

  ;; Check inverted cache
  (let [inverted @(:inverted (meta idx))]
    (println (format "\n  Inverted cache: %d words cached" (count inverted)))))

;; Test 2: Disk Storage (Pre-computed Inverted Index)
(println "\n\n2. DISK STORAGE (Pre-computed Inverted Index)")
(println "─────────────────────────────────────────────────────────────\n")
(when (.exists (java.io.File. "/tmp/bench-inverted.dat"))
  (.delete (java.io.File. "/tmp/bench-inverted.dat")))

(let [storage (disk-storage/open-disk-storage "/tmp/bench-inverted.dat" 128 true)
      idx (neb/search-add (neb/init) docs)]

  (print "  Storing to disk... ")
  (flush)
  (let [{:keys [duration]} (measure-time #(neb/store idx storage))]
    (println (format-duration duration)))

  ;; Restore from disk
  (let [ref (neb/store idx storage)
        idx-disk (neb/restore storage ref)]

    (println (format "  Inverted root-offset: %s\n" (:inverted-root-offset ref)))

    ;; First search - should use pre-computed inverted index
    (print "  First search 'number'... ")
    (flush)
    (let [{:keys [duration result]} (measure-time #(neb/search idx-disk "number"))]
      (println (format "%s (%d results)" (format-duration duration) (count result))))

    ;; Second search - should use LRU cache
    (print "  Second search 'number'... ")
    (flush)
    (let [{:keys [duration result]} (measure-time #(neb/search idx-disk "number"))]
      (println (format "%s (%d results)" (format-duration duration) (count result))))

    ;; Search for specific number
    (print "  Search 'number 42'... ")
    (flush)
    (let [{:keys [duration result]} (measure-time #(neb/search idx-disk "number 42"))]
      (println (format "%s (%d results)" (format-duration duration) (count result))))

    ;; Search different word
    (print "  First search 'content'... ")
    (flush)
    (let [{:keys [duration result]} (measure-time #(neb/search idx-disk "content"))]
      (println (format "%s (%d results)" (format-duration duration) (count result))))

    (nebsearch.storage/close storage)))

;; Test 3: Comparison - String Scanning vs Inverted Index
(println "\n\n3. PERFORMANCE COMPARISON (100 queries)")
(println "─────────────────────────────────────────────────────────────\n")

(let [queries ["number" "content" "item" "number 42" "content item"]]

  ;; Memory storage (lazy inverted)
  (let [idx (neb/search-add (neb/init) docs)
        {:keys [duration]} (measure-time
                            (fn []
                              (dotimes [_ 20]
                                (doseq [q queries]
                                  (neb/search idx q)))))]
    (println (format "  Memory (lazy inverted):  %s (%.0f queries/sec)"
                    (format-duration duration)
                    (/ (* 100 1e9) duration))))

  ;; Disk storage (pre-computed inverted)
  (when (.exists (java.io.File. "/tmp/bench-inverted.dat"))
    (.delete (java.io.File. "/tmp/bench-inverted.dat")))
  (let [storage (disk-storage/open-disk-storage "/tmp/bench-inverted.dat" 128 true)
        idx (neb/search-add (neb/init) docs)
        ref (neb/store idx storage)
        idx-disk (neb/restore storage ref)
        {:keys [duration]} (measure-time
                            (fn []
                              (dotimes [_ 20]
                                (doseq [q queries]
                                  (neb/search idx-disk q)))))]
    (println (format "  Disk (pre-computed):     %s (%.0f queries/sec)"
                    (format-duration duration)
                    (/ (* 100 1e9) duration)))
    (nebsearch.storage/close storage)))

(println "\n═══════════════════════════════════════════════════════════════")
(println "Benchmark complete!")
