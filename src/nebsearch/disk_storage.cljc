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
            [nebsearch.entries :as entries])
  #?(:clj (:import [java.io RandomAccessFile File ByteArrayOutputStream ByteArrayInputStream
                    DataOutputStream DataInputStream]
                   [java.nio ByteBuffer]
                   [java.util.zip CRC32]
                   [nebsearch.entries DocumentEntry InvertedEntry])))

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

     (defn- write-string [^DataOutputStream dos ^String s]
       (let [bytes (.getBytes s "UTF-8")]
         (.writeInt dos (alength bytes))
         (.write dos bytes)))

     (defn- read-utf8-string [^DataInputStream dis]
       (let [len (.readInt dis)
             bytes (byte-array len)]
         (.readFully dis bytes)
         (String. bytes "UTF-8")))

     (defn- serialize-node [node]
       "Custom binary serialization optimized for B-tree nodes"
       (let [baos (ByteArrayOutputStream.)
             dos (DataOutputStream. baos)
             node-data (dissoc node :offset :cached)]
         ;; Write node type (1 byte: 0=internal, 1=leaf)
         (.writeByte dos (if (= (:type node-data) :internal) 0 1))

         (if (= (:type node-data) :internal)
           ;; Internal node: keys + children
           ;; Keys can be either Long (document B-tree) or String (inverted index)
           (let [keys (:keys node-data)
                 children (:children node-data)
                 first-key (first keys)
                 is-long-keys (instance? Long first-key)]
             ;; Write key type: 0=Long, 1=String
             (.writeByte dos (if is-long-keys 0 1))
             (.writeInt dos (count keys))
             (if is-long-keys
               (doseq [k keys] (.writeLong dos k))
               (doseq [k keys] (write-string dos k)))
             (.writeInt dos (count children))
             (doseq [c children] (.writeLong dos c))))

           ;; Leaf node: entries + next-leaf
           ;; Entries can be either:
           ;;   - Document B-tree: [pos id text] where pos is Long
           ;;   - Inverted index B-tree: [word doc-id] where word is String
           (let [entries (:entries node-data)
                 next-leaf (:next-leaf node-data)]
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
                     (write-string dos id)
                     (if text
                       (do
                         (.writeBoolean dos true)
                         (write-string dos text))
                       (.writeBoolean dos false))))

                 ;; Inverted index B-tree entry (deftype)
                 (instance? InvertedEntry entry)
                 (do
                   (.writeByte dos 1) ;; entry type: 1 = inverted
                   (let [^InvertedEntry e entry
                         word (.-word e)
                         doc-id (.-doc-id e)]
                     (write-string dos word)
                     (write-string dos doc-id)))

                 ;; Backwards compatibility: vector entries
                 (vector? entry)
                 (let [first-elem (first entry)]
                   (if (instance? Long first-elem)
                     ;; Document B-tree entry: [pos id text]
                     (do
                       (.writeByte dos 0)
                       (let [[pos id text] entry]
                         (.writeLong dos pos)
                         (write-string dos id)
                         (if text
                           (do
                             (.writeBoolean dos true)
                             (write-string dos text))
                           (.writeBoolean dos false))))
                     ;; Inverted index B-tree entry: [word doc-id]
                     (do
                       (.writeByte dos 1)
                       (let [[word doc-id] entry]
                         (write-string dos word)
                         (write-string dos doc-id)))))

                 :else
                 (throw (ex-info "Unknown entry type in B-tree node"
                                {:entry entry :type (type entry)}))))
             (if next-leaf
               (do
                 (.writeBoolean dos true)
                 (.writeLong dos next-leaf))
               (.writeBoolean dos false)))

         ;; Return serialized bytes
         (.toByteArray baos)))

     (defn- deserialize-node [^bytes data offset]
       "Custom binary deserialization optimized for B-tree nodes"
       (let [bais (ByteArrayInputStream. data)
             dis (DataInputStream. bais)
             node-type (.readByte dis)]

         (if (= node-type 0)
           ;; Internal node
           (let [key-type (.readByte dis)
                 key-count (.readInt dis)
                 keys (if (= key-type 0)
                       ;; Long keys (document B-tree)
                       (vec (repeatedly key-count #(.readLong dis)))
                       ;; String keys (inverted index B-tree)
                       (vec (repeatedly key-count #(read-utf8-string dis))))
                 child-count (.readInt dis)
                 children (vec (repeatedly child-count #(.readLong dis)))]
             {:type :internal
              :keys keys
              :children children
              :offset offset})

           ;; Leaf node
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
                 next-leaf (when has-next-leaf (.readLong dis))]
             {:type :leaf
              :entries entries
              :next-leaf next-leaf
              :offset offset}))))

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
             ;; Cache the node
             (swap! node-cache assoc offset (assoc node :offset offset))
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
