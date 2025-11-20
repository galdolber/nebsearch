(ns nebsearch.btree
  "Persistent B-tree implementation with lazy loading from disk.

  Key features:
  - Copy-on-Write (COW) semantics for structural sharing
  - Lazy node loading - only loads nodes from disk when needed
  - Dual mode: in-memory or disk-backed
  - Compatible with persistent-sorted-set API for [position id] pairs"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  #?(:clj (:import [java.io RandomAccessFile File]
                   [java.nio ByteBuffer]
                   [java.util.zip CRC32])))

;; B-tree configuration
(def ^:const btree-order 128) ;; Max children per internal node
(def ^:const leaf-capacity 256) ;; Max entries per leaf node
(def ^:const node-size 4096) ;; Target node size for disk alignment
(def ^:const header-size 256)
(def ^:const magic-number "NEBSRCH\0")

;; Node types
(defn internal-node [keys children]
  {:type :internal
   :keys (vec keys)
   :children (vec children)})

(defn leaf-node [entries next-leaf]
  {:type :leaf
   :entries (vec entries)
   :next-leaf next-leaf})

(defn node-type [node]
  (:type node))

;; In-memory B-tree (compatible with current sorted-set behavior)
(defprotocol IBTree
  "Protocol for B-tree operations"
  (bt-insert [this entry] "Insert [pos id] entry, returns new tree")
  (bt-delete [this entry] "Delete [pos id] entry, returns new tree")
  (bt-range [this start end] "Get all entries in range [start end]")
  (bt-search [this pos] "Find entry at position")
  (bt-seq [this] "Return lazy seq of all entries in order"))

;; In-memory implementation (wraps persistent sorted set)
(defrecord InMemoryBTree [data]
  IBTree
  (bt-insert [this entry]
    (->InMemoryBTree (conj data entry)))

  (bt-delete [this entry]
    (->InMemoryBTree (disj data entry)))

  (bt-range [this start end]
    (if (and start end)
      (into [] (filter (fn [[pos _]] (<= start pos end)) data))
      (vec data)))

  (bt-search [this pos]
    (first (filter (fn [[p _]] (= p pos)) data)))

  (bt-seq [this]
    (seq data)))

(defn in-memory-btree [sorted-set]
  "Create an in-memory B-tree from a persistent sorted set"
  (->InMemoryBTree sorted-set))

