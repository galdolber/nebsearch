(ns nebsearch.core
  #?(:clj (:gen-class))
  (:require [clojure.string :as string]
            [clojure.set :as sets]
            [nebsearch.btree :as bt]
            [nebsearch.storage :as storage]
            #?(:clj [nebsearch.memory-storage :as mem-storage])))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn normalize ^String [^String str]
     (let [^String normalized (java.text.Normalizer/normalize str java.text.Normalizer$Form/NFD)]
       (clojure.string/replace normalized #"\p{InCombiningDiacriticalMarks}+" "")))

   :cljs (defn normalize [^string s]
           (let [^string s (.normalize s "NFD")]
             (clojure.string/replace s #"[\u0300-\u036f]" ""))))

(def join-char \ñ) ;; normalized to 'n' by encoder, ensures it never appears in indexed text

;; Performance tuning parameters
(def ^:dynamic *cache-size* 1000)  ;; Max cache entries (LRU)
(def ^:dynamic *auto-gc-threshold* 0.3)  ;; Auto-GC when >30% fragmented
(def ^:dynamic *batch-threshold* 100)  ;; Use StringBuilder for batches >100

;; Cross-platform timestamp function
(defn- current-time-millis []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

;; LRU Cache implementation
(defn- lru-cache-evict [cache max-size]
  (if (<= (count cache) max-size)
    cache
    (let [;; Remove oldest 20% when threshold exceeded
          keep-count (long (* max-size 0.8))
          sorted-entries (sort-by (comp :access-time val) > cache)]
      (into {} (take keep-count sorted-entries)))))

(defn- lru-cache-get [cache-atom key]
  (let [cache @cache-atom
        entry (get cache key)]
    (when entry
      (swap! cache-atom assoc-in [key :access-time] (current-time-millis))
      (:value entry))))

(defn- lru-cache-put [cache-atom key value]
  (swap! cache-atom
         (fn [cache]
           (let [new-cache (assoc cache key {:value value
                                              :access-time (current-time-millis)})]
             (lru-cache-evict new-cache *cache-size*)))))

(defn default-encoder [value]
  (when value
    (normalize (string/lower-case value))))


(defn default-splitter [^String s]
  (set (remove string/blank? (string/split s #"[^a-zA-Z0-9\.+]"))))

(defn init
  "Initialize a new search index backed by in-memory storage (MemStorage).

  This creates a durable B-tree structure that provides:
  - Better performance than old sorted-set implementation
  - Time-travel and versioning via copy-on-write semantics
  - Structural sharing for efficient updates
  - Optional persistence via store/restore API

  For disk persistence, use store/restore with DiskStorage:
    (def idx (init))
    (def idx2 (search-add idx [[\"doc1\" \"text\" \"Title\"]]))
    (require '[nebsearch.disk-storage :as disk])
    (def disk-storage (disk/open-disk-storage \"index.dat\" 128 true))
    (def ref (store idx2 disk-storage))
    (def idx3 (restore disk-storage ref))

  Examples:
    (init) ; creates empty index with in-memory storage"
  []
  #?(:clj
     (let [storage (mem-storage/create-memory-storage)
           btree (bt/open-btree storage)]
       ^{:cache (atom {}) :storage storage}
       {:data btree
        :index ""
        :ids {}
        :pos-boundaries []})
     :cljs
     (throw (ex-info "init not supported in ClojureScript" {}))))

;; All indexes are now durable B-tree based (no more dual-mode)

(defn- data-rslice [data start-entry end-entry]
  "Get range from B-tree - returns BACKWARDS iterator

   rslice behavior:
   - (rslice data from to) returns backwards iterator where from <= X <= to
   - (rslice data from nil) returns backwards iterator where X <= from"
  ;; B-tree uses efficient range queries
  ;; - When end-entry is nil: return entries where X <= start-entry, largest first
  ;; - When end-entry is not nil: return entries where end-entry <= X <= start-entry, largest first
  ;; NOTE: Entries can be [pos id] or [pos id text], so compare by position only
  (let [start-pos (when start-entry (first start-entry))
        end-pos (when end-entry (first end-entry))]
    (cond
      ;; No range specified - return all entries reversed
      (and (nil? start-pos) (nil? end-pos))
      (reverse (bt/bt-range data nil nil))

      ;; Only start specified: return entries where pos <= start-pos
      (and start-pos (not end-pos))
      (reverse (bt/bt-range data 0 start-pos))

      ;; Both start and end: return entries where end-pos <= pos <= start-pos
      (and start-pos end-pos)
      (reverse (bt/bt-range data end-pos start-pos))

      ;; Only end specified (unusual): return entries where pos >= end-pos
      :else
      (reverse (bt/bt-range data end-pos nil)))))

(defn close
  "Close a lazy index and release storage resources.
   No-op for in-memory indexes."
  [index]
  (when-let [data (:data index)]
    (when #?(:clj (instance? nebsearch.btree.DurableBTree data)
             :cljs false)
      (bt/close-btree data)))
  nil)

(defn store
  "Store the index to storage and return a reference.

  This allows dynamic persistence - you can create a normal in-memory index
  and decide to persist it at any point. The reference can be used with restore
  to get a lazy version of the index. Multiple calls to store with incremental
  changes will use structural sharing (COW semantics).

  Example:
    (def idx (init))
    (def idx2 (search-add idx \"doc1\" \"hello world\"))
    (def ref (store idx2 storage))
    ;; ref is a map with :root-offset, :index, :ids

  Parameters:
  - index: The search index to store (can be in-memory or already lazy)
  - storage: An IStorage implementation (e.g., DiskStorage or MemoryStorage)

  Returns:
  - A reference map {:root-offset, :index, :ids, :pos-boundaries} that can be used with restore"
  [index storage]
  (let [data (:data index)]
    (cond
      ;; Already a durable B-tree - just need to save it
      #?(:clj (instance? nebsearch.btree.DurableBTree data)
         :cljs false)
      (let [root-offset (:root-offset data)]
        ;; Update root offset in storage using generic protocol
        (when (satisfies? storage/IStorageRoot storage)
          (storage/set-root-offset storage root-offset))

        ;; Save storage
        (when (satisfies? storage/IStorageSave storage)
          (storage/save storage))

        {:root-offset root-offset
         :index (:index index)
         :ids (:ids index)
         :pos-boundaries (:pos-boundaries index)})

      ;; In-memory sorted set - need to convert to B-tree and store
      :else
      (let [;; Create a B-tree with the storage
            btree (bt/open-btree storage)
            ;; Convert sorted-set entries to B-tree
            entries (vec data)
            btree-with-data (if (seq entries)
                              (bt/bt-bulk-insert btree entries)
                              btree)
            root-offset (:root-offset btree-with-data)]
        ;; Update root offset in storage using generic protocol
        (when (satisfies? storage/IStorageRoot storage)
          (storage/set-root-offset storage root-offset))

        ;; Save to storage
        (when (satisfies? storage/IStorageSave storage)
          (storage/save storage))

        {:root-offset root-offset
         :index (:index index)
         :ids (:ids index)
         :pos-boundaries (:pos-boundaries index)}))))

(defn restore
  "Restore an index from storage using a reference.

  The returned index is lazy - it will load B-tree nodes from storage on-demand.
  All operations (search, search-add, search-remove) work transparently on lazy
  indexes. You can make changes to a lazy index and store it again to get a new
  reference with structural sharing.

  Example:
    (require '[nebsearch.disk-storage :as disk-storage])
    (def storage (disk-storage/open-disk-storage \"index.dat\" 128 false))
    (def idx-lazy (restore storage ref))
    ;; Works transparently
    (search idx-lazy \"hello\")
    ;; Make changes
    (def idx2 (search-add idx-lazy \"doc2\" \"more text\"))
    ;; Store again - structural sharing with previous version
    (def ref2 (store idx2 storage))

  Parameters:
  - storage: An IStorage implementation (must be the same storage used for store)
  - reference: A reference map returned from store {:root-offset, :index, :ids, :pos-boundaries}

  Returns:
  - A lazy search index that loads nodes on-demand"
  [storage reference]
  #?(:clj
     (let [{:keys [root-offset index ids pos-boundaries]} reference
           ;; Create a B-tree that will lazily load from storage
           btree (bt/->DurableBTree storage root-offset)]
       ^{:cache (atom {})
         :lazy? true}
       {:data btree
        :index index
        :ids ids
        :pos-boundaries pos-boundaries})
     :cljs
     (throw (ex-info "restore not supported in ClojureScript" {}))))

(defn find-len [index pos]
  (if-let [end (string/index-of index join-char pos)]
    (- end pos)
    (throw (ex-info "Invalid index position: join-char not found"
                    {:pos pos :index-length (count index)}))))

;; Magic Trick #1: Binary Search Position Index
;; O(log n) lookups where n = number of documents (not positions!)
(defn- build-pos-boundaries
  "Build sorted vector of [position, doc-id, text-length] from :ids map.
   Enables binary search to find which document contains a given position."
  [ids index]
  (vec (sort-by first
                (map (fn [[id pos]]
                       (let [len (find-len index pos)]
                         [pos id len]))
                     ids))))

(defn- find-doc-at-pos
  "Find document ID at given position using binary search.
   Returns doc-id if position falls within a document, nil otherwise.
   Complexity: O(log n) where n = number of documents."
  [pos-boundaries pos]
  (when (seq pos-boundaries)
    (loop [lo 0
           hi (dec (count pos-boundaries))]
      (when (<= lo hi)
        (let [mid (quot (+ lo hi) 2)
              [start-pos doc-id text-len] (nth pos-boundaries mid)
              end-pos (+ start-pos text-len)]
          (cond
            (and (>= pos start-pos) (< pos end-pos))
            doc-id
            (< pos start-pos)
            (recur lo (dec mid))
            :else
            (recur (inc mid) hi)))))))

;; Forward declarations for mutual recursion
(declare search-gc)

(defn- calculate-fragmentation [{:keys [ids data] :as flex}]
  (if (zero? (count ids))
    0.0
    ;; Calculate from B-tree entries
    ;; Fragmentation = (total_space - actual_text) / total_space
    ;; This includes both delimiter overhead and gaps from removals
    (let [entries (bt/bt-seq data)]
      (if (empty? entries)
        0.0
        (let [;; Find the position where next entry would be added
              ;; This is max(pos + len(text) + 1) across all entries
              max-pos (reduce (fn [max-p [pos _ text]]
                               (max max-p (+ pos (count text) 1)))
                             0 entries)
              ;; Sum of actual text lengths (without delimiters or gaps)
              text-length-only (reduce (fn [sum [_ _ text]]
                                        (+ sum (count text)))
                                      0 entries)]
          (if (zero? max-pos)
            0.0
            (/ (double (- max-pos text-length-only)) max-pos)))))))

(defn search-remove [{:keys [index data ids pos-boundaries] :as flex} id-list]
  {:pre [(map? flex)
         (or (nil? id-list) (sequential? id-list))]}
  (let [existing (filter identity (mapv (fn [id] [(get ids id) id]) id-list))]
    ;; Delete from B-tree (text is in B-tree)
    (loop [[[pos :as pair] & ps] existing
           data data]
      (if pair
        (recur ps (bt/bt-delete data pair))
        ;; Create new version with updated cache
        (let [old-cache @(:cache (meta flex))
              removed-ids (set id-list)
              ;; Filter cached results to remove deleted document IDs
              new-cache (atom (into {} (map (fn [[k v]]
                                              [k (assoc v :value (sets/difference (:value v) removed-ids))])
                                            old-cache)))
              updated-ids (apply dissoc ids id-list)
              updated-pos-boundaries (filterv (fn [[_ id _]] (not (removed-ids id))) pos-boundaries)
              result (-> (assoc flex
                                :ids updated-ids
                                :data data
                                :index index  ;; Index unchanged (text in B-tree)
                                :pos-boundaries updated-pos-boundaries)
                         (vary-meta assoc :cache new-cache))]
          ;; Auto-GC disabled for durable mode - could break COW file semantics
          result)))))

(defn search-add [{:keys [ids pos-boundaries] :as flex} pairs]
  {:pre [(map? flex)
         (or (map? pairs) (sequential? pairs))]}
  (let [pairs (if (map? pairs) (seq pairs) pairs)
        updated-pairs (filter (comp ids first) pairs)
        {:keys [ids ^String index data pos-boundaries] :as flex}
        (if (seq updated-pairs) (search-remove flex (mapv first updated-pairs)) flex)]
    ;; Text stored in both index string (for search) and B-tree (for durability)
    ;; Optimization: Collect all entries first, then bulk insert into B-tree
    (loop [[[id w] & ws] pairs
           pos #?(:clj (.length index) :cljs (.-length index))
           btree-entries []
           r []
           ids ids
           new-boundaries []]
      (if w
        (let [^String w (default-encoder w)
              len #?(:clj (.length w) :cljs (.-length w))
              ;; Collect entry for bulk insert
              entry [pos id w]]
          (recur ws (+ pos len 1)
                 (conj btree-entries entry)
                 (conj r w)
                 (assoc ids id pos)
                 (conj new-boundaries [pos id len])))
        ;; Bulk insert all entries into B-tree at once
        (let [new-index (str index (string/join join-char r) join-char)
              updated-pos-boundaries (into pos-boundaries new-boundaries)
              new-data (bt/bt-bulk-insert data btree-entries)]
          (-> (assoc flex
                     :ids ids
                     :index new-index
                     :data new-data
                     :pos-boundaries updated-pos-boundaries)
              (vary-meta assoc :cache (atom {}))))))))

(defn rebuild-index [pairs]
  (loop [[[_ w] & ws] pairs
         r (transient [])]
    (if w
      (recur ws (conj! r (default-encoder w)))
      (str (string/join join-char (persistent! r)) join-char))))

(defn find-positions [text min-pos max-pos search]
  (let [search-len (count search)]
    (loop [from min-pos
           r []]
      (if-let [i (string/index-of text search from)]
        (if (or (not max-pos) (<= i max-pos))
          (recur (+ (int i) search-len) (conj r i))
          r)
        r))))

(defn search
  ([flex search-query]
   (search flex search-query nil))
  ([{:keys [index data pos-boundaries] :as flex} search-query {:keys [limit] :or {limit nil}}]
   {:pre [(map? flex)]}
   (if-not (and search-query data)
     #{}
     (let [cache (:cache (meta flex))
           words (default-splitter (default-encoder search-query))]
       (if (empty? words)
         #{}
         ;; Use LRU cache
         (if-let [cached (lru-cache-get cache words)]
           (if limit (set (take limit cached)) cached)
           (let [result
                   (apply
                    sets/intersection
                    (loop [[w & ws] (reverse (sort-by count words))
                           r []
                           min-pos 0
                           max-pos (count index)]
                      (if w
                        (let [positions (find-positions index min-pos max-pos w)
                              ;; ✨ MAGIC: Binary search instead of B-tree lookups! ✨
                              doc-ids (keep #(find-doc-at-pos pos-boundaries %) positions)
                              doc-id-set (set doc-ids)] ;; Convert to set for O(1) lookup
                          (if (seq doc-ids)
                            ;; Narrow search range based on matches
                            (let [matching-bounds (filter (fn [[_ id _]] (contains? doc-id-set id))
                                                         pos-boundaries)
                                  new-min (long (apply min (map first matching-bounds)))
                                  new-max (long (reduce (fn [mx [pos _ len]]
                                                         (max mx (+ pos len)))
                                                       0
                                                       matching-bounds))]
                              (recur ws (conj r doc-id-set)
                                     new-min new-max))
                            ;; No matches
                            (recur ws (conj r #{}) min-pos max-pos)))
                        r)))]
             (lru-cache-put cache words result)
             (if limit (set (take limit result)) result))))))))

(defn search-gc [{:keys [data] :as flex}]
  "Rebuild index to remove fragmentation.

   Extracts all entries from the B-tree, creates a new index, and adds them back.
   This compacts the data structure and removes any fragmentation.

   For lazy indexes created via restore, use store/restore to compact:
     (def ref2 (store lazy-index storage))  ;; Creates compacted version"
  ;; Extract all entries from B-tree and rebuild
  (let [entries (bt/bt-seq data)
        pairs (mapv (fn [[_ id text]] [id text]) entries)
        new-flex (search-add (init) pairs)]
    (with-meta new-flex (meta flex))))

(defn index-stats
  "Get statistics about the search index.

  Returns a map with:
    :mode - always :durable (all indexes use B-tree now)
    :document-count - number of indexed documents
    :index-size - size of the index string in bytes
    :fragmentation - fragmentation ratio (0.0 to 1.0)
    :cache-size - number of cached search results
    :btree-stats - B-tree statistics"
  [flex]
  (let [;; Sum text lengths from B-tree entries
        index-size (reduce (fn [sum [_ _ text]]
                            (+ sum (count text) 1)) ;; +1 for delimiter
                          0
                          (bt/bt-seq (:data flex)))
        base-stats {:mode :durable
                    :document-count (count (:ids flex))
                    :index-size index-size
                    :fragmentation (calculate-fragmentation flex)
                    :cache-size (count @(:cache (meta flex)))}]
    (assoc base-stats :btree-stats #?(:clj (bt/btree-stats (:data flex))
                                      :cljs nil))))

