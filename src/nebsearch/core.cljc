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
(def ^:dynamic *bulk-insert-threshold* 50)  ;; Use bt-bulk-insert for >=50 docs, bt-insert for <50
(def ^:dynamic *storage-cache-size* 256)  ;; Storage node cache size (small=128, medium=256, large=512)
(def ^:dynamic *enable-metrics* false)  ;; Enable performance metrics tracking

;; Preset cache configurations
(def cache-presets
  {:small  {:cache-size 500  :storage-cache-size 128  :bulk-threshold 25}
   :medium {:cache-size 1000 :storage-cache-size 256  :bulk-threshold 50}
   :large  {:cache-size 2000 :storage-cache-size 512  :bulk-threshold 100}})

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
  (set (remove string/blank? (string/split s #"[^a-zA-Z0-9]"))))

(defn apply-cache-preset
  "Apply a cache preset configuration (:small, :medium, or :large).

  Example:
    (binding [*cache-size* (:cache-size (cache-presets :large))
              *storage-cache-size* (:storage-cache-size (cache-presets :large))
              *bulk-insert-threshold* (:bulk-threshold (cache-presets :large))]
      ... your code ...)"
  [preset]
  (get cache-presets preset (:medium cache-presets)))

(defn- create-metrics-atom []
  "Create a metrics tracking atom"
  (atom {:cache-hits 0
         :cache-misses 0
         :total-queries 0
         :total-query-time-ms 0}))

(defn get-metrics
  "Get performance metrics from an index.
  Returns nil if metrics are not enabled or not available."
  [index]
  (when-let [metrics (:metrics (meta index))]
    @metrics))

(defn reset-metrics!
  "Reset performance metrics for an index."
  [index]
  (when-let [metrics (:metrics (meta index))]
    (reset! metrics {:cache-hits 0
                     :cache-misses 0
                     :total-queries 0
                     :total-query-time-ms 0})))

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
           btree (bt/->DurableBTree storage nil)
           ;; Memory storage uses lazy inverted index (atom with map)
           ;; Disk storage would use separate B-tree (see restore)
           inverted (if (storage/precompute-inverted? storage)
                      (bt/->DurableBTree storage nil)  ; Separate B-tree for pre-computed inverted
                      (atom {}))]                      ; Lazy map for on-demand building
       ^{:cache (atom {})
         :storage storage
         :inverted inverted
         :metrics (when *enable-metrics* (create-metrics-atom))}
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
  (let [data (:data index)
        current-storage (:storage (meta index))
        inverted (:inverted (meta index))]
    (cond
      ;; Already a durable B-tree using the SAME storage - just need to save it
      #?(:clj (and (instance? nebsearch.btree.DurableBTree data)
                   (identical? current-storage storage))
         :cljs false)
      (let [root-offset (:root-offset data)
            ;; Save inverted root offset if using pre-computed B-tree
            inverted-root-offset (when (and (storage/precompute-inverted? storage)
                                           #?(:clj (instance? nebsearch.btree.DurableBTree inverted)
                                              :cljs false))
                                  (:root-offset inverted))]
        ;; Update root offset in storage using generic protocol
        (when (satisfies? storage/IStorageRoot storage)
          (storage/set-root-offset storage root-offset))

        ;; Save storage
        (when (satisfies? storage/IStorageSave storage)
          (storage/save storage))

        ;; Don't store index string for disk storage with pre-computed inverted index
        ;; The string is only needed for fallback search, which never triggers with inverted index
        ;; This saves massive memory (100MB-10GB) for large indexes
        (cond-> {:root-offset root-offset
                 :index (if inverted-root-offset "" (:index index))  ; Empty for disk storage!
                 :ids (:ids index)
                 :pos-boundaries (:pos-boundaries index)}
          inverted-root-offset (assoc :inverted-root-offset inverted-root-offset)))

      ;; B-tree with different storage or converting - extract entries and create new B-tree
      :else
      (let [;; Create a B-tree with the target storage
            btree (bt/->DurableBTree storage nil)
            ;; Extract all entries from current B-tree using bt-seq
            entries (vec (bt/bt-seq data))
            btree-with-data (if (seq entries)
                              (bt/bt-bulk-insert btree entries)
                              btree)
            root-offset (:root-offset btree-with-data)
            ;; Migrate inverted index if needed
            inverted-root-offset
            (when (storage/precompute-inverted? storage)
              ;; Target storage wants pre-computed inverted index
              (let [inverted-btree (bt/->DurableBTree storage nil)
                    ;; Extract inverted entries from current inverted index
                    inverted-entries (cond
                                      ;; Source is B-tree (disk -> disk migration)
                                      #?(:clj (instance? nebsearch.btree.DurableBTree inverted)
                                         :cljs false)
                                      (vec (bt/bt-seq inverted))

                                      ;; Source is atom/map (memory -> disk migration)
                                      ;; Always build from scratch to ensure completeness
                                      #?(:clj (instance? clojure.lang.Atom inverted)
                                         :cljs false)
                                      ;; Build from scratch by scanning main data B-tree
                                      (vec (for [[_ doc-id text] entries
                                                 word (default-splitter text)]
                                             [word doc-id]))

                                      :else [])
                    inverted-with-data (if (seq inverted-entries)
                                        (bt/bt-bulk-insert inverted-btree inverted-entries)
                                        inverted-btree)]
                (:root-offset inverted-with-data)))]
        ;; Update root offset in storage using generic protocol
        (when (satisfies? storage/IStorageRoot storage)
          (storage/set-root-offset storage root-offset))

        ;; Save to storage
        (when (satisfies? storage/IStorageSave storage)
          (storage/save storage))

        ;; Don't store index string for disk storage with pre-computed inverted index
        ;; The string is only needed for fallback search, which never triggers with inverted index
        ;; This saves massive memory (100MB-10GB) for large indexes
        (cond-> {:root-offset root-offset
                 :index (if inverted-root-offset "" (:index index))  ; Empty for disk storage!
                 :ids (:ids index)
                 :pos-boundaries (:pos-boundaries index)}
          inverted-root-offset (assoc :inverted-root-offset inverted-root-offset))))))

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
     (let [{:keys [root-offset index ids pos-boundaries inverted-root-offset]} reference
           ;; Create a B-tree that will lazily load from storage
           btree (bt/->DurableBTree storage root-offset)
           ;; Restore inverted index based on storage strategy
           inverted (if (storage/precompute-inverted? storage)
                      ;; Disk storage: restore inverted B-tree if it exists
                      (if inverted-root-offset
                        (bt/->DurableBTree storage inverted-root-offset)
                        (bt/->DurableBTree storage nil))  ; Empty B-tree if no inverted yet
                      ;; Memory storage: start with empty lazy map
                      (atom (or (:inverted reference) {})))]
       ^{:cache (atom {})
         :lazy? true
         :storage storage
         :inverted inverted}
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
  (let [existing (filter identity (mapv (fn [id] [(get ids id) id]) id-list))
        storage (:storage (meta flex))
        precompute? (and storage (storage/precompute-inverted? storage))
        removed-ids-set (set id-list)]
    ;; Delete from B-tree (text is in B-tree)
    (loop [[[pos :as pair] & ps] existing
           data data]
      (if pair
        (recur ps (bt/bt-delete data pair))
        ;; Create new version with updated cache and inverted index
        (let [old-cache @(:cache (meta flex))
              ;; Filter cached results to remove deleted document IDs
              new-cache (atom (into {} (map (fn [[k v]]
                                              [k (assoc v :value (sets/difference (:value v) removed-ids-set))])
                                            old-cache)))
              updated-ids (apply dissoc ids id-list)
              updated-pos-boundaries (filterv (fn [[_ id _]] (not (removed-ids-set id))) pos-boundaries)
              ;; Update inverted index if pre-computing
              inverted (:inverted (meta flex))
              new-inverted (if precompute?
                            #?(:clj (if (instance? nebsearch.btree.DurableBTree inverted)
                                     ;; For B-tree inverted: remove all [word doc-id] entries for removed docs
                                     ;; We need to scan and delete entries matching removed doc IDs
                                     (reduce (fn [inv-tree [word doc-id]]
                                              (if (removed-ids-set doc-id)
                                                (bt/bt-delete inv-tree [word doc-id])
                                                inv-tree))
                                            inverted
                                            (bt/bt-seq inverted))
                                     inverted)
                               :cljs inverted)
                            ;; For lazy map: remove doc IDs from sets (create new atom for COW)
                            #?(:clj (if (instance? clojure.lang.Atom inverted)
                                     ;; Create NEW atom for COW semantics (don't mutate shared atom!)
                                     (atom (into {} (map (fn [[word doc-ids]]
                                                          [word (sets/difference doc-ids removed-ids-set)])
                                                        @inverted)))
                                     inverted)
                               :cljs inverted))
              result (-> (assoc flex
                                :ids updated-ids
                                :data data
                                :index index  ;; Index unchanged (text in B-tree)
                                :pos-boundaries updated-pos-boundaries)
                         (vary-meta merge {:cache new-cache :inverted new-inverted}))]
          ;; Auto-GC disabled for durable mode - could break COW file semantics
          result)))))