;; File-backed B-tree with lazy loading
#?(:clj
   (do
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

     (defn- write-header [^RandomAccessFile raf root-offset node-count]
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

     (defn- write-node [^RandomAccessFile raf node]
       "Write node to file, returns offset where it was written"
       (let [offset (.length raf)]
         (.seek raf offset)
         (let [node-bytes (serialize-node node)
               checksum (crc32 node-bytes)
               length (alength node-bytes)]
           ;; Write: length (4) + data (n) + checksum (4)
           (.writeInt raf length)
           (.write raf node-bytes)
           (.writeInt raf (unchecked-int checksum))
           offset)))

     (defn- read-node [^RandomAccessFile raf offset node-cache]
       "Read node from file with caching"
       (if-let [cached (get @node-cache offset)]
         cached
         (do
           (.seek raf offset)
           (let [length (.readInt raf)
                 node-bytes (byte-array length)]
             (.read raf node-bytes)
             (let [stored-checksum (unchecked-int (.readInt raf))
                   computed-checksum (unchecked-int (crc32 node-bytes))]
               (when (not= stored-checksum computed-checksum)
                 (throw (ex-info "Checksum mismatch" {:offset offset
                                                       :stored stored-checksum
                                                       :computed computed-checksum})))
               (let [node (deserialize-node node-bytes offset)]
                 (swap! node-cache assoc offset node)
                 node))))))

     ;; Forward declarations for mutual recursion
     (declare bt-insert-impl bt-delete-impl bt-range-impl bt-search-impl bt-seq-impl)

     (defrecord DurableBTree [file-path raf root-offset node-cache]
       IBTree
       (bt-insert [this entry]
         ;; For now, delegate to helper function
         (bt-insert-impl this entry))

       (bt-delete [this entry]
         (bt-delete-impl this entry))

       (bt-range [this start end]
         (bt-range-impl this start end))

       (bt-search [this pos]
         (bt-search-impl this pos))

       (bt-seq [this]
         (bt-seq-impl this)))

     ;; Helper functions for DurableBTree operations
     (defn- bt-search-impl [btree pos]
       "Search for entry at position"
       (when-let [root-off (:root-offset btree)]
         (loop [node-offset root-off]
           (let [node (read-node (:raf btree) node-offset (:node-cache btree))]
             (case (:type node)
               :leaf
               (first (filter (fn [[p _]] (= p pos)) (:entries node)))

               :internal
               (let [keys (:keys node)
                     children (:children node)
                     ;; Find which child to descend into
                     idx (or (first (keep-indexed (fn [i k] (when (< pos k) i)) keys))
                             (count keys))]
                 (recur (nth children idx))))))))

     (defn- bt-range-impl [btree start end]
       "Get all entries in range [start, end]"
       (when-let [root-off (:root-offset btree)]
         ;; Find the first leaf containing start
         (loop [node-offset root-off]
           (let [node (read-node (:raf btree) node-offset (:node-cache btree))]
             (case (:type node)
               :leaf
               ;; Found a leaf, collect entries in range
               (loop [current-leaf node
                      result (transient [])]
                 (let [entries (filter (fn [[pos _]]
                                         (and (or (nil? start) (>= pos start))
                                              (or (nil? end) (<= pos end))))
                                       (:entries current-leaf))
                       result' (reduce conj! result entries)]
                   (if (and (:next-leaf current-leaf)
                            (or (nil? end)
                                (< (first (first (:entries current-leaf))) end)))
                     (recur (read-node (:raf btree) (:next-leaf current-leaf) (:node-cache btree))
                            result')
                     (persistent! result'))))

               :internal
               (let [keys (:keys node)
                     children (:children node)
                     idx (or (first (keep-indexed (fn [i k] (when (< (or start 0) k) i)) keys))
                             (count keys))]
                 (recur (nth children idx))))))))

     (defn- bt-seq-impl [btree]
       "Return lazy seq of all entries by traversing tree structure (not next-leaf pointers).
        This is correct for COW trees where next-leaf pointers may point to old versions."
       (when-let [root-off (:root-offset btree)]
         (letfn [(node-seq [node-offset]
                   (lazy-seq
                    (let [node (read-node (:raf btree) node-offset (:node-cache btree))]
                      (case (:type node)
                        :leaf
                        (:entries node)

                        :internal
                        ;; Recursively get entries from all children
                        (mapcat node-seq (:children node))))))]
           (node-seq root-off))))

     ;; COW insert operation
     (defn- bt-insert-impl [btree entry]
       "Insert entry using COW semantics"
       (let [[pos id] entry
             raf (:raf btree)
             cache (:node-cache btree)]
         (if-not (:root-offset btree)
           ;; Empty tree - create first leaf
           (let [new-leaf (leaf-node [entry] nil)
                 new-offset (write-node raf new-leaf)]
             (write-header raf new-offset 1)
             (assoc btree :root-offset new-offset))

           ;; Insert into existing tree
           (letfn [(insert-into-node [node-offset path]
                     (let [node (read-node raf node-offset cache)]
                       (case (:type node)
                         :leaf
                         ;; Insert into leaf
                         (let [entries (:entries node)
                               ;; Sort by full entry [pos id], not just position
                               new-entries (vec (sort (conj entries entry)))]
                           (if (<= (count new-entries) leaf-capacity)
                             ;; Fits in leaf, write new version
                             (let [new-leaf (leaf-node new-entries (:next-leaf node))
                                   new-offset (write-node raf new-leaf)]
                               {:offset new-offset :split nil})
                             ;; Split required
                             (let [mid (quot (count new-entries) 2)
                                   left-entries (subvec new-entries 0 mid)
                                   right-entries (subvec new-entries mid)
                                   right-leaf (leaf-node right-entries (:next-leaf node))
                                   right-offset (write-node raf right-leaf)
                                   left-leaf (leaf-node left-entries right-offset)
                                   left-offset (write-node raf left-leaf)
                                   split-key (first (first right-entries))]
                               {:offset left-offset
                                :split {:key split-key :right-offset right-offset}})))

                         :internal
                         ;; Descend into child
                         (let [keys (:keys node)
                               children (:children node)
                               idx (or (first (keep-indexed (fn [i k] (when (< pos k) i)) keys))
                                       (count keys))
                               child-result (insert-into-node (nth children idx) (conj path idx))]
                           (if-not (:split child-result)
                             ;; No split, just update child pointer
                             (let [new-children (assoc children idx (:offset child-result))
                                   new-node (internal-node keys new-children)
                                   new-offset (write-node raf new-node)]
                               {:offset new-offset :split nil})
                             ;; Child split, insert new key
                             (let [split (:split child-result)
                                   new-keys (vec (concat (take idx keys)
                                                         [(:key split)]
                                                         (drop idx keys)))
                                   new-children (vec (concat (take idx children)
                                                             [(:offset child-result)]
                                                             [(:right-offset split)]
                                                             (drop (inc idx) children)))]
                               (if (<= (count new-children) btree-order)
                                 ;; Fits in node
                                 (let [new-node (internal-node new-keys new-children)
                                       new-offset (write-node raf new-node)]
                                   {:offset new-offset :split nil})
                                 ;; Split internal node
                                 (let [mid (quot (count new-children) 2)
                                       split-key (nth new-keys (dec mid))
                                       left-keys (subvec new-keys 0 (dec mid))
                                       right-keys (subvec new-keys mid)
                                       left-children (subvec new-children 0 mid)
                                       right-children (subvec new-children mid)
                                       right-node (internal-node right-keys right-children)
                                       right-offset (write-node raf right-node)
                                       left-node (internal-node left-keys left-children)
                                       left-offset (write-node raf left-node)]
                                   {:offset left-offset
                                    :split {:key split-key :right-offset right-offset}}))))))))]

             (let [result (insert-into-node (:root-offset btree) [])]
               (if-not (:split result)
                 ;; No split at root, return new btree with updated root
                 ;; DON'T write header - that breaks COW! Header only written on serialize/flush
                 (assoc btree :root-offset (:offset result))
                 ;; Root split, create new root
                 (let [split (:split result)
                       new-root (internal-node [(:key split)]
                                               [(:offset result) (:right-offset split)])
                       new-root-offset (write-node raf new-root)]
                   ;; DON'T write header - return new btree with new root offset
                   (assoc btree :root-offset new-root-offset))))))))

     (defn- bt-delete-impl [btree entry]
       "Delete entry using COW semantics (simplified - no merging for now)"
       (let [[pos id] entry
             raf (:raf btree)
             cache (:node-cache btree)]
         (when (:root-offset btree)
           (letfn [(delete-from-node [node-offset]
                     (let [node (read-node raf node-offset cache)]
                       (case (:type node)
                         :leaf
                         (let [entries (:entries node)
                               new-entries (vec (remove #(= % entry) entries))]
                           (if (seq new-entries)
                             (let [new-leaf (leaf-node new-entries (:next-leaf node))
                                   new-offset (write-node raf new-leaf)]
                               new-offset)
                             ;; Empty leaf - for now just write it (TODO: merge with sibling)
                             (let [new-leaf (leaf-node [] (:next-leaf node))
                                   new-offset (write-node raf new-leaf)]
                               new-offset)))

                         :internal
                         (let [keys (:keys node)
                               children (:children node)
                               idx (or (first (keep-indexed (fn [i k] (when (< pos k) i)) keys))
                                       (count keys))
                               new-child-offset (delete-from-node (nth children idx))
                               new-children (assoc children idx new-child-offset)
                               new-node (internal-node keys new-children)]
                           (write-node raf new-node)))))]
             (let [new-root-offset (delete-from-node (:root-offset btree))]
               ;; DON'T write header - that breaks COW! Header only written on serialize/flush
               (assoc btree :root-offset new-root-offset))))))

     (defn open-btree
       "Open or create a durable B-tree file"
       ([file-path]
        (open-btree file-path false))
       ([file-path create?]
        (let [file (io/file file-path)
              exists? (.exists file)
              raf (RandomAccessFile. file "rw")]
          (if (or create? (not exists?) (zero? (.length raf)))
            ;; Create new file
            (do
              (write-header raf nil 0)
              (->DurableBTree file-path raf nil (atom {})))
            ;; Open existing file
            (let [header (read-header raf)]
              (->DurableBTree file-path raf (:root-offset header) (atom {})))))))

     (defn close-btree [btree]
       "Close the B-tree file"
       (when-let [raf (:raf btree)]
         (.close ^RandomAccessFile raf)))

     (defn- count-nodes [btree]
       "Count total number of nodes in the tree by traversal"
       (if-not (:root-offset btree)
         0
         (let [visited (atom #{})
               raf (:raf btree)
               cache (:node-cache btree)]
           (letfn [(visit-node [offset]
                     (when-not (@visited offset)
                       (swap! visited conj offset)
                       (let [node (read-node raf offset cache)]
                         (when (= (:type node) :internal)
                           (doseq [child (:children node)]
                             (visit-node child))))))]
             (visit-node (:root-offset btree))
             (count @visited)))))

     (defn btree-stats [btree]
       "Get statistics about the B-tree"
       (if (instance? InMemoryBTree btree)
         {:type :in-memory
          :size (count (:data btree))}
         {:type :durable
          :root-offset (:root-offset btree)
          :node-count (count-nodes btree)
          :cache-size (count @(:node-cache btree))
          :file-size (.length ^RandomAccessFile (:raf btree))}))

     (defn btree-flush [btree]
       "Ensure all data is written to disk"
       (when-let [raf (:raf btree)]
         (.getChannel ^RandomAccessFile raf)
         (.force (.getChannel ^RandomAccessFile raf) true)))))

;; ClojureScript stubs (not implemented yet)
#?(:cljs
   (do
     (defn open-btree [file-path]
       (throw (ex-info "Durable B-tree not supported in ClojureScript" {})))

     (defn close-btree [btree]
       nil)

     (defn btree-stats [btree]
       {:type :in-memory
        :size (count (:data btree))})

     (defn btree-flush [btree]
       nil)))
