# Magic Trick #1: Binary Search Position Index

## Summary

Current performance: **317x slower** than in-memory (5000 docs: 91s vs 287ms)
Target: **10-30x faster** → ~3-9 seconds for 5000 docs
Memory overhead: **16-24 bytes per document** (~120KB for 5000 docs)

## The Trick

We already have `{doc-id → start-pos}` in the `:ids` map!
**Invert it** to get sorted `[[pos, id, len], ...]` and use **binary search**.

Instead of 1000 B-tree lookups (one per word position), do **1000 binary searches** on a small in-memory array.

## Implementation Steps

### 1. Add Helper Functions (after `find-len` at line ~332)

```clojure
;; Position boundaries for fast O(log n) position→doc-id lookups
(defn- build-pos-boundaries
  "Build sorted vector of [position, doc-id, text-length] from :ids map.
   Enables binary search: O(log n) where n = number of documents."
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
            ;; Position is within this document
            (and (>= pos start-pos) (< pos end-pos))
            doc-id

            ;; Position is before this document
            (< pos start-pos)
            (recur lo (dec mid))

            ;; Position is after this document
            :else
            (recur (inc mid) hi)))))))
```

### 2. Update `init` Function

Add `:pos-boundaries []` to both branches (durable and in-memory):

```clojure
;; Durable mode
{:data (bt/open-btree index-path true)
 :index ""
 :ids {}
 :pos-boundaries []}  ; ← ADD THIS

;; In-memory mode
{:data (pss/sorted-set)
 :index ""
 :ids {}
 :pos-boundaries []}  ; ← ADD THIS
```

### 3. Update `search-add` (Both Modes)

**Durable mode** (around line 461):
```clojure
(let [new-index (str index (string/join join-char r) join-char)
      pos-boundaries (build-pos-boundaries ids new-index)]  ; ← ADD
  (-> (assoc flex
             :ids ids
             :index new-index
             :data data
             :pos-boundaries pos-boundaries)  ; ← ADD
      (vary-meta assoc :cache (atom {}))))
```

**In-memory mode** (around line 491):
```clojure
(let [words (persistent! r)
      new-index #?(:clj ...)
      persistent-ids (persistent! ids)  ; ← ADD
      pos-boundaries (build-pos-boundaries persistent-ids new-index)]  ; ← ADD
  (-> (assoc flex
             :ids persistent-ids
             :index new-index
             :data (persistent! data)
             :pos-boundaries pos-boundaries)  ; ← ADD
      (vary-meta assoc :cache (atom {}))))
```

### 4. Update `search-remove` (Both Modes)

**Durable mode** (around line 402):
```clojure
(let [old-cache @(:cache (meta flex))
      removed-ids (set id-list)
      new-cache (atom ...)
      updated-ids (apply dissoc ids id-list)  ; ← ADD
      pos-boundaries (build-pos-boundaries updated-ids index)]  ; ← ADD
  (-> (assoc flex
             :ids updated-ids
             :data data
             :index index
             :pos-boundaries pos-boundaries)  ; ← ADD
      (vary-meta assoc :cache new-cache)))
```

**In-memory mode** (around line 426):
```clojure
(let [old-cache @(:cache (meta flex))
      removed-ids (set id-list)
      new-cache (atom ...)
      updated-ids (apply dissoc ids id-list)  ; ← ADD
      pos-boundaries (build-pos-boundaries updated-ids index)]  ; ← ADD
  (-> (assoc flex
             :ids updated-ids
             :data (persistent! data)
             :index index
             :pos-boundaries pos-boundaries)  ; ← ADD
      (vary-meta assoc :cache new-cache)))
```

### 5. Update `search` Function (THE MAGIC!)

Replace the complex B-tree lookup (lines 543-553) with simple binary search:

```clojure
(defn search
  ([flex search-query]
   (search flex search-query nil))
  ([{:keys [index data pos-boundaries] :as flex} search-query {:keys [limit] :or {limit nil}}]  ; ← ADD pos-boundaries
   {:pre [(map? flex)]}
   (if-not (and search-query data)
     #{}
     (let [cache (:cache (meta flex))
           words (default-splitter (default-encoder search-query))]
       (if (empty? words)
         #{}
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
                      r)))]
             (lru-cache-put cache words result)
             (if limit (set (take limit result)) result))))))))
```

### 6. Update `open-index` (if you reopen indexes)

When reopening, rebuild pos-boundaries from the loaded :ids:

```clojure
(defn open-index [{:keys [index-path]}]
  (let [metadata (meta/read-metadata index-path)
        btree (bt/open-btree index-path false)
        pos-boundaries (build-pos-boundaries (:ids metadata) (:index metadata))]  ; ← ADD
    (with-meta
      (assoc metadata
             :data btree
             :pos-boundaries pos-boundaries)  ; ← ADD
      {:cache (atom {})
       :durable? true
       :index-path index-path
       :version (:version metadata)})))
```

## Expected Performance

| Dataset | Before (Phase 2) | After (Magic Trick #1) | Speedup |
|---------|------------------|------------------------|---------|
| 100 docs | 110ms | ~50ms | 2.2x |
| 1000 docs | 4.50s | ~500ms | 9x |
| 5000 docs | 91.18s | ~3-9s | 10-30x |

## Why It Works

**Before:**
- Search finds 1000 word positions
- For each position: traverse B-tree (O(log N) where N = total entries)
- Total: 1000 × O(log 10,000) = ~13,000 operations

**After:**
- Search finds 1000 word positions
- For each position: binary search small array (O(log n) where n = # docs)
- Total: 1000 × O(log 5000) = ~13 comparisons each = 13,000 comparisons
- But on **tiny in-memory array** vs **disk-backed B-tree**!

The magic is: **13 comparisons on a 120KB array is 100x faster than 13 node reads from disk!**

## Testing

After implementation, run:
```bash
java -cp "lib/*:src:test" clojure.main run_all_tests.clj
java -cp "lib/*:src:." clojure.main benchmark_quick.clj
```

All 1257 tests should pass, and search should be 10-30x faster!
