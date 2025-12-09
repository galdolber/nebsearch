(ns nebsearch.btree
  "Persistent B-tree implementation with lazy loading from disk.

  Key features:
  - Copy-on-Write (COW) semantics for structural sharing
  - Lazy node loading - only loads nodes from disk when needed
  - Dual mode: in-memory or disk-backed
  - Pluggable storage via IStorage protocol
  - Compatible with persistent-sorted-set API for [position id] pairs"
  (:require #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.edn :as edn])
            [nebsearch.storage :as storage]
            [nebsearch.entries :as entries])
  #?(:clj (:import [java.io RandomAccessFile File]
                   [java.nio ByteBuffer]
                   [java.util.zip CRC32]
                   [java.util Arrays]
                   [nebsearch.entries DocumentEntry])))

#?(:clj (set! *warn-on-reflection* true))

;; B-tree configuration
(def ^:const btree-order 128) ;; Max children per internal node
(def ^:const leaf-capacity 256) ;; Max entries per leaf node
(def ^:const node-size 4096) ;; Target node size for disk alignment
(def ^:const header-size 256)
(def ^:const magic-number "NEBSRCH\0")

;; Node types using deftype for zero overhead
#?(:clj
   (do
     (deftype InternalNode [keys children ^:unsynchronized-mutable _meta]
       Object
       (equals [this other]
         (and (instance? InternalNode other)
              (let [^InternalNode o other]
                (and (= keys (.-keys o))
                     (= children (.-children o))))))

       clojure.lang.ILookup
       (valAt [this k]
         (case k
           :type :internal
           :keys keys
           :children children
           :offset (when _meta (:offset _meta))
           nil))
       (valAt [this k not-found]
         (case k
           :type :internal
           :keys keys
           :children children
           :offset (if _meta (:offset _meta) not-found)
           not-found))

       clojure.lang.IMeta
       (meta [this] _meta)

       clojure.lang.IObj
       (withMeta [this m] (InternalNode. keys children m)))

     (deftype LeafNode [entries next-leaf ^:unsynchronized-mutable _meta]
       Object
       (equals [this other]
         (and (instance? LeafNode other)
              (let [^LeafNode o other]
                (and (= entries (.-entries o))
                     (= next-leaf (.-next-leaf o))))))

       clojure.lang.ILookup
       (valAt [this k]
         (case k
           :type :leaf
           :entries entries
           :next-leaf next-leaf
           :offset (when _meta (:offset _meta))
           nil))
       (valAt [this k not-found]
         (case k
           :type :leaf
           :entries entries
           :next-leaf next-leaf
           :offset (if _meta (:offset _meta) not-found)
           not-found))

       clojure.lang.IMeta
       (meta [this] _meta)

       clojure.lang.IObj
       (withMeta [this m] (LeafNode. entries next-leaf m)))

     (defn internal-node [keys children]
       (->InternalNode (vec keys) (vec children) nil))

     (defn leaf-node [entries next-leaf]
       (->LeafNode (vec entries) next-leaf nil)))

   :cljs
   (do
     ;; ClojureScript uses maps for now
     (defn internal-node [keys children]
       {:type :internal
        :keys (vec keys)
        :children (vec children)})

     (defn leaf-node [entries next-leaf]
       {:type :leaf
        :entries (vec entries)
        :next-leaf next-leaf})))

(defn node-type [node]
  (:type node))

;; In-memory B-tree (compatible with current sorted-set behavior)
(defprotocol IBTree
  "Protocol for B-tree operations"
  (bt-insert [this entry] "Insert [pos id] entry, returns new tree")
  (bt-bulk-insert [this entries] "Bulk insert multiple entries efficiently, returns new tree")
  (bt-delete [this entry] "Delete [pos id] entry, returns new tree")
  (bt-range [this start end] "Get all entries in range [start end]")
  (bt-search [this pos] "Find entry at position")
  (bt-seq [this] "Return lazy seq of all entries in order"))