(defn- search-add-bulk
  "Bulk insert approach: rebuilds entire B-tree from scratch.
   Optimal for large batches (>= threshold)"
  [{:keys [ids ^String index data pos-boundaries] :as flex} pairs storage precompute?]
  (loop [[[id w] & ws] pairs
           pos #?(:clj (.length index) :cljs (.-length index))
           btree-entries []
           inverted-entries []  ;; Collect [word doc-id] for inverted B-tree
           r []
           ids ids
           new-boundaries []]
      (if w
        (let [^String w (default-encoder w)
              len #?(:clj (.length w) :cljs (.-length w))
              ;; Collect entry for bulk insert
              entry [pos id w]
              ;; Extract words for inverted index
              words (default-splitter w)
              inv-entries (mapv (fn [word] [word id]) words)]
          (recur ws (+ pos len 1)
                 (conj btree-entries entry)
                 (into inverted-entries inv-entries)
                 (conj r w)
                 (assoc ids id pos)
                 (conj new-boundaries [pos id len])))
        ;; Bulk insert all entries into B-tree at once
        (let [new-index (str index (string/join join-char r) join-char)
              updated-pos-boundaries (into pos-boundaries new-boundaries)
              ;; IMPORTANT: bt-bulk-insert builds NEW tree from scratch!
              ;; Must merge existing + new entries
              existing-entries (bt/bt-seq data)
              all-data-entries (into existing-entries btree-entries)
              new-data (if (seq all-data-entries)
                        (bt/bt-bulk-insert (bt/->DurableBTree storage nil) all-data-entries)
                        data)
              ;; Update inverted index
              inverted (:inverted (meta flex))
              new-inverted (cond
                            ;; Pre-computed B-tree (disk storage) - bulk insert all entries
                            (and precompute? (seq inverted-entries))
                            #?(:clj (if (instance? nebsearch.btree.DurableBTree inverted)
                                     ;; IMPORTANT: bt-bulk-insert builds NEW tree from scratch!
                                     ;; Must merge existing + new inverted entries
                                     (let [existing-inv-entries (bt/bt-seq inverted)
                                           all-inv-entries (into existing-inv-entries inverted-entries)]
                                       (if (seq all-inv-entries)
                                         (bt/bt-bulk-insert (bt/->DurableBTree storage nil) all-inv-entries)
                                         inverted))
                                     inverted)
                               :cljs inverted)

                            ;; Lazy atom/map (memory storage) - update only cached words
                            #?(:clj (instance? clojure.lang.Atom inverted)
                               :cljs false)
                            #?(:clj
                               ;; Create NEW atom for COW semantics (don't mutate shared atom!)
                               (atom (reduce (fn [m [word doc-id]]
                                              ;; Only update if word is already cached
                                              (if (contains? m word)
                                                (update m word conj doc-id)
                                                m))
                                            @inverted
                                            inverted-entries))
                               :cljs inverted)

                            ;; No inverted index
                            :else inverted)]
          (-> (assoc flex
                     :ids ids
                     :index new-index
                     :data new-data
                     :pos-boundaries updated-pos-boundaries)
              ;; Reset LRU cache to force fresh searches, but keep inverted as-is
              (vary-meta merge {:cache (atom {}) :inverted new-inverted}))))))

