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

(println "\n════════════════════════════════════════")
(println "  NebSearch Search Performance Analysis")
(println "════════════════════════════════════════\n")

;; Test with 1000 docs
(let [n 1000
      docs (generate-docs n)
      idx (neb/search-add (neb/init) docs)

      ;; Generate different types of queries
      single-queries (take 10 (repeatedly #(str "content " (rand-int n))))
      cached-query "content 0" ;; Should hit cache
      multi-word-query "content additional"]

  (println (format "Testing with %d documents\n" n))

  ;; Test 1: Single query (cold cache)
  (println "1. Single query (cold cache):")
  (let [[result time] (measure-time #(neb/search idx "content 0"))]
    (println (format "   Time: %s" (format-duration time)))
    (println (format "   Results: %d documents" (count result))))

  ;; Test 2: Same query again (warm cache)
  (println "\n2. Same query (warm cache - should be instant):")
  (let [[result time] (measure-time #(neb/search idx "content 0"))]
    (println (format "   Time: %s" (format-duration time)))
    (println (format "   Results: %d documents" (count result))))

  ;; Test 3: Different single-word queries
  (println "\n3. 10 different single-word queries:")
  (let [start (System/nanoTime)]
    (doseq [q single-queries]
      (neb/search idx q))
    (let [total-time (- (System/nanoTime) start)]
      (println (format "   Total: %s" (format-duration total-time)))
      (println (format "   Per query: %s" (format-duration (quot total-time 10))))))

  ;; Test 4: Multi-word query
  (println "\n4. Multi-word query:")
  (let [[result time] (measure-time #(neb/search idx multi-word-query))]
    (println (format "   Query: '%s'" multi-word-query))
    (println (format "   Time: %s" (format-duration time)))
    (println (format "   Results: %d documents" (count result))))

  ;; Test 5: Profile a single query in detail
  (println "\n5. Detailed profiling of single query:")
  (println "   (This requires instrumenting the search function)")

  ;; Test 6: Scaling test
  (println "\n6. How search time scales with document count:")
  (doseq [size [100 500 1000 2000]]
    (let [test-docs (generate-docs size)
          test-idx (neb/search-add (neb/init) test-docs)
          [_ time] (measure-time #(neb/search test-idx "content 0"))]
      (println (format "   %4d docs: %s" size (format-duration time))))))

(println "\n════════════════════════════════════════")
(println "Analysis complete!")
(println "════════════════════════════════════════\n")

(System/exit 0)
