(ns nebsearch.core
  #?(:clj (:gen-class))
  (:require [clojure.string :as string]
            [clojure.set :as sets]
            [me.tonsky.persistent-sorted-set :as pss]
            [nebsearch.btree :as bt]
            [nebsearch.metadata :as meta]))

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
  "Initialize a new search index.

  Options:
    :durable? - Enable disk-backed persistence (default: false)
    :index-path - Path to index file (required if :durable? is true)

  Examples:
    (init) ; in-memory index
    (init {:durable? true :index-path \"index.dat\"}) ; disk-backed index"
  ([]
   (init {}))
  ([opts]
   (let [{:keys [durable? index-path]} opts]
     (if durable?
       #?(:clj
          (do
            (when-not index-path
              (throw (ex-info "index-path required for durable mode" {})))
            ;; Initialize metadata
            (meta/initialize-metadata index-path)
            ;; Create metadata with version tracking
            ^{:cache (atom {})
              :durable? true
              :index-path index-path
              :version 0}
            {:data (bt/open-btree index-path true)
             :index ""
             :ids {}
             :pos-boundaries []})
          :cljs
          (throw (ex-info "Durable mode not supported in ClojureScript" {})))
       ;; In-memory mode (default)
       ^{:cache (atom {})}
       {:data (pss/sorted-set)
        :index ""
        :ids {}
        :pos-boundaries []}))))

;; Helper functions for dual-mode operation
(defn- durable-mode? [flex]
  "Check if index is in durable mode"
  (boolean (:durable? (meta flex))))

(defn- data-conj [data entry durable?]
  "Add entry to data structure (works with both sorted-set and btree)"
  (if durable?
    (bt/bt-insert data entry)
    (conj data entry)))

(defn- data-disj [data entry durable?]
  "Remove entry from data structure (works with both sorted-set and btree)"
  (if durable?
    (bt/bt-delete data entry)
    (disj data entry)))

(defn- data-rslice [data start-entry end-entry durable?]
  "Get range from data structure - returns BACKWARDS iterator like pss/rslice

   rslice behavior:
   - (rslice data from to) returns backwards iterator where from <= X <= to
   - (rslice data from nil) returns backwards iterator where X <= from

   This matches pss/rslice exactly for compatibility."
  (if durable?
    ;; For B-tree, use efficient range queries instead of full scan
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
        (reverse (bt/bt-range data end-pos nil))))
    (pss/rslice data start-entry end-entry)))