(defn- search-add-incremental
  "Incremental insert approach: uses bt-insert for each entry.
   Optimal for small batches (< threshold). O(k log n) complexity."
  [{:keys [ids ^String index data pos-boundaries] :as flex} pairs storage precompute?]
  (reduce
   (fn [current-flex [id w]]
     ;; Skip nil or empty values
     (if-not w
       current-flex
       (let [^String encoded-w (default-encoder w)
           len #?(:clj (.length encoded-w) :cljs (.-length encoded-w))
           ;; Calculate next position: use index length if available, otherwise compute from pos-boundaries
           pos (let [idx-str (:index current-flex)]
                 (if (and idx-str (pos? #?(:clj (.length ^String idx-str) :cljs (.-length idx-str))))
                   ;; Index string available, use its length
                   #?(:clj (.length ^String idx-str) :cljs (.-length idx-str))
                   ;; Index string empty (restored from disk), calculate from pos-boundaries
                   (if-let [last-boundary (last (:pos-boundaries current-flex))]
                     (let [[last-pos _ last-len] last-boundary]
                       (+ last-pos last-len 1))  ; position after last entry + join char
                     0)))  ; Empty index
           entry [pos id encoded-w]

           ;; Incremental B-tree insert
           new-data (bt/bt-insert (:data current-flex) entry)

           ;; Update inverted index incrementally
           words (default-splitter encoded-w)
           inverted (:inverted (meta current-flex))
           new-inverted (cond
                          ;; Pre-computed B-tree (disk storage) - insert each [word doc-id]
                          (and precompute? (seq words))
                          #?(:clj (if (instance? nebsearch.btree.DurableBTree inverted)
                                   (reduce (fn [inv-tree word]
                                            (bt/bt-insert inv-tree [word id]))
                                          inverted
                                          words)
                                   inverted)
                             :cljs inverted)

                          ;; Lazy atom/map (memory storage) - update cached words
                          #?(:clj (instance? clojure.lang.Atom inverted)
                             :cljs false)
                          #?(:clj
                             ;; Create NEW atom for COW semantics
                             (atom (reduce (fn [m word]
                                            ;; Only update if word is already cached
                                            (if (contains? m word)
                                              (update m word conj id)
                                              m))
                                          @inverted
                                          words))
                             :cljs inverted)

                          ;; No inverted index
                          :else inverted)]

       ;; Return updated flex
       (-> current-flex
           (assoc :data new-data
                  :index (str (:index current-flex) encoded-w join-char)
                  :ids (assoc (:ids current-flex) id pos)
                  :pos-boundaries (conj (:pos-boundaries current-flex) [pos id len]))
           (vary-meta merge {:cache (atom {}) :inverted new-inverted})))))
   flex
   pairs))

