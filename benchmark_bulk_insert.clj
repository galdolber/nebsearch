(ns benchmark-bulk-insert
  "Benchmark different bulk insert optimization strategies"
  (:require [nebsearch.core :as search]
            [nebsearch.btree :as btree]
            [nebsearch.disk-storage :as disk]
            [nebsearch.entries :as entries])
  (:import [java.util Arrays]
           [nebsearch.entries DocumentEntry]))

(set! *warn-on-reflection* true)

;; Generate realistic test data similar to nebsearch use case
(defn generate-test-entries [n]
  "Generate n document entries with realistic doc IDs and positions"
  (mapv (fn [i]
          (entries/->DocumentEntry
            (long i)
            (str "doc-" (format "%08d" i))
            nil))
        (range n)))

;; Benchmark harness
(defn benchmark [name f]
  (let [start (System/nanoTime)
        _ (dotimes [_ 3] (f)) ; warmup
        start (System/nanoTime)
        result (f)
        end (System/nanoTime)
        elapsed-ms (/ (- end start) 1000000.0)]
    (println (format "%s: %.2f ms" name elapsed-ms))
    elapsed-ms))

;; === OPTIMIZATION 1: Avoid vec after sort (keep array) ===
(defn chunk-with-vec-current [^objects arr chunk-size]
  "Current approach: convert to vec, use take/drop"
  (let [sorted (vec arr)]
    (loop [remaining sorted
           result []]
      (if (empty? remaining)
        result
        (let [chunk (vec (take chunk-size remaining))]
          (recur (drop chunk-size remaining)
                 (conj result chunk)))))))

(defn chunk-with-array-optimized [^objects arr chunk-size]
  "Optimized: work with array directly, use transients"
  (let [len (alength arr)]
    (loop [offset 0
           result (transient [])]
      (if (>= offset len)
        (persistent! result)
        (let [end (min (+ offset chunk-size) len)
              chunk (java.util.Arrays/copyOfRange arr offset end)]
          (recur end (conj! result chunk)))))))

;; === OPTIMIZATION 2: Transients for building vectors ===
(defn build-with-persistent [items]
  "Current: persistent conj"
  (loop [remaining items
         result []]
    (if (empty? remaining)
      result
      (recur (rest remaining) (conj result (first remaining))))))

(defn build-with-transient [items]
  "Optimized: transient conj!"
  (loop [remaining items
         result (transient [])]
    (if (empty? remaining)
      (persistent! result)
      (recur (rest remaining) (conj! result (first remaining))))))

;; === OPTIMIZATION 3: Partition-all vs take/drop ===
(defn chunk-with-take-drop [coll chunk-size]
  "Current: loop with take/drop"
  (loop [remaining coll
         result []]
    (if (empty? remaining)
      result
      (recur (drop chunk-size remaining)
             (conj result (vec (take chunk-size remaining)))))))

(defn chunk-with-partition [coll chunk-size]
  "Optimized: partition-all with transients"
  (persistent!
   (reduce (fn [acc chunk]
             (conj! acc (vec chunk)))
           (transient [])
           (partition-all chunk-size coll))))

;; === OPTIMIZATION 4: Pre-calculate chunk metadata ===
(defn extract-keys-current [chunks]
  "Current: map with first/last calls"
  (vec (map (fn [chunk]
              {:min (first (first chunk))
               :max (first (last chunk))})
            chunks)))

(defn extract-keys-optimized [chunks]
  "Optimized: single pass with transients"
  (persistent!
   (reduce (fn [acc chunk]
             (conj! acc {:min (first (first chunk))
                        :max (first (last chunk))}))
           (transient [])
           chunks)))

;; === OPTIMIZATION 5: Batch array operations ===
(defn sort-entries-current [entries]
  "Current: to-array, sort, vec"
  (let [arr (to-array entries)]
    (Arrays/sort arr)
    (vec arr)))

(defn sort-entries-optimized [entries]
  "Optimized: to-array, sort, keep as array for chunking"
  (let [arr (to-array entries)]
    (Arrays/sort arr)
    arr)) ; return array, not vec

;; Run benchmarks
(defn -main []
  (println "\n=== Bulk Insert Optimization Benchmarks ===\n")

  ;; Test with realistic data sizes
  (doseq [size [1000 10000 50000]]
    (println (format "\n--- Testing with %d entries ---" size))
    (let [entries (generate-test-entries size)
          arr (to-array entries)]

      (println "\n1. Chunking strategy:")
      (benchmark "  take/drop + vec" #(chunk-with-take-drop entries 256))
      (benchmark "  partition-all  " #(chunk-with-partition entries 256))
      (benchmark "  array slicing  " #(chunk-with-array-optimized arr 256))

      (println "\n2. Vector building:")
      (let [items (take 1000 entries)]
        (benchmark "  persistent conj" #(build-with-persistent items))
        (benchmark "  transient conj!" #(build-with-transient items)))

      (println "\n3. Metadata extraction:")
      (let [chunks (partition-all 256 entries)]
        (benchmark "  map first/last " #(extract-keys-current chunks))
        (benchmark "  reduce transient" #(extract-keys-optimized chunks)))

      (println "\n4. Sorting strategy:")
      (benchmark "  sort -> vec    " #(sort-entries-current entries))
      (benchmark "  sort -> array  " #(sort-entries-optimized entries))))

  (println "\n=== Benchmarks Complete ===\n"))

(-main)
