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
             :ids {}})
          :cljs
          (throw (ex-info "Durable mode not supported in ClojureScript" {})))
       ;; In-memory mode (default)
       ^{:cache (atom {})}
       {:data (pss/sorted-set)
        :index ""
        :ids {}}))))

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
  "Get range from data structure"
  (if durable?
    ;; For B-tree, match rslice behavior exactly:
    ;; Return entries where start-entry <= entry < end-entry
    ;; Use bt-seq to get all entries, then filter
    ;; (This is slower but ensures correct behavior)
    (let [all-entries (bt/bt-seq data)]
      (cond->> all-entries
        start-entry (filter #(>= (compare % start-entry) 0))
        end-entry (filter #(< (compare % end-entry) 0))))
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

;; Forward declarations for mutual recursion
(declare search-gc)
#?(:clj (declare open-index))

(defn- calculate-fragmentation [{:keys [index ids]}]
  (if (or (empty? index) (zero? (count ids)))
    0.0
    (let [space-count (count (filter #(= % \space) index))
          total-chars (count index)]
      (/ (double space-count) total-chars))))

(defn search-remove [{:keys [index data ids] :as flex} id-list]
  {:pre [(map? flex)
         (or (nil? id-list) (sequential? id-list))]}
  (let [existing (filter identity (mapv (fn [id] [(get ids id) id]) id-list))
        durable? (durable-mode? flex)]
    ;; Update cache entries to remove deleted IDs
    (swap! (:cache (meta flex)) update-vals
           (fn [entry]
             (update entry :value #(apply disj % id-list))))
    (if durable?
      ;; Durable mode - use B-tree operations
      (loop [[[pos :as pair] & ps] existing
             data data
             index index]
        (if pair
          (let [len (find-len index pos)]
            (recur ps
                   (data-disj data pair true)
                   (str (subs index 0 pos)
                        (apply str (repeat len " "))
                        (subs index (+ pos len)))))
          (let [result (assoc flex
                              :ids (apply dissoc ids id-list)
                              :data data
                              :index index)]
            ;; Auto-GC if fragmentation exceeds threshold
            (if (> (calculate-fragmentation result) *auto-gc-threshold*)
              (search-gc result)
              result))))
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
          (let [result (assoc flex
                              :ids (apply dissoc ids id-list)
                              :data (persistent! data)
                              :index index)]
            ;; Auto-GC if fragmentation exceeds threshold
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
    (reset! (:cache (meta flex)) {})
    (if durable?
      ;; Durable mode - no transients
      (loop [[[id w] & ws] pairs
             pos #?(:clj (.length index) :cljs (.-length index))
             data data
             r []
             ids ids]
        (if w
          (let [^String w (default-encoder w)
                len #?(:clj (.length w) :cljs (.-length w))
                pair [pos id]]
            (recur ws (+ pos len 1) (data-conj data pair true) (conj r w)
                   (assoc ids id (first pair))))
          (let [words r
                new-index #?(:clj (if (>= (count words) *batch-threshold*)
                                    (let [sb (StringBuilder. index)]
                                      (doseq [word words]
                                        (.append sb word)
                                        (.append sb join-char))
                                      (.toString sb))
                                    (str index (string/join join-char words) join-char))
                             :cljs (str index (string/join join-char words) join-char))]
            (assoc flex
                   :ids ids
                   :index new-index
                   :data data))))
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
                             :cljs (str index (string/join join-char words) join-char))]
            (assoc flex
                   :ids (persistent! ids)
                   :index new-index
                   :data (persistent! data))))))))

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
  ([{:keys [index data] :as flex} search-query {:keys [limit] :or {limit nil}}]
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
                 (apply
                  sets/intersection
                  (loop [[w & ws] (reverse (sort-by count words))
                         r []
                         min-pos 0
                         max-pos (count index)]
                    (if w
                      (let [positions (find-positions index min-pos max-pos w)
                            pairs (filterv some? (mapv (fn [i] (first (data-rslice data [(inc i) nil] nil durable?)))
                                                       positions))]
                        (if (seq pairs)
                          (let [new-min (long (apply min (map first pairs)))
                                new-max (long (reduce (fn [mx [pos _]]
                                                        (max mx (+ pos (find-len index pos))))
                                                      0
                                                      pairs))]
                            (recur ws (conj r (set (map last pairs)))
                                   new-min new-max))
                          (recur ws (conj r #{}) min-pos max-pos)))
                      r)))]
             (lru-cache-put cache words result)
             (if limit (set (take limit result)) result))))))))

(defn search-gc [{:keys [index data] :as flex}]
  (let [durable? (durable-mode? flex)
        data-seq (if durable? (bt/bt-seq data) (seq data))
        pairs (mapv (fn [[pos id :as pair]]
                      (let [len (find-len index (first pair))]
                        [id (subs index pos (+ pos len))])) data-seq)
        new-flex (if durable?
                   (search-add (init {:durable? true :index-path (:index-path (meta flex))}) pairs)
                   (search-add (init) pairs))]
    (with-meta new-flex (meta flex))))

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
        base-stats {:mode (if durable? :durable :in-memory)
                    :document-count (count (:ids flex))
                    :index-size (count (:index flex))
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
           (let [btree (bt/open-btree index-path false)]
             ^{:cache (atom {})
               :durable? true
               :index-path index-path
               :version loaded-version}
             {:data btree
              :index (:index metadata)
              :ids (:ids metadata)}))))
     :cljs
     (throw (ex-info "Durable mode not supported in ClojureScript" {}))))
