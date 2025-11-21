(ns nebsearch.disk-storage
  "Disk-based storage implementation for nebsearch B-tree nodes.

  Features:
  - RandomAccessFile for efficient random access
  - Custom binary serialization (hand-optimized for B-tree nodes)
  - CRC32 checksums for data integrity
  - Node caching for performance
  - Atomic updates via write-ahead approach
  - Explicit save semantics (no automatic flush)"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [nebsearch.storage :as storage]
            [nebsearch.entries :as entries]
            [nebsearch.btree :as btree])
  #?(:clj (:import [java.io RandomAccessFile File ByteArrayOutputStream ByteArrayInputStream
                    DataOutputStream DataInputStream]
                   [java.nio ByteBuffer]
                   [java.util.zip CRC32]
                   [nebsearch.entries DocumentEntry InvertedEntry]
                   [nebsearch.btree InternalNode LeafNode])))

#?(:clj
   (do
     (set! *warn-on-reflection* true)

     ;; File format constants
     (def ^:const header-size 256)
     (def ^:const magic-number "NEBSRCH\0")

     ;; Serialization utilities
     (defn- crc32 [^bytes data]
       "Calculate CRC32 checksum"
       (let [crc (CRC32.)]
         (.update crc data)
         (.getValue crc)))

     (defn- write-utf8-string [^DataOutputStream dos ^String s]
       "Write string with raw UTF-8 encoding (int32 length + UTF-8 bytes)"
       (let [^bytes bytes (.getBytes s "UTF-8")]
         (.writeInt dos (alength bytes))
         (.write dos ^bytes bytes)))

     (defn- read-utf8-string [^DataInputStream dis]
       "Read string with raw UTF-8 decoding (int32 length + UTF-8 bytes)"
       (let [len (.readInt dis)
             bytes (byte-array len)]
         (.readFully dis bytes)
         (String. bytes "UTF-8")))

     (defn- serialize-node [node]
       "Custom binary serialization optimized for B-tree nodes with direct field access"
       (let [baos (ByteArrayOutputStream.)
             dos (DataOutputStream. baos)]

         (cond
           ;; Deftype InternalNode - use direct field access (zero overhead)
           ;; Keys are ALWAYS longs (DocumentEntry.pos or InvertedEntry.word-hash)
           (instance? InternalNode node)
           (let [^InternalNode n node
                 keys (.-keys n)
                 children (.-children n)]
             (.writeByte dos 0) ; internal node type
             ;; No key-type byte - keys are always longs!
             (.writeInt dos (count keys))
             (doseq [k keys] (.writeLong dos (long k))) ; Direct long write
             (.writeInt dos (count children))
             (doseq [c children] (.writeLong dos c)))

           ;; Deftype LeafNode - use direct field access (zero overhead)
           (instance? LeafNode node)
           (let [^LeafNode n node
                 entries (.-entries n)
                 next-leaf (.-next-leaf n)]
             (.writeByte dos 1) ; leaf node type
             (.writeInt dos (count entries))
             (doseq [entry entries]
               (cond
                 ;; Document B-tree entry (deftype)
                 (instance? DocumentEntry entry)
                 (do
                   (.writeByte dos 0) ;; entry type: 0 = document
                   (let [^DocumentEntry e entry
                         pos (.-pos e)
                         id (.-id e)
                         text (.-text e)]
                     (.writeLong dos pos)
                     (write-utf8-string dos id)
                     (if text
                       (do
                         (.writeBoolean dos true)
                         (write-utf8-string dos text))
                       (.writeBoolean dos false))))

                 ;; Inverted index B-tree entry (deftype)
                 (instance? InvertedEntry entry)
                 (do
                   (.writeByte dos 1) ;; entry type: 1 = inverted
                   (let [^InvertedEntry e entry
                         word (.-word e)
                         doc-id (.-doc-id e)]
                     (write-utf8-string dos word)
                     (write-utf8-string dos doc-id)))

                 ;; Backwards compatibility: vector entries
                 (vector? entry)
                 (let [first-elem (first entry)]
                   (if (instance? Long first-elem)
                     ;; Document B-tree entry: [pos id text]
                     (do
                       (.writeByte dos 0)
                       (let [[pos id text] entry]
                         (.writeLong dos pos)
                         (write-utf8-string dos id)
                         (if text
                           (do
                             (.writeBoolean dos true)
                             (write-utf8-string dos text))
                           (.writeBoolean dos false))))
                     ;; Inverted index B-tree entry: [word doc-id]
                     (do
                       (.writeByte dos 1)
                       (let [[word doc-id] entry]
                         (write-utf8-string dos word)
                         (write-utf8-string dos doc-id)))))

                 :else
                 (throw (ex-info "Unknown entry type in B-tree node"
                                {:entry entry :type (type entry)}))))
             (if next-leaf
               (do
                 (.writeBoolean dos true)
                 (.writeLong dos next-leaf))
               (.writeBoolean dos false)))

           ;; Backwards compatibility: Map-based nodes
           :else
           (let [node-data (dissoc node :offset :cached)]
             (if (= (:type node-data) :internal)
               ;; Internal map node
               (let [keys (:keys node-data)
                     children (:children node-data)
                     first-key (first keys)
                     is-long-keys (instance? Long first-key)]
                 (.writeByte dos 0) ; internal node type
                 (.writeByte dos (if is-long-keys 0 1)) ; key type
                 (.writeInt dos (count keys))
                 (if is-long-keys
                   (doseq [k keys] (.writeLong dos k))
                   (doseq [k keys] (write-utf8-string dos k)))
                 (.writeInt dos (count children))
                 (doseq [c children] (.writeLong dos c)))
               ;; Leaf map node
               (let [entries (:entries node-data)
                     next-leaf (:next-leaf node-data)]
                 (.writeByte dos 1) ; leaf node type
                 (.writeInt dos (count entries))
                 (doseq [entry entries]
                   (cond
                     (instance? DocumentEntry entry)
                     (do
                       (.writeByte dos 0)
                       (let [^DocumentEntry e entry]
                         (.writeLong dos (.-pos e))
                         (write-utf8-string dos (.-id e))
                         (if (.-text e)
                           (do
                             (.writeBoolean dos true)
                             (write-utf8-string dos (.-text e)))
                           (.writeBoolean dos false))))

                     (instance? InvertedEntry entry)
                     (do
                       (.writeByte dos 1)
                       (let [^InvertedEntry e entry]
                         (write-utf8-string dos (.-word e))
                         (write-utf8-string dos (.-doc-id e))))

                     (vector? entry)
                     (let [first-elem (first entry)]
                       (if (instance? Long first-elem)
                         (do
                           (.writeByte dos 0)
                           (let [[pos id text] entry]
                             (.writeLong dos pos)
                             (write-utf8-string dos id)
                             (if text
                               (do
                                 (.writeBoolean dos true)
                                 (write-utf8-string dos text))
                               (.writeBoolean dos false))))
                         (do
                           (.writeByte dos 1)
                           (let [[word doc-id] entry]
                             (write-utf8-string dos word)
                             (write-utf8-string dos doc-id)))))

                     :else
                     (throw (ex-info "Unknown entry type" {:entry entry}))))
                 (if next-leaf
                   (do
                     (.writeBoolean dos true)
                     (.writeLong dos next-leaf))
                   (.writeBoolean dos false))))))

         ;; Return serialized bytes
         (.toByteArray baos)))

     (defn- deserialize-node [^bytes data offset]
       "Custom binary deserialization returns deftype nodes for zero overhead"
       (let [bais (ByteArrayInputStream. data)
             dis (DataInputStream. bais)
             node-type (.readByte dis)]

         (if (= node-type 0)
           ;; Internal node - return InternalNode deftype with :offset in ILookup
           ;; Keys are ALWAYS longs (no key-type byte needed)
           (let [key-count (.readInt dis)
                 keys (vec (repeatedly key-count #(.readLong dis))) ; Always long keys
                 child-count (.readInt dis)
                 children (vec (repeatedly child-count #(.readLong dis)))
                 node (btree/internal-node keys children)]
             ;; Store offset as metadata to avoid modifying deftype
             (vary-meta node assoc :offset offset))

           ;; Leaf node - return LeafNode deftype with :offset in metadata
           (let [entry-count (.readInt dis)
                 entries (vec (repeatedly entry-count
                                         (fn []
                                           (let [entry-type (.readByte dis)]
                                             (if (= entry-type 0)
                                               ;; Document B-tree entry
                                               (let [pos (.readLong dis)
                                                     id (read-utf8-string dis)
                                                     has-text (.readBoolean dis)
                                                     text (when has-text (read-utf8-string dis))]
                                                 (entries/->DocumentEntry pos id text))
                                               ;; Inverted index B-tree entry
                                               (let [word (read-utf8-string dis)
                                                     doc-id (read-utf8-string dis)]
                                                 (entries/->InvertedEntry word doc-id)))))))
                 has-next-leaf (.readBoolean dis)
                 next-leaf (when has-next-leaf (.readLong dis))
                 node (btree/leaf-node entries next-leaf)]
             ;; Store offset as metadata
             (vary-meta node assoc :offset offset)))))

     ;; File header management
     (defn- write-header [^RandomAccessFile raf root-offset node-count btree-order]
       "Write file header"
       (.seek raf 0)
       (.writeBytes raf magic-number)
       (.writeInt raf 1) ;; version
       (.writeLong raf (or root-offset -1))
       (.writeLong raf (or node-count 0))
       (.writeLong raf -1) ;; free list head (unused for now)
       (.writeInt raf btree-order)
       ;; Pad to header-size
       (let [written (+ 8 4 8 8 8 4)
             padding (- header-size written)]
         (dotimes [_ padding]
           (.writeByte raf 0))))

     (defn- read-header [^RandomAccessFile raf]
       "Read file header, returns {:root-offset, :node-count, :order}"
       (.seek raf 0)
       (let [magic (byte-array 8)]
         (.read raf magic)
         (when (not= (String. magic "UTF-8") magic-number)
           (throw (ex-info "Invalid magic number" {:magic (String. magic "UTF-8")}))))
       (let [version (.readInt raf)
             root-offset (.readLong raf)
             node-count (.readLong raf)
             _ (.readLong raf) ;; free list
             order (.readInt raf)]
         {:version version
          :root-offset (when (>= root-offset 0) root-offset)
          :node-count node-count
          :order order}))

     ;; DiskStorage record
     (defrecord DiskStorage [file-path
                             ^RandomAccessFile raf
                             node-cache        ;; atom: {offset -> node}
                             root-offset-atom  ;; atom: current root offset (not saved until explicit save)
                             btree-order]      ;; B-tree configuration
       storage/IStorage
       (store [this node]
         "Store a node and return its file offset as address"
         (let [offset (.length raf)]
           (.seek raf offset)
           (let [^bytes node-bytes (serialize-node node)
                 checksum (crc32 node-bytes)
                 length (alength node-bytes)]
             ;; Write: length (4) + data (n) + checksum (4)
             (.writeInt raf length)
             (.write raf ^bytes node-bytes)
             (.writeInt raf (unchecked-int checksum))
             ;; Cache the node with offset - use metadata for deftypes, assoc for maps
             (let [cached-node (if (map? node)
                                (assoc node :offset offset)
                                (with-meta node (assoc (meta node) :offset offset)))]
               (swap! node-cache assoc offset cached-node))
             offset)))

       (restore [this address]
         "Restore a node from its file offset"
         (if-let [cached (get @node-cache address)]
           cached
           (do
             (.seek raf address)
             (let [length (.readInt raf)
                   node-bytes (byte-array length)]
               (.read raf node-bytes)
               (let [stored-checksum (unchecked-int (.readInt raf))
                     computed-checksum (unchecked-int (crc32 node-bytes))]
                 (when (not= stored-checksum computed-checksum)
                   (throw (ex-info "Checksum mismatch"
                                   {:offset address
                                    :stored stored-checksum
                                    :computed computed-checksum})))
                 (let [node (deserialize-node node-bytes address)]
                   (swap! node-cache assoc address node)
                   node))))))

       storage/IBatchedStorage
       (batch-store [this nodes]
         "Batched storage: 2-4x faster than individual writes.

         Strategy:
         1. Pre-calculate all offsets
         2. Serialize all nodes into single ByteBuffer
         3. Single FileChannel.write() call
         4. Single fsync at end
         5. Update cache with all nodes"
         (if (empty? nodes)
           []
           (let [start-offset (.length raf)
                 ;; Phase 1: Serialize all nodes and calculate sizes
                 serialized (loop [i (int 0)
                                  results (transient [])]
                             (if (>= i (count nodes))
                               (persistent! results)
                               (let [node (nth nodes i)
                                     ^bytes node-bytes (serialize-node node)
                                     checksum (crc32 node-bytes)
                                     length (alength node-bytes)
                                     ;; Each node: length (4) + data (n) + checksum (4)
                                     node-size (+ 4 length 4)]
                                 (recur (int (inc i))
                                        (conj! results {:node node
                                                       :bytes node-bytes
                                                       :checksum checksum
                                                       :length length
                                                       :size node-size})))))

                 ;; Phase 2: Calculate offsets for each node
                 offsets (loop [i (int 0)
                               current-offset (long start-offset)
                               offs (transient [])]
                          (if (>= i (count serialized))
                            (persistent! offs)
                            (let [item (nth serialized i)
                                  size (long (:size item))]
                              (recur (int (inc i))
                                     (long (+ current-offset size))
                                     (conj! offs current-offset)))))

                 ;; Phase 3: Calculate total buffer size
                 total-size (long (reduce + 0 (map :size serialized)))

                 ;; Phase 4: Allocate heap ByteBuffer and write all data
                 ;; (benchmarks showed heap is faster than direct for this use case)
                 ;; Check for integer overflow before allocating
                 _ (when (> total-size Integer/MAX_VALUE)
                    (throw (ex-info "Batch too large for single buffer"
                                   {:total-size total-size
                                    :max-size Integer/MAX_VALUE})))
                 buffer (ByteBuffer/allocate (int total-size))
                 _ (doseq [item serialized]
                    (let [length (:length item)
                          ^bytes node-bytes (:bytes item)
                          checksum (:checksum item)]
                      (.putInt buffer (unchecked-int length))
                      (.put buffer node-bytes)
                      (.putInt buffer (unchecked-int checksum))))

                 ;; Phase 5: Write entire buffer in single FileChannel.write()
                 _ (.flip buffer)
                 channel (.getChannel raf)
                 _ (.position channel start-offset)
                 _ (.write channel buffer)

                 ;; Phase 6: Strategic fsync - only once at end (2x speedup)
                 _ (.force channel true)

                 ;; Phase 7: Update cache for all nodes
                 _ (loop [i (int 0)]
                    (when (< i (count nodes))
                      (let [node (nth nodes i)
                            offset (nth offsets i)
                            cached-node (if (map? node)
                                         (assoc node :offset offset)
                                         (with-meta node (assoc (meta node) :offset offset)))]
                        (swap! node-cache assoc offset cached-node)
                        (recur (int (inc i))))))]

             ;; Return vector of offsets
             offsets)))

       storage/IStorageRoot
       (set-root-offset [this offset]
         "Set the root offset in storage (not saved until explicit save call)"
         (reset! root-offset-atom offset)
         this)

       (get-root-offset [this]
         "Get the current root offset from storage"
         @root-offset-atom)

       storage/IStorageSave
       (save [this]
         "Explicitly save the current root offset to disk header"
         (let [root-offset @root-offset-atom]
           ;; Count nodes by traversing from root
           (let [node-count (if root-offset
                             (let [visited (atom #{})]
                               (letfn [(visit-node [offset]
                                         (when-not (@visited offset)
                                           (swap! visited conj offset)
                                           (let [node (storage/restore this offset)]
                                             (when (= (:type node) :internal)
                                               (doseq [child (:children node)]
                                                 (visit-node child))))))]
                                 (visit-node root-offset)
                                 (count @visited)))
                             0)]
             ;; Write header with current root
             (write-header raf root-offset node-count btree-order)
             ;; Sync to disk
             (.force (.getChannel raf) true)
             this)))

       storage/IStorageClose
       (close [this]
         "Close the file handle"
         (.close raf))

       storage/IStorageStats
       (storage-stats [this]
         "Get storage statistics"
         {:type :disk
          :file-path file-path
          :root-offset @root-offset-atom
          :cache-size (count @node-cache)
          :file-size (.length raf)})

       storage/IStorageInvertedStrategy
       (precompute-inverted? [this]
         "Disk storage uses pre-computed inverted index (build during search-add)"
         true))

     (defn open-disk-storage
       "Open or create a disk-based storage file.

       Parameters:
       - file-path: Path to the storage file
       - btree-order: B-tree configuration (default 128)
       - create?: If true, create a new file (default false)

       Returns: DiskStorage instance"
       ([file-path]
        (open-disk-storage file-path 128 false))
       ([file-path btree-order]
        (open-disk-storage file-path btree-order false))
       ([file-path btree-order create?]
        (let [file (io/file file-path)
              exists? (.exists file)
              raf (RandomAccessFile. file "rw")]
          (if (or create? (not exists?) (zero? (.length raf)))
            ;; Create new file
            (do
              (write-header raf nil 0 btree-order)
              (->DiskStorage file-path raf (atom {}) (atom nil) btree-order))
            ;; Open existing file
            (let [header (read-header raf)]
              (->DiskStorage file-path
                            raf
                            (atom {})
                            (atom (:root-offset header))
                            (:order header)))))))

     ))

;; ClojureScript stubs
#?(:cljs
   (do
     (defn open-disk-storage [file-path]
       (throw (ex-info "Disk storage not supported in ClojureScript" {})))))