;; In-memory implementation (wraps persistent sorted set)
(defrecord InMemoryBTree [data]
  IBTree
  (bt-insert [this entry]
    (InMemoryBTree. (conj data entry)))

  (bt-bulk-insert [this entries]
    (InMemoryBTree. (into data entries)))

  (bt-delete [this entry]
    (InMemoryBTree. (disj data entry)))

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

;; File-backed B-tree with lazy loading via pluggable storage
#?(:clj
   (do
     ;; Forward declarations for mutual recursion
     (declare bt-insert-impl bt-bulk-insert-impl bt-delete-impl bt-range-impl bt-search-impl bt-seq-impl)

     (defrecord DurableBTree [storage    ;; IStorage implementation
                              root-offset] ;; Current root address (not saved until explicit save)
       IBTree
       (bt-insert [this entry]
         ;; For now, delegate to helper function
         (bt-insert-impl this entry))

       (bt-bulk-insert [this entries]
         (bt-bulk-insert-impl this entries))

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
           (let [node (storage/restore (:storage btree) node-offset)]
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
       "Get all entries in range [start, end].
        Traverses tree structure (not next-leaf pointers) for COW correctness.
        Optimizes by pruning subtrees outside the range."
       (when-let [root-off (:root-offset btree)]
         (letfn [(node-range [node-offset]
                   (lazy-seq
                    (let [node (storage/restore (:storage btree) node-offset)]
                      (case (:type node)
                        :leaf
                        ;; Filter leaf entries by range
                        (filter (fn [[pos _]]
                                  (and (or (nil? start) (>= pos start))
                                       (or (nil? end) (<= pos end))))
                                (:entries node))

                        :internal
                        ;; For internal nodes, only visit children that might contain entries in range
                        (let [keys (:keys node)
                              children (:children node)]
                          ;; Determine which children to visit based on range
                          (mapcat
                           (fn [i]
                             (let [child-min (if (zero? i) nil (nth keys (dec i)))
                                   child-max (when (< i (count keys)) (nth keys i))]
                               ;; Visit child if its range [child-min, child-max] overlaps [start, end]
                               ;; Use <= and >= to include boundary values (critical for exact match queries)
                               (when (and (or (nil? end) (nil? child-min) (<= child-min end))
                                         (or (nil? start) (nil? child-max) (>= child-max start)))
                                 (node-range (nth children i)))))
                           (range (count children))))))))]
           (vec (node-range root-off)))))

     (defn- bt-seq-impl [btree]
       "Return lazy seq of all entries by traversing tree structure (not next-leaf pointers).
        This is correct for COW trees where next-leaf pointers may point to old versions."
       (when-let [root-off (:root-offset btree)]
         (letfn [(node-seq [node-offset]
                   (lazy-seq
                    (let [node (storage/restore (:storage btree) node-offset)]
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
             stor (:storage btree)]
         (if-not (:root-offset btree)
           ;; Empty tree - create first leaf
           (let [new-leaf (leaf-node [entry] nil)
                 new-offset (storage/store stor new-leaf)]
             ;; Don't write header - that's done on explicit save
             (assoc btree :root-offset new-offset))

           ;; Insert into existing tree
           (letfn [(insert-into-node [node-offset path]
                     (let [node (storage/restore stor node-offset)]
                       (case (:type node)
                         :leaf
                         ;; Insert into leaf
                         (let [entries (:entries node)
                               ;; Sort by full entry [pos id], not just position
                               ;; Use Arrays.sort for maximum performance with Comparable deftypes
                               arr (to-array (conj entries entry))
                               _ (Arrays/sort arr)
                               new-entries (vec arr)]
                           (if (<= (count new-entries) leaf-capacity)
                             ;; Fits in leaf, write new version
                             (let [new-leaf (leaf-node new-entries (:next-leaf node))
                                   new-offset (storage/store stor new-leaf)]
                               {:offset new-offset :split nil})
                             ;; Split required
                             (let [mid (quot (count new-entries) 2)
                                   left-entries (subvec new-entries 0 mid)
                                   right-entries (subvec new-entries mid)
                                   right-leaf (leaf-node right-entries (:next-leaf node))
                                   right-offset (storage/store stor right-leaf)
                                   left-leaf (leaf-node left-entries right-offset)
                                   left-offset (storage/store stor left-leaf)
                                   split-key (first (first right-entries))]
                               {:offset left-offset
                                :split {:key split-key :right-offset right-offset}})))

                         :internal
                         ;; Descend into child
                         (let [keys (:keys node)
                               children (:children node)
                               idx (or (first (keep-indexed (fn [i k] (when (neg? (compare pos k)) i)) keys))
                                       (count keys))
                               child-result (insert-into-node (nth children idx) (conj path idx))]
                           (if-not (:split child-result)
                             ;; No split, just update child pointer
                             (let [new-children (assoc children idx (:offset child-result))
                                   new-node (internal-node keys new-children)
                                   new-offset (storage/store stor new-node)]
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
                                       new-offset (storage/store stor new-node)]
                                   {:offset new-offset :split nil})
                                 ;; Split internal node
                                 (let [mid (quot (count new-children) 2)
                                       split-key (nth new-keys (dec mid))
                                       left-keys (subvec new-keys 0 (dec mid))
                                       right-keys (subvec new-keys mid)
                                       left-children (subvec new-children 0 mid)
                                       right-children (subvec new-children mid)
                                       right-node (internal-node right-keys right-children)
                                       right-offset (storage/store stor right-node)
                                       left-node (internal-node left-keys left-children)
                                       left-offset (storage/store stor left-node)]
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
                       new-root-offset (storage/store stor new-root)]
                   ;; DON'T write header - return new btree with new root offset
                   (assoc btree :root-offset new-root-offset))))))))

     (defn- bt-delete-impl [btree entry]
       "Delete entry using COW semantics (simplified - no merging for now)"
       (let [[pos id] entry
             stor (:storage btree)]
         (when (:root-offset btree)
           (letfn [(delete-from-node [node-offset]
                     (let [node (storage/restore stor node-offset)]
                       (case (:type node)
                         :leaf
                         (let [entries (:entries node)
                               ;; Compare only [pos id], ignore text if present
                               new-entries (vec (remove #(and (= (first %) pos)
                                                              (= (second %) id))
                                                       entries))]
                           (if (seq new-entries)
                             (let [new-leaf (leaf-node new-entries (:next-leaf node))
                                   new-offset (storage/store stor new-leaf)]
                               new-offset)
                             ;; Empty leaf - for now just write it (TODO: merge with sibling)
                             (let [new-leaf (leaf-node [] (:next-leaf node))
                                   new-offset (storage/store stor new-leaf)]
                               new-offset)))

                         :internal
                         (let [keys (:keys node)
                               children (:children node)
                               idx (or (first (keep-indexed (fn [i k] (when (neg? (compare pos k)) i)) keys))
                                       (count keys))
                               new-child-offset (delete-from-node (nth children idx))
                               new-children (assoc children idx new-child-offset)
                               new-node (internal-node keys new-children)]
                           (storage/store stor new-node)))))]
             (let [new-root-offset (delete-from-node (:root-offset btree))]
               ;; DON'T write header - that breaks COW! Header only written on explicit save
               (assoc btree :root-offset new-root-offset))))))

     (defn- bt-bulk-insert-impl [btree entries]
       "Bulk insert entries efficiently by building tree bottom-up.
        Entries are sorted using TimSort (optimized for < 100K entries).
        Much faster than repeated single inserts for large batches."
       (if (empty? entries)
         btree
         (let [stor (:storage btree)
               ;; Sort and KEEP as array - use TimSort for small/medium datasets
               ;; TimSort is faster than radix for < 100K entries (8ms vs 25ms for 10K)
               arr (to-array entries)
               _ (Arrays/sort arr)  ; TimSort: adaptive, fast for nearly-sorted data
               ^objects sorted-arr arr
               arr-len (int (alength sorted-arr))]
           (letfn [(build-leaf-level [^objects sorted-arr]
                     "Build all leaf nodes using FULL Phase 1 optimizations (5-8x faster)"
                     (let [arr-len (int (alength sorted-arr))
                           ;; Phase 1: Collect all leaf nodes using transients + array slicing
                           leaf-data (loop [offset (int 0)
                                           leaves (transient [])]
                                      (if (>= offset arr-len)
                                        (persistent! leaves)
                                        (let [end (int (min (+ offset leaf-capacity) arr-len))
                                              ;; Array slicing (2-3x faster than take/drop)
                                              chunk-arr (Arrays/copyOfRange sorted-arr offset end)
                                              chunk-vec (vec chunk-arr)
                                              ;; Cache first/last - extract LONG keys via protocol
                                              ;; DocumentEntry: first returns pos (long)
                                              ;; InvertedEntry: first returns word-hash (long)
                                              ;; Both produce longs - FULL SPEED with no type checking!
                                              first-entry (aget chunk-arr 0)
                                              last-entry (aget chunk-arr (int (dec (alength chunk-arr))))
                                              first-key (first first-entry)  ;; Always returns long!
                                              last-key (first last-entry)    ;; Always returns long!
                                              leaf (leaf-node chunk-vec nil)]
                                          (recur end
                                                 (conj! leaves {:node leaf
                                                               :min-key first-key
                                                               :max-key last-key})))))
                           ;; Phase 2: Batch store all leaves (2-4x faster than individual stores)
                           nodes (mapv :node leaf-data)
                           offsets (if (satisfies? storage/IBatchedStorage stor)
                                    (storage/batch-store stor nodes)
                                    ;; Fallback to individual stores if batching not supported
                                    (mapv #(storage/store stor %) nodes))]
                       ;; Phase 3: Return leaves with offsets (use transient for building)
                       (let [result-count (int (count leaf-data))]
                         (persistent!
                          (loop [i (int 0)
                                 result (transient [])]
                            (if (>= i result-count)
                              result
                              (let [data (nth leaf-data i)
                                    offset (nth offsets i)]
                                (recur (int (inc i))
                                       (conj! result {:offset offset
                                                     :min-key (:min-key data)
                                                     :max-key (:max-key data)})))))))))

                   (build-internal-level [children]
                     "Build one level using FULL Phase 1 optimizations (5-8x faster)"
                     (if (<= (count children) 1)
                       (first children) ;; Return root
                       (let [n-children (int (count children))
                             ;; Phase 1: Collect all internal nodes using transients + loop/recur
                             internal-data (loop [offset (int 0)
                                                parents (transient [])]
                                           (if (>= offset n-children)
                                             (persistent! parents)
                                             (let [end (int (min (+ offset btree-order) n-children))
                                                   ;; Use subvec (O(1) vs take/drop O(n))
                                                   chunk (subvec children offset end)
                                                   chunk-len (int (count chunk))
                                                   ;; Cache first/last
                                                   first-child (nth chunk 0)
                                                   last-child (nth chunk (int (dec chunk-len)))
                                                   ;; Extract keys with loop+transient (vs map)
                                                   keys (persistent!
                                                         (loop [i (int 1)
                                                                ks (transient [])]
                                                           (if (>= i chunk-len)
                                                             ks
                                                             (recur (int (inc i))
                                                                    (conj! ks (:min-key (nth chunk i)))))))
                                                   ;; Extract child offsets with loop+transient
                                                   child-offsets (persistent!
                                                                  (loop [i (int 0)
                                                                         offs (transient [])]
                                                                    (if (>= i chunk-len)
                                                                      offs
                                                                      (recur (int (inc i))
                                                                             (conj! offs (:offset (nth chunk i)))))))
                                                   internal (internal-node keys child-offsets)]
                                               (recur end
                                                      (conj! parents {:node internal
                                                                     :min-key (:min-key first-child)
                                                                     :max-key (:max-key last-child)})))))
                             ;; Phase 2: Batch store all internal nodes (2-4x faster)
                             nodes (mapv :node internal-data)
                             offsets (if (satisfies? storage/IBatchedStorage stor)
                                      (storage/batch-store stor nodes)
                                      ;; Fallback to individual stores if batching not supported
                                      (mapv #(storage/store stor %) nodes))]
                         ;; Phase 3: Return internal nodes with offsets (use transient)
                         (let [result-count (int (count internal-data))]
                           (persistent!
                            (loop [i (int 0)
                                   result (transient [])]
                              (if (>= i result-count)
                                result
                                (let [data (nth internal-data i)
                                      offset (nth offsets i)]
                                  (recur (int (inc i))
                                         (conj! result {:offset offset
                                                       :min-key (:min-key data)
                                                       :max-key (:max-key data)}))))))))))]

             ;; Build tree bottom-up
             (let [leaves (build-leaf-level sorted-arr)]
               (loop [level leaves]
                 (if (<= (count level) 1)
                   ;; Done! We have the root
                   (assoc btree :root-offset (:offset (first level)))
                   ;; Build next level
                   (recur (build-internal-level level)))))))))

     (defn open-btree
       "Open or create a durable B-tree with the given storage implementation.

       Parameters:
       - storage: An implementation of IStorage protocol

       Returns: DurableBTree instance using the provided storage"
       [storage]
       ;; Try to get root offset from storage if it supports IStorageRoot
       (let [root-offset (when (satisfies? storage/IStorageRoot storage)
                          (storage/get-root-offset storage))]
         (->DurableBTree storage root-offset)))

     (defn close-btree [btree]
       "Close the B-tree storage"
       (when-let [storage (:storage btree)]
         (when (satisfies? storage/IStorageClose storage)
           (storage/close storage))))

     (defn- count-nodes [btree]
       "Count total number of nodes in the tree by traversal"
       (if-not (:root-offset btree)
         0
         (let [visited (atom #{})
               stor (:storage btree)]
           (letfn [(visit-node [offset]
                     (when-not (@visited offset)
                       (swap! visited conj offset)
                       (let [node (storage/restore stor offset)]
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
         (merge
          {:type :durable
           :root-offset (:root-offset btree)
           :node-count (count-nodes btree)}
          (when (satisfies? storage/IStorageStats (:storage btree))
            (storage/storage-stats (:storage btree))))))

     (defn btree-save [btree]
       "Explicitly save the B-tree state to storage.
        This is the ONLY way to make changes durable.
        All bt-insert, bt-delete operations are in-memory until this is called."
       (when-let [stor (:storage btree)]
         ;; Update storage with current root offset if it supports IStorageRoot
         (when (satisfies? storage/IStorageRoot stor)
           (storage/set-root-offset stor (:root-offset btree)))
         ;; Explicitly save if it supports IStorageSave
         (when (satisfies? storage/IStorageSave stor)
           (storage/save stor)))
       btree)))

;; ClojureScript stubs (not implemented yet)
#?(:cljs
   (do
     (defn open-btree [file-path]
       (throw (ex-info "Durable B-tree not supported in ClojureScript" {})))

     (defn close-btree [btree]
       nil)

     (defn btree-stats [btree]
       {:type :in-memory
        :size (count (:data btree))})))
