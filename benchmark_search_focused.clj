(require '[nebsearch.core :as neb])
(require '[nebsearch.disk-storage :as disk])
(require '[nebsearch.storage :as storage])

(defn format-duration [nanos]
  (cond
    (< nanos 1000) (format "%.2f ns" (double nanos))
    (< nanos 1000000) (format "%.2f μs" (/ nanos 1000.0))
    (< nanos 1000000000) (format "%.2f ms" (/ nanos 1000000.0))
    :else (format "%.2f s" (/ nanos 1000000000.0))))

(defn measure [f]
  (let [start (System/nanoTime)
        result (f)
        duration (- (System/nanoTime) start)]
    {:result result :duration duration}))

(defn generate-docs [n]
  (mapv (fn [i]
          [(str "doc" i)
           (str "word" (mod i 100) " common content document " (when (< i 1000) (str "rare" i)))
           (str "Title " i)])
        (range n)))

(println "\n╔════════════════════════════════════════════════════════════╗")
(println "║        FOCUSED SEARCH PERFORMANCE BENCHMARK               ║")
(println "║   (Testing weak areas: search at scale with substring)    ║")
(println "╚════════════════════════════════════════════════════════════╝\n")

(doseq [doc-count [1000 10000 50000]]
  (println (format "═══ Testing with %d documents ═══\n" doc-count))

  (let [docs (generate-docs doc-count)]

    ;; Build in-memory index
    (print "  Building in-memory index... ")
    (flush)
    (let [in-mem-build (measure #(neb/search-add (neb/init) docs))]
      (println (format "%s (%.0f docs/sec)"
                      (format-duration (:duration in-mem-build))
                      (/ (* doc-count 1e9) (:duration in-mem-build))))

      (let [idx-mem (:result in-mem-build)

            ;; Build disk index
            path (str "/tmp/bench-focused-" doc-count ".dat")
            _ (.delete (java.io.File. path))
            store (disk/open-disk-storage path 128 true)
            _ (do (print "  Building disk index... ") (flush))
            disk-build (measure #(let [idx (neb/search-add (neb/init) docs)
                                       ref (neb/store idx store)]
                                  (neb/restore store ref)))
            _ (println (format "%s (%.0f docs/sec)"
                              (format-duration (:duration disk-build))
                              (/ (* doc-count 1e9) (:duration disk-build))))
            idx-disk (:result disk-build)]

    ;; Test 1: Common word (high cardinality) - worst case
    (println "\n  Test 1: Common word search (\"common\" - appears in ALL docs)")
    (let [in-mem-cold (measure #(neb/search idx-mem "common"))
          in-mem-warm (measure #(neb/search idx-mem "common"))
          disk-cold (measure #(neb/search idx-disk "common"))
          disk-warm (measure #(neb/search idx-disk "common"))]
      (println (format "    In-Memory:  cold=%s  warm=%s  speedup=%.1fx"
                      (format-duration (:duration in-mem-cold))
                      (format-duration (:duration in-mem-warm))
                      (/ (:duration in-mem-cold) (double (:duration in-mem-warm)))))
      (println (format "    Disk:       cold=%s  warm=%s  speedup=%.1fx"
                      (format-duration (:duration disk-cold))
                      (format-duration (:duration disk-warm))
                      (/ (:duration disk-cold) (double (:duration disk-warm)))))
      (println (format "    Disk vs In-Memory: %.1fx faster (cold)"
                      (/ (:duration in-mem-cold) (double (:duration disk-cold))))))

    ;; Test 2: Rare word (low cardinality) - best case
    (println "\n  Test 2: Rare word search (\"rare500\" - appears in 1 doc)")
    (let [in-mem (measure #(neb/search idx-mem "rare500"))
          disk (measure #(neb/search idx-disk "rare500"))]
      (println (format "    In-Memory:  %s" (format-duration (:duration in-mem))))
      (println (format "    Disk:       %s" (format-duration (:duration disk))))
      (println (format "    Disk vs In-Memory: %.1fx faster"
                      (/ (:duration in-mem) (double (:duration disk))))))

    ;; Test 3: Multi-word query (common + rare) - realistic case
    (println "\n  Test 3: Multi-word search (\"common rare500\")")
    (let [in-mem (measure #(neb/search idx-mem "common rare500"))
          disk (measure #(neb/search idx-disk "common rare500"))]
      (println (format "    In-Memory:  %s" (format-duration (:duration in-mem))))
      (println (format "    Disk:       %s" (format-duration (:duration disk))))
      (println (format "    Disk vs In-Memory: %.1fx faster"
                      (/ (:duration in-mem) (double (:duration disk))))))

    ;; Test 4: Substring prefix match
    (println "\n  Test 4: Substring search (\"wor\" - prefix of \"word0\" thru \"word99\")")
    (let [in-mem (measure #(neb/search idx-mem "wor"))
          disk (measure #(neb/search idx-disk "wor"))]
      (println (format "    In-Memory:  %s (found %d docs)"
                      (format-duration (:duration in-mem))
                      (count (:result in-mem))))
      (println (format "    Disk:       %s (found %d docs)"
                      (format-duration (:duration disk))
                      (count (:result disk))))
      (println (format "    Disk vs In-Memory: %.1fx faster"
                      (/ (:duration in-mem) (double (:duration disk))))))

    ;; Test 5: Throughput test (100 varied queries)
    (println "\n  Test 5: Throughput (100 mixed queries)")
    (let [queries (concat
                   (repeat 50 "common")              ;; 50% common
                   (map #(str "word" %) (range 25))  ;; 25% medium
                   (map #(str "rare" %) (range 500 525))) ;; 25% rare
          in-mem (measure #(doseq [q queries] (neb/search idx-mem q)))
          disk (measure #(doseq [q queries] (neb/search idx-disk q)))]
      (println (format "    In-Memory:  %s (%.0f q/sec)"
                      (format-duration (:duration in-mem))
                      (/ (* 100 1e9) (:duration in-mem))))
      (println (format "    Disk:       %s (%.0f q/sec)"
                      (format-duration (:duration disk))
                      (/ (* 100 1e9) (:duration disk))))
      (println (format "    Disk vs In-Memory: %.1fx faster"
                      (/ (:duration in-mem) (double (:duration disk))))))

        (storage/close store)
        (.delete (java.io.File. path))
        (println)))))

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                    BENCHMARK COMPLETE                      ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println "\nKey Metrics to Track:")
(println "  • Common word search time (scales with doc count)")
(println "  • Rare word search time (should be constant)")
(println "  • Substring search time (worst case - full scan)")
(println "  • Throughput (mixed workload)")
(println "  • Cache effectiveness (cold vs warm ratio)")

(System/exit 0)
