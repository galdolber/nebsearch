(ns benchmark-io
  "Benchmark different I/O strategies for bulk writes"
  (:require [nebsearch.entries :as entries])
  (:import [java.io RandomAccessFile File ByteArrayOutputStream DataOutputStream]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.util.zip CRC32]
           [nebsearch.entries DocumentEntry]))

(set! *warn-on-reflection* true)

;; Simplified node serialization for benchmarking
(defn- serialize-node-simple [entries]
  "Simplified serialization for benchmark"
  (let [baos (ByteArrayOutputStream.)
        dos (DataOutputStream. baos)]
    (.writeByte dos (int 1)) ; leaf node
    (.writeInt dos (int (count entries)))
    (doseq [^DocumentEntry e entries]
      (.writeByte dos (int 0))
      (.writeLong dos (long (.-pos e)))
      (let [^bytes id-bytes (.getBytes ^String (.-id e) "UTF-8")]
        (.writeInt dos (int (alength id-bytes)))
        (.write dos id-bytes))
      (.writeBoolean dos false)) ; no text
    (.toByteArray baos)))

;; Test data
(defn generate-test-nodes [n-nodes entries-per-node]
  (mapv (fn [i]
          (mapv (fn [j]
                  (entries/->DocumentEntry
                    (long (+ (* i entries-per-node) j))
                    (str "doc-" (format "%08d" j))
                    nil))
                (range entries-per-node)))
        (range n-nodes)))

;; === STRATEGY 1: Current - Individual writes with RAF ===
(defn write-nodes-current [^String file-path nodes]
  (let [file (File. file-path)
        raf (RandomAccessFile. file "rw")]
    (try
      (doseq [node-entries nodes]
        (let [offset (.length raf)
              ^bytes node-bytes (serialize-node-simple node-entries)
              len (int (alength node-bytes))]
          (.seek raf offset)
          (.writeInt raf len)
          (.write raf node-bytes)
          (.writeInt raf (int 0)))) ; dummy checksum
      (.sync (.getFD raf)) ; sync to disk
      (finally
        (.close raf)))))

;; === STRATEGY 2: FileChannel with individual direct buffers ===
(defn write-nodes-filechannel [^String file-path nodes]
  (let [file (File. file-path)
        raf (RandomAccessFile. file "rw")
        ^FileChannel channel (.getChannel raf)]
    (try
      (doseq [node-entries nodes]
        (let [^bytes node-bytes (serialize-node-simple node-entries)
              len (int (alength node-bytes))
              buf-size (int (+ 4 (+ len 4)))
              ^ByteBuffer buffer (ByteBuffer/allocate buf-size)]
          (.putInt buffer len)
          (.put buffer node-bytes)
          (.putInt buffer (int 0)) ; dummy checksum
          (.flip buffer)
          (.write channel buffer)))
      (.force channel true) ; sync to disk
      (finally
        (.close channel)
        (.close raf)))))

;; === STRATEGY 3: Batch all nodes into single buffer ===
(defn write-nodes-batched [^String file-path nodes]
  (let [file (File. file-path)
        raf (RandomAccessFile. file "rw")
        ^FileChannel channel (.getChannel raf)
        ;; Serialize all nodes first
        serialized-nodes (mapv serialize-node-simple nodes)
        total-size (int (reduce (fn [^long acc ^bytes b] (+ acc (+ 4 (+ (alength b) 4))))
                               (long 0) serialized-nodes))
        ^ByteBuffer buffer (ByteBuffer/allocate total-size)]
    (try
      ;; Write all nodes to buffer
      (doseq [^bytes node-bytes serialized-nodes]
        (let [len (int (alength node-bytes))]
          (.putInt buffer len)
          (.put buffer node-bytes)
          (.putInt buffer (int 0)))) ; dummy checksum
      (.flip buffer)
      ;; Single write operation
      (.write channel buffer)
      (.force channel true) ; sync to disk
      (finally
        (.close channel)
        (.close raf)))))

;; === STRATEGY 4: Direct ByteBuffer (off-heap) batch ===
(defn write-nodes-direct-buffer [^String file-path nodes]
  (let [file (File. file-path)
        raf (RandomAccessFile. file "rw")
        ^FileChannel channel (.getChannel raf)
        ;; Serialize all nodes first
        serialized-nodes (mapv serialize-node-simple nodes)
        total-size (int (reduce (fn [^long acc ^bytes b] (+ acc (+ 4 (+ (alength b) 4))))
                               (long 0) serialized-nodes))
        ^ByteBuffer buffer (ByteBuffer/allocateDirect total-size)]
    (try
      ;; Write all nodes to direct buffer
      (doseq [^bytes node-bytes serialized-nodes]
        (let [len (int (alength node-bytes))]
          (.putInt buffer len)
          (.put buffer node-bytes)
          (.putInt buffer (int 0)))) ; dummy checksum
      (.flip buffer)
      ;; Single write operation
      (.write channel buffer)
      (.force channel true) ; sync to disk
      (finally
        (.close channel)
        (.close raf)))))

;; === STRATEGY 5: Batched without immediate sync ===
(defn write-nodes-batched-nosync [^String file-path nodes]
  (let [file (File. file-path)
        raf (RandomAccessFile. file "rw")
        ^FileChannel channel (.getChannel raf)
        serialized-nodes (mapv serialize-node-simple nodes)
        total-size (int (reduce (fn [^long acc ^bytes b] (+ acc (+ 4 (+ (alength b) 4))))
                               (long 0) serialized-nodes))
        ^ByteBuffer buffer (ByteBuffer/allocate total-size)]
    (try
      (doseq [^bytes node-bytes serialized-nodes]
        (let [len (int (alength node-bytes))]
          (.putInt buffer len)
          (.put buffer node-bytes)
          (.putInt buffer (int 0))))
      (.flip buffer)
      (.write channel buffer)
      ;; NO force() call - rely on OS caching
      (finally
        (.close channel)
        (.close raf)))))

;; Benchmark harness
(defn benchmark [name f]
  (let [_ (dotimes [_ 2] (f)) ; warmup
        start (System/nanoTime)
        _ (f)
        end (System/nanoTime)
        elapsed-ms (/ (- end start) 1000000.0)]
    (println (format "  %s: %.2f ms" name elapsed-ms))
    elapsed-ms))

(defn cleanup [file-path]
  (.delete (File. file-path)))

(defn -main []
  (println "\n=== I/O Strategy Benchmarks ===\n")

  (doseq [[n-nodes entries-per-node] [[100 256] [500 256] [1000 256]]]
    (println (format "\n--- %d nodes, %d entries each ---" n-nodes entries-per-node))
    (let [nodes (generate-test-nodes n-nodes entries-per-node)]
      (benchmark "Individual RAF writes    " #(do (write-nodes-current "/tmp/bench1.db" nodes) (cleanup "/tmp/bench1.db")))
      (benchmark "FileChannel individual   " #(do (write-nodes-filechannel "/tmp/bench2.db" nodes) (cleanup "/tmp/bench2.db")))
      (benchmark "Batched heap buffer      " #(do (write-nodes-batched "/tmp/bench3.db" nodes) (cleanup "/tmp/bench3.db")))
      (benchmark "Batched direct buffer    " #(do (write-nodes-direct-buffer "/tmp/bench4.db" nodes) (cleanup "/tmp/bench4.db")))
      (benchmark "Batched no-sync          " #(do (write-nodes-batched-nosync "/tmp/bench5.db" nodes) (cleanup "/tmp/bench5.db")))))

  (println "\n=== Benchmarks Complete ===\n"))

(-main)
