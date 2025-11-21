(ns nebsearch.btree
  "Persistent B-tree implementation with lazy loading from disk.

  Key features:
  - Copy-on-Write (COW) semantics for structural sharing
  - Lazy node loading - only loads nodes from disk when needed
  - Dual mode: in-memory or disk-backed
  - Pluggable storage via IStorage protocol
  - Compatible with persistent-sorted-set API for [position id] pairs"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [nebsearch.storage :as storage])
  #?(:clj (:import [java.io RandomAccessFile File]
                   [java.nio ByteBuffer]
                   [java.util.zip CRC32]
                   [java.util Arrays])))

#?(:clj (set! *warn-on-reflection* true))

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
  (bt-bulk-insert [this entries] "Bulk insert multiple entries efficiently, returns new tree")
  (bt-delete [this entry] "Delete [pos id] entry, returns new tree")
  (bt-range [this start end] "Get all entries in range [start end]")
  (bt-search [this pos] "Find entry at position")
  (bt-seq [this] "Return lazy seq of all entries in order"))

;; In-memory implementation (wraps persistent sorted set)
(defrecord InMemoryBTree [data]
  IBTree
  (bt-insert [this entry]
    (->InMemoryBTree (conj data entry)))

  (bt-bulk-insert [this entries]
    (->InMemoryBTree (into data entries)))

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
                               (when (and (or (nil? end) (nil? child-min) (< child-min end))
                                         (or (nil? start) (nil? child-max) (> child-max start)))
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
        entries should be pre-sorted by [pos id].
        Much faster than repeated single inserts for large batches."
       (if (empty? entries)
         btree
         (let [stor (:storage btree)
               ;; Use Java's Arrays.sort for maximum performance with Comparable deftypes
               arr (to-array entries)
               _ (Arrays/sort arr)
               sorted-entries (vec arr)]
           (letfn [(build-leaf-level [entries]
                     "Build all leaf nodes from sorted entries"
                     (loop [remaining entries
                            leaves []]
                       (if (empty? remaining)
                         leaves
                         (let [chunk (vec (take leaf-capacity remaining))
                               next-entries (drop leaf-capacity remaining)
                               ;; For now, don't link next-leaf in bulk build
                               leaf (leaf-node chunk nil)
                               offset (storage/store stor leaf)]
                           (recur next-entries (conj leaves {:offset offset
                                                             :min-key (first (first chunk))
                                                             :max-key (first (last chunk))}))))))

                   (build-internal-level [children]
                     "Build one level of internal nodes from child nodes"
                     (if (<= (count children) 1)
                       (first children) ;; Return root
                       (loop [remaining children
                              parents []]
                         (if (empty? remaining)
                           parents
                           (let [chunk (vec (take btree-order remaining))
                                 next-remaining (drop btree-order remaining)
                                 ;; Extract keys - each key is the min of the corresponding child
                                 keys (vec (map :min-key (rest chunk)))
                                 child-offsets (vec (map :offset chunk))
                                 internal (internal-node keys child-offsets)
                                 offset (storage/store stor internal)]
                             (recur next-remaining
                                    (conj parents {:offset offset
                                                  :min-key (:min-key (first chunk))
                                                  :max-key (:max-key (last chunk))})))))))]

             ;; Build tree bottom-up
             (let [leaves (build-leaf-level sorted-entries)]
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