(defn search-add [{:keys [ids pos-boundaries] :as flex} pairs]
  {:pre [(map? flex)
         (or (map? pairs) (sequential? pairs))]}
  (let [pairs (if (map? pairs) (seq pairs) pairs)
        updated-pairs (filter (comp ids first) pairs)
        {:keys [ids ^String index data pos-boundaries] :as flex}
        (if (seq updated-pairs) (search-remove flex (mapv first updated-pairs)) flex)
        storage (:storage (meta flex))
        precompute? (and storage (storage/precompute-inverted? storage))
        num-new-docs (count pairs)
        use-bulk? (>= num-new-docs *bulk-insert-threshold*)]

    ;; Choose strategy based on batch size
    (if use-bulk?
      ;; Large batch: Use bulk rebuild (O(n + k))
      (search-add-bulk flex pairs storage precompute?)
      ;; Small batch: Use incremental inserts (O(k log n))
      (search-add-incremental flex pairs storage precompute?))))

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

(defn- find-docs-with-word
  "Find all doc IDs containing the given word using inverted index.
   Supports substring matching: 'tatto' matches 'tattoo'.
   For pre-computed (disk): Query B-tree directly
   For lazy (memory): Build on first access via B-tree iteration"
  [inverted word data]
  (cond
    ;; Pre-computed B-tree inverted index (disk storage)
    #?(:clj (instance? nebsearch.btree.DurableBTree inverted)
       :cljs false)
    #?(:clj
       ;; Scan B-tree for all entries where word is substring of token
       ;; Entries are [word doc-id], filter for substring match
       (set (keep (fn [[w doc-id]]
                   (when (string/includes? w word) doc-id))
                 (bt/bt-seq inverted)))
       :cljs #{})

    ;; Lazy atom/map inverted index (memory storage)
    #?(:clj (instance? clojure.lang.Atom inverted)
       :cljs false)
    #?(:clj
       ;; Check cache for exact match first
       (if-let [cached (get @inverted word)]
         cached
         ;; Not cached yet - build it by scanning B-tree
         (let [doc-ids (set (keep (fn [[_ doc-id text]]
                                    ;; Check if any token in the text contains word as substring
                                    (when (some #(string/includes? % word) (default-splitter text))
                                      doc-id))
                                  (bt/bt-seq data)))]
           ;; Cache the exact word for future lookups
           (swap! inverted assoc word doc-ids)
           doc-ids))
       :cljs #{})

    ;; No inverted index
    :else nil))