(defn serialize [flex]
  (if (durable-mode? flex)
    ;; For durable mode, persist everything to disk
    (do
      #?(:clj
         (let [index-path (:index-path (clojure.core/meta flex))
               current-version (or (:version (clojure.core/meta flex)) 0)
               new-version (inc current-version)
               root-offset (get-in (bt/btree-stats (:data flex)) [:root-offset])]
           ;; Write B-tree to disk
           (bt/btree-flush (:data flex))
           ;; Write metadata (index string and ids map)
           (meta/write-metadata index-path
                                {:index (:index flex)
                                 :ids (:ids flex)
                                 :version new-version
                                 :timestamp (System/currentTimeMillis)})
           ;; Append to version log
           (meta/append-version index-path
                                {:version new-version
                                 :timestamp (System/currentTimeMillis)
                                 :root-offset root-offset
                                 :parent-version current-version})
           ;; Return flex with updated version in metadata
           (vary-meta flex assoc :version new-version)))
      flex)
    ;; For in-memory mode, convert sorted-set to regular set
    (update flex :data #(into #{} %))))

(defn deserialize [flex]
  (-> flex
      (update :data #(apply pss/sorted-set %))
      (vary-meta #(or % {:cache (atom {})}))))

(defn close
  "Close a durable index and release resources.
   No-op for in-memory indexes."
  [flex]
  (when (durable-mode? flex)
    #?(:clj (bt/close-btree (:data flex))))
  nil)

(defn snapshot
  "Create a named snapshot of the current index state.

  Options:
    :name - Name for this snapshot (required)

  Returns the index unchanged, but records this version as a named snapshot.

  Example:
    (snapshot idx {:name \"checkpoint-1\"})"
  [flex {:keys [name]}]
  (if (durable-mode? flex)
    #?(:clj
       (do
         (when-not name
           (throw (ex-info "Snapshot name required" {})))
         ;; First flush to ensure we have latest version saved
         (let [flushed (flush flex)
               index-path (:index-path (clojure.core/meta flushed))
               current-version (:version (clojure.core/meta flushed))
               versions (meta/read-version-log index-path)]
           ;; Update the current version entry with snapshot name
           (when-let [version-entry (first (filter #(= (:version %) current-version) versions))]
             (let [updated-entry (assoc version-entry :snapshot-name name)
                   other-versions (remove #(= (:version %) current-version) versions)
                   all-versions (sort-by :version (conj other-versions updated-entry))
                   log-path (meta/version-log-path index-path)]
               ;; Rewrite version log
               (spit log-path
                     (clojure.string/join "\n" (map pr-str all-versions)))))
           flushed))
       :cljs
       (throw (ex-info "Snapshots not supported in ClojureScript" {})))
    ;; In-memory mode - just return the index
    flex))

(defn list-snapshots
  "List all named snapshots for a durable index.

  Returns a vector of maps with :version, :snapshot-name, and :timestamp."
  [flex]
  (if (durable-mode? flex)
    #?(:clj
       (let [index-path (:index-path (clojure.core/meta flex))]
         (meta/list-snapshots index-path))
       :cljs [])
    []))

(defn restore-snapshot
  "Restore a durable index to a named snapshot.

  Options:
    :name - Snapshot name to restore (required)

  Returns a new index with data from the snapshot.

  Example:
    (restore-snapshot idx {:name \"checkpoint-1\"})"
  [flex {:keys [name]}]
  (if (durable-mode? flex)
    #?(:clj
       (do
         (when-not name
           (throw (ex-info "Snapshot name required" {})))
         ;; Close current index and reopen at snapshot
         ;; Note: open-index is defined later, so we call it via var
         (let [index-path (:index-path (clojure.core/meta flex))
               open-index-fn (resolve 'nebsearch.core/open-index)]
           (close flex)
           (open-index-fn {:index-path index-path
                           :snapshot-name name})))
       :cljs
       (throw (ex-info "Snapshots not supported in ClojureScript" {})))
    flex))

(defn gc-versions
  "Garbage collect old versions, keeping only specified snapshots.

  Options:
    :keep-snapshots - Vector of snapshot names to keep (default: all named snapshots)
    :keep-latest - Number of latest versions to keep (default: 1)

  This will rewrite the version log to only include kept versions.
  Note: The B-tree file will still contain old nodes until file-level GC is implemented.

  Example:
    (gc-versions idx {:keep-snapshots [\"checkpoint-1\" \"checkpoint-2\"]
                      :keep-latest 5})"
  [flex {:keys [keep-snapshots keep-latest] :or {keep-latest 1}}]
  (if (durable-mode? flex)
    #?(:clj
       (let [index-path (:index-path (clojure.core/meta flex))
             all-versions (meta/read-version-log index-path)
             ;; Determine which versions to keep
             snapshot-versions (if keep-snapshots
                                 (set (map :version
                                           (filter #(some #{(:snapshot-name %)} keep-snapshots)
                                                   all-versions)))
                                 (set (map :version (filter :snapshot-name all-versions))))
             latest-versions (set (map :version (take-last keep-latest all-versions)))
             keep-versions (clojure.set/union snapshot-versions latest-versions)]
         ;; GC the versions
         (meta/gc-old-versions index-path keep-versions)
         flex)
       :cljs
       (throw (ex-info "Version GC not supported in ClojureScript" {})))
    flex))

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
#?(:clj (declare open-index))

(defn- calculate-fragmentation [{:keys [index ids data] :as flex}]
  (if (zero? (count ids))
    0.0
    (if (durable-mode? flex)
      ;; Durable mode: calculate from B-tree entries
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
              (/ (double (- max-pos text-length-only)) max-pos)))))
      ;; In-memory mode: count spaces in index string
      (if (empty? index)
        0.0
        (let [space-count (count (filter #(= % \space) index))
              total-chars (count index)]
          (/ (double space-count) total-chars))))))

(defn search-remove [{:keys [index data ids] :as flex} id-list]
  {:pre [(map? flex)
         (or (nil? id-list) (sequential? id-list))]}
  (let [existing (filter identity (mapv (fn [id] [(get ids id) id]) id-list))
        durable? (durable-mode? flex)]
    (if durable?
      ;; Durable mode - delete from B-tree (text is in B-tree)
      (loop [[[pos :as pair] & ps] existing
             data data]
        (if pair
          (recur ps (data-disj data pair true))
          ;; Create new version with updated cache
          (let [old-cache @(:cache (meta flex))
                removed-ids (set id-list)
                ;; Filter cached results to remove deleted document IDs
                new-cache (atom (into {} (map (fn [[k v]]
                                                [k (assoc v :value (sets/difference (:value v) removed-ids))])
                                              old-cache)))
                updated-ids (apply dissoc ids id-list)
                pos-boundaries (build-pos-boundaries updated-ids index)
                result (-> (assoc flex
                                  :ids updated-ids
                                  :data data
                                  :index index  ;; Index unchanged (text in B-tree)
                                  :pos-boundaries pos-boundaries)
                           (vary-meta assoc :cache new-cache))]
            ;; Auto-GC disabled for durable mode - could break COW file semantics
            result)))
      ;; In-memory mode - use transients for performance
      (loop [[[pos :as pair] & ps] existing
             data (transient data)
             index index]
        (if pair
          (let [len (find-len index pos)]
            (recur ps (disj! data pair)
                   (str (subs index 0 pos)
                        (apply str (repeat len " "))
                        (subs index (+ pos len)))))
          ;; Create new version with updated cache
          (let [old-cache @(:cache (meta flex))
                removed-ids (set id-list)
                ;; Filter cached results to remove deleted document IDs
                new-cache (atom (into {} (map (fn [[k v]]
                                                [k (assoc v :value (sets/difference (:value v) removed-ids))])
                                              old-cache)))
                updated-ids (apply dissoc ids id-list)
                pos-boundaries (build-pos-boundaries updated-ids index)
                result (-> (assoc flex
                                  :ids updated-ids
                                  :data (persistent! data)
                                  :index index
                                  :pos-boundaries pos-boundaries)
                           (vary-meta assoc :cache new-cache))]
            ;; Auto-GC if fragmentation exceeds threshold (in-memory mode only)
            (if (> (calculate-fragmentation result) *auto-gc-threshold*)
              (search-gc result)
              result)))))))

(defn search-add [{:keys [ids] :as flex} pairs]
  {:pre [(map? flex)
         (or (map? pairs) (sequential? pairs))]}
  (let [pairs (if (map? pairs) (seq pairs) pairs)
        updated-pairs (filter (comp ids first) pairs)
        {:keys [ids ^String index data] :as flex}
        (if (seq updated-pairs) (search-remove flex (mapv first updated-pairs)) flex)
        durable? (durable-mode? flex)]
    ;; No need to reset cache - each version gets its own cache via vary-meta
    (if durable?
      ;; Durable mode - text stored in both index string (for search) and B-tree (for durability)
      (loop [[[id w] & ws] pairs
             pos #?(:clj (.length index) :cljs (.-length index))
             data data
             r []
             ids ids]
        (if w
          (let [^String w (default-encoder w)
                len #?(:clj (.length w) :cljs (.-length w))
                ;; Store [pos, id, text] in B-tree
                entry [pos id w]]
            (recur ws (+ pos len 1) (data-conj data entry true)
                   (conj r w)
                   (assoc ids id pos)))
          ;; Build index string and position boundaries for searching
          (let [new-index (str index (string/join join-char r) join-char)
                pos-boundaries (build-pos-boundaries ids new-index)]
            (-> (assoc flex
                       :ids ids
                       :index new-index
                       :data data
                       :pos-boundaries pos-boundaries)
                (vary-meta assoc :cache (atom {}))))))
      ;; In-memory mode - use transients for performance
      (loop [[[id w] & ws] pairs
             pos #?(:clj (.length index) :cljs (.-length index))
             data (transient data)
             r (transient [])
             ids (transient ids)]
        (if w
          (let [^String w (default-encoder w)
                len #?(:clj (.length w) :cljs (.-length w))
                pair [pos id]]
            (recur ws (+ pos len 1) (conj! data pair) (conj! r w)
                   (assoc! ids id (first pair))))
          (let [words (persistent! r)
                ;; Optimization: Use StringBuilder for large batches (JVM only)
                new-index #?(:clj (if (>= (count words) *batch-threshold*)
                                    (let [sb (StringBuilder. index)]
                                      (doseq [word words]
                                        (.append sb word)
                                        (.append sb join-char))
                                      (.toString sb))
                                    (str index (string/join join-char words) join-char))
                             :cljs (str index (string/join join-char words) join-char))
                persistent-ids (persistent! ids)
                pos-boundaries (build-pos-boundaries persistent-ids new-index)]
            ;; Create new version with its own cache to preserve COW semantics
            (-> (assoc flex
                       :ids persistent-ids
                       :index new-index
                       :data (persistent! data)
                       :pos-boundaries pos-boundaries)
                (vary-meta assoc :cache (atom {})))))))))

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
           words (default-splitter (default-encoder search-query))
           durable? (durable-mode? flex)]
       (if (empty? words)
         #{}
         ;; Use LRU cache
         (if-let [cached (lru-cache-get cache words)]
           (if limit (set (take limit cached)) cached)
           (let [result
                 ;; Both modes use index string for search
                 (if true ;; Same logic for both modes
                   (apply
                    sets/intersection
                    (loop [[w & ws] (reverse (sort-by count words))
                           r []
                           min-pos 0
                           max-pos (count index)]
                      (if w
                        (let [positions (find-positions index min-pos max-pos w)
                              ;; ✨ MAGIC: Binary search instead of B-tree lookups! ✨
                              doc-ids (keep #(find-doc-at-pos pos-boundaries %) positions)]
                          (if (seq doc-ids)
                            ;; Narrow search range based on matches
                            (let [matching-bounds (filter (fn [[pos id _]]
                                                           (some #(= id %) doc-ids))
                                                         pos-boundaries)
                                  new-min (long (apply min (map first matching-bounds)))
                                  new-max (long (reduce (fn [mx [pos _ len]]
                                                         (max mx (+ pos len)))
                                                       0
                                                       matching-bounds))]
                              (recur ws (conj r (set doc-ids))
                                     new-min new-max))
                            ;; No matches
                            (recur ws (conj r #{}) min-pos max-pos)))
                        r))))]
             (lru-cache-put cache words result)
             (if limit (set (take limit result)) result))))))))

(defn search-gc [{:keys [index data ids] :as flex}]
  "Rebuild index to remove fragmentation. For in-memory mode only.
   For durable mode with COW semantics, GC would break old version references,
   so we just rebuild the index string and B-tree in place."
  (let [durable? (durable-mode? flex)]
    (if durable?
      ;; For durable mode: extract pairs and rebuild using search-add
      #?(:clj
         (let [data-seq (bt/bt-seq data)
               ;; Extract [id, text] pairs from B-tree entries
               ;; Entries are now [pos id text], only keep docs still in ids map
               pairs (keep (fn [[pos id text]]
                            (when (contains? ids id)
                              [id text])) data-seq)
               old-path (:index-path (meta flex))
               temp-path (str old-path ".gc-temp")
               old-meta (meta flex)]
           ;; Close old B-tree
           (bt/close-btree data)
           ;; Build new index in temp location
           (let [rebuilt (search-add (init {:durable? true :index-path temp-path})
                                     (into {} pairs))]
             ;; Flush metadata to disk before closing
             (serialize rebuilt)
             ;; Close temp
             (bt/close-btree (:data rebuilt))
             ;; Delete old files and rename temp to original
             (doseq [suffix ["" ".meta" ".versions"]]
               (let [old-f (java.io.File. (str old-path suffix))
                     temp-f (java.io.File. (str temp-path suffix))]
                 (when (.exists old-f) (.delete old-f))
                 (when (.exists temp-f) (.renameTo temp-f old-f))))
             ;; Reopen at original path
             (let [reopened (open-index {:index-path old-path})]
               (with-meta reopened old-meta))))
         :cljs flex)
      ;; For in-memory mode: full rebuild
      (let [data-seq (seq data)
            pairs (mapv (fn [[pos id]]
                         (let [len (find-len index pos)]
                           [id (subs index pos (+ pos len))])) data-seq)
            new-flex (search-add (init) pairs)]
        (with-meta new-flex (meta flex))))))

(defn index-stats
  "Get statistics about the search index.

  Returns a map with:
    :mode - :in-memory or :durable
    :document-count - number of indexed documents
    :index-size - size of the index string in bytes
    :fragmentation - fragmentation ratio (0.0 to 1.0)
    :cache-size - number of cached search results
    :btree-stats - B-tree statistics (durable mode only)"
  [flex]
  (let [durable? (durable-mode? flex)
        ;; Calculate index-size differently for durable mode
        index-size (if durable?
                     ;; Durable: sum text lengths from B-tree entries
                     (reduce (fn [sum [_ _ text]]
                              (+ sum (count text) 1)) ;; +1 for delimiter
                            0
                            (bt/bt-seq (:data flex)))
                     ;; In-memory: use index string length
                     (count (:index flex)))
        base-stats {:mode (if durable? :durable :in-memory)
                    :document-count (count (:ids flex))
                    :index-size index-size
                    :fragmentation (calculate-fragmentation flex)
                    :cache-size (count @(:cache (meta flex)))}]
    (if durable?
      (assoc base-stats :btree-stats #?(:clj (bt/btree-stats (:data flex))
                                        :cljs nil))
      base-stats)))

(defn flush
  "Flush a durable index to disk.
   No-op for in-memory indexes.

   This ensures all data is written to disk and fsync'd.
   Same as serialize, but used for explicit flush operations."
  [flex]
  (if (durable-mode? flex)
    (serialize flex)
    flex))

(defn open-index
  "Open an existing durable index from disk.

  Options:
    :index-path - Path to the index file (required)
    :version - Optional version number to open (default: latest)
    :snapshot-name - Optional snapshot name to open

  Returns a search index that can be used with search, search-add, etc.

  Note: The index is opened in read-write mode. To create a new index,
  use (init {:durable? true :index-path \"...\"})"
  [{:keys [index-path version snapshot-name]}]
  #?(:clj
     (do
       (when-not index-path
         (throw (ex-info "index-path required" {})))

       ;; Load metadata
       (let [metadata (meta/read-metadata index-path)]
         (when-not metadata
           (throw (ex-info "No metadata found - index may not exist or is corrupted"
                           {:index-path index-path})))

         ;; Get version info
         (let [version-info (if (or version snapshot-name)
                              (meta/get-version index-path
                                                (cond
                                                  version {:version version}
                                                  snapshot-name {:snapshot-name snapshot-name}))
                              (last (meta/read-version-log index-path)))
               loaded-version (:version metadata)]

           ;; Open B-tree
           (let [btree (bt/open-btree index-path false)
                 pos-boundaries (build-pos-boundaries (:ids metadata) (:index metadata))]
             ^{:cache (atom {})
               :durable? true
               :index-path index-path
               :version loaded-version}
             {:data btree
              :index (:index metadata)
              :ids (:ids metadata)
              :pos-boundaries pos-boundaries}))))
     :cljs
     (throw (ex-info "Durable mode not supported in ClojureScript" {}))))
