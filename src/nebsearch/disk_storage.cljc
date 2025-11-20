(ns nebsearch.disk-storage
  "Disk-based storage implementation for nebsearch B-tree nodes.

  Features:
  - RandomAccessFile for efficient random access
  - CRC32 checksums for data integrity
  - Node caching for performance
  - Atomic updates via write-ahead approach
  - Explicit save semantics (no automatic flush)"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [nebsearch.storage :as storage])
  #?(:clj (:import [java.io RandomAccessFile File]
                   [java.nio ByteBuffer]
                   [java.util.zip CRC32])))

#?(:clj
   (do
     ;; File format constants
     (def ^:const header-size 256)
     (def ^:const magic-number "NEBSRCH\0")

     ;; Serialization utilities
     (defn- crc32 [^bytes data]
       "Calculate CRC32 checksum"
       (let [crc (CRC32.)]
         (.update crc data)
         (.getValue crc)))

     (defn- serialize-node [node]
       "Serialize node to EDN bytes"
       (let [edn-str (pr-str (dissoc node :offset :cached))
             bytes (.getBytes edn-str "UTF-8")]
         bytes))

     (defn- deserialize-node [^bytes data offset]
       "Deserialize node from EDN bytes"
       (let [edn-str (String. data "UTF-8")
             node (edn/read-string edn-str)]
         (assoc node :offset offset)))

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
           (let [node-bytes (serialize-node node)
                 checksum (crc32 node-bytes)
                 length (alength node-bytes)]
             ;; Write: length (4) + data (n) + checksum (4)
             (.writeInt raf length)
             (.write raf node-bytes)
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
          :file-size (.length raf)}))

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