(defn search
  ([flex search-query]
   (search flex search-query nil))
  ([{:keys [index data pos-boundaries] :as flex} search-query {:keys [limit] :or {limit nil}}]
   {:pre [(map? flex)]}
   (if-not (and search-query data)
     #{}
     (let [start-time (when *enable-metrics* (current-time-millis))
           cache (:cache (meta flex))
           metrics (:metrics (meta flex))
           words (default-splitter (default-encoder search-query))]
       (if (empty? words)
         #{}
         ;; Use LRU cache
         (if-let [cached (lru-cache-get cache words)]
           (do
             ;; Track cache hit
             (when metrics
               (swap! metrics (fn [m]
                               (-> m
                                   (update :cache-hits inc)
                                   (update :total-queries inc)
                                   (update :total-query-time-ms + (- (current-time-millis) start-time))))))
             (if limit (set (take limit cached)) cached))
           (let [inverted (:inverted (meta flex))
                 ;; Try inverted index first
                 result (if inverted
                         ;; Use inverted index (pre-computed or lazy)
                         (if (= 1 (count words))
                           ;; Single word: direct lookup
                           (find-docs-with-word inverted (first words) data)
                           ;; Multiple words: intersection
                           (apply sets/intersection
                                 (map #(find-docs-with-word inverted % data) words)))
                         ;; Fallback to string scanning if no inverted index
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
                              r))))]
             ;; Track cache miss
             (when metrics
               (swap! metrics (fn [m]
                               (-> m
                                   (update :cache-misses inc)
                                   (update :total-queries inc)
                                   (update :total-query-time-ms + (- (current-time-millis) start-time))))))
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

