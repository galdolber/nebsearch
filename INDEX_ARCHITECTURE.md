# NebSearch Index Architecture

## Table of Contents
1. [Overview](#overview)
2. [Core Concepts](#core-concepts)
3. [In-Memory Index Architecture](#in-memory-index-architecture)
4. [Disk-Based Index Architecture](#disk-based-index-architecture)
5. [Storage Abstraction Layer](#storage-abstraction-layer)
6. [Design Decisions & Rationale](#design-decisions--rationale)
7. [Performance Characteristics](#performance-characteristics)
8. [Copy-on-Write Semantics](#copy-on-write-semantics)

## Overview

NebSearch is a full-text search library for Clojure/ClojureScript that implements a dual-mode indexing system:
- **In-Memory Mode**: Fast, transient indexes using persistent sorted sets
- **Durable Mode**: Disk-backed lazy-loading indexes using B-trees with Copy-on-Write (COW) semantics

The library is inspired by tonsky/persistent-sorted-set's durability interface and provides seamless transition between modes.

### Key Innovation: Dual-Mode Architecture

The same index can operate in two fundamentally different modes:
1. **Start in-memory** for development/testing
2. **Store to disk** at any point to create a durable snapshot
3. **Restore lazily** to get a disk-backed version that loads nodes on-demand
4. **All operations work identically** regardless of mode

---

## Core Concepts

### Index Structure

Every NebSearch index is a map with four core components:

```clojure
{:data         ; Storage for [position id] or [position id text] entries
 :index        ; Concatenated normalized text string (search target)
 :ids          ; Map of {doc-id -> position} for document lookup
 :pos-boundaries ; Binary search index for position-to-document mapping
}
```

#### Example:
```clojure
;; Adding two documents:
(search-add (init) [["doc1" "hello world"]
                    ["doc2" "clojure programming"]])

;; Produces:
{:data #{[0 "doc1"] [12 "doc2"]}  ; Sorted set of position->id
 :index "hello worldñclojure programmingñ"  ; ñ = join char
 :ids {"doc1" 0, "doc2" 12}
 :pos-boundaries [[0 "doc1" 11] [12 "doc2" 19]]}
```

### The Magic Join Character (ñ)

```clojure
(def join-char \ñ)  ;; normalized to 'n' by encoder
```

**Why ñ?**
- Normalizes to 'n', ensuring it never appears in indexed text after normalization
- Acts as document delimiter in the concatenated index string
- Enables efficient string searching without document boundaries interfering

---

## In-Memory Index Architecture

### Data Structure: Persistent Sorted Set

Source: `nebsearch.core/init` (core.cljc:63-80)

```clojure
(defn init []
  ^{:cache (atom {})}
  {:data (pss/sorted-set)    ; tonsky/persistent-sorted-set
   :index ""
   :ids {}
   :pos-boundaries []})
```

**Why Persistent Sorted Set?**
- O(log n) ordered operations (conj, disj, slice)
- Structural sharing for efficient immutable updates
- Compatible with Clojure's transient optimization for batch operations

### Search Algorithm: Binary Search + Set Intersection

Source: `nebsearch.core/search` (core.cljc:464-508)

**The "Magic Trick" - Position Boundaries:**

```clojure
(defn- build-pos-boundaries [ids index]
  "Build sorted vector of [position, doc-id, text-length]"
  (vec (sort-by first
                (map (fn [[id pos]]
                       (let [len (find-len index pos)]
                         [pos id len]))
                     ids))))

(defn- find-doc-at-pos [pos-boundaries pos]
  "Binary search: O(log n) where n = number of documents"
  ;; ... binary search implementation ...
)
```

**Why This Matters:**
- Traditional approach: Iterate through B-tree entries to find which document contains each position → O(n) per position
- Binary search approach: Search sorted position boundaries → **O(log n) per position**
- For queries with multiple terms, this dramatically reduces lookup cost

### Search Flow:

```
1. Normalize query → "Hello World" → "hello world"
2. Split into terms → ["hello", "world"]
3. For each term (sorted by length, longest first):
   a. String search in :index → find all positions
   b. Binary search pos-boundaries → map positions to doc-ids
   c. Convert to set for O(1) intersection
4. Intersect all doc-id sets → final results
5. Cache result with LRU eviction
```

**Optimization - Search Range Narrowing:**
Source: core.cljc:483-505

```clojure
;; After each term match, narrow search range for next term
(let [matching-bounds (filter (fn [[_ id _]] (contains? doc-id-set id))
                              pos-boundaries)
      new-min (apply min (map first matching-bounds))
      new-max (reduce (fn [mx [pos _ len]] (max mx (+ pos len))) 0 matching-bounds)]
  ;; Search only within [new-min, new-max] for next term
  (recur ws (conj r doc-id-set) new-min new-max))
```

This progressive range narrowing makes multi-term searches increasingly efficient.

### Adding Documents (In-Memory)

Source: `nebsearch.core/search-add` (core.cljc:375-445)

**Algorithm:**
```
1. Check for document updates (same id already exists)
2. If updates exist, remove old entries first (search-remove)
3. Use transients for performance:
   - transient sorted-set for :data
   - transient map for :ids
   - transient vector for text accumulation
4. For each document:
   - Normalize and encode text
   - Append to index string with join character
   - Add [position id] to sorted set
   - Record position in ids map
   - Add boundary to pos-boundaries
5. Persist transients and return new index
6. Create fresh cache (atom {}) for new version
```

**Key Optimization - Batch String Building:**
Source: core.cljc:428-436

```clojure
;; JVM: Use StringBuilder for large batches (>100 docs)
(def ^:dynamic *batch-threshold* 100)

(if (>= (count words) *batch-threshold*)
  (let [sb (StringBuilder. index)]
    (doseq [word words]
      (.append sb word)
      (.append sb join-char))
    (.toString sb))
  (str index (string/join join-char words) join-char))
```

String concatenation is expensive; StringBuilder provides significant speedup for bulk operations.

### Removing Documents (In-Memory)

Source: `nebsearch.core/search-remove` (core.cljc:317-373)

**Algorithm:**
```
1. Find positions of documents to remove
2. Use transients for performance
3. For each document:
   - Remove [position id] from sorted set
   - Replace text in index with spaces (fragmentation!)
4. Update cache by filtering out removed doc-ids from cached results
5. Calculate fragmentation ratio
6. Auto-GC if > 30% fragmented (configurable via *auto-gc-threshold*)
```

**Fragmentation Management:**

```clojure
(def ^:dynamic *auto-gc-threshold* 0.3)  ;; Auto-GC when >30% fragmented

;; Fragmentation = spaces / total chars
(defn- calculate-fragmentation [{:keys [index]}]
  (if (empty? index)
    0.0
    (let [space-count (count (filter #(= % \space) index))
          total-chars (count index)]
      (/ (double space-count) total-chars))))
```

**Why Spaces Instead of Rebuilding?**
- Deleting characters and shifting would break all position references
- Positions are stored in sorted set entries and ids map
- Spaces maintain position stability
- Periodic GC (search-gc) rebuilds when fragmentation is high

### Garbage Collection (In-Memory)

Source: `nebsearch.core/search-gc` (core.cljc:510-524)

```clojure
(defn search-gc [flex]
  "Rebuild index to remove fragmentation"
  (let [data-seq (seq data)
        ;; Extract original text from index string
        pairs (mapv (fn [[pos id]]
                     (let [len (find-len index pos)]
                       [id (subs index pos (+ pos len))]))
                   data-seq)
        ;; Build fresh index from scratch
        new-flex (search-add (init) pairs)]
    (with-meta new-flex (meta flex))))
```

**When GC Triggers:**
- Automatically after search-remove if fragmentation > 30%
- Manually via (search-gc index)
- **Never in durable mode** - GC would break COW semantics

---

## Disk-Based Index Architecture

### Data Structure: B-Tree with Lazy Loading

Source: `nebsearch.btree/DurableBTree` (btree.cljc:81-101)

```clojure
(defrecord DurableBTree [storage      ;; IStorage implementation
                         root-offset] ;; Current root address
  IBTree
  (bt-insert [this entry] ...)
  (bt-bulk-insert [this entries] ...)
  (bt-delete [this entry] ...)
  (bt-range [this start end] ...)
  (bt-search [this pos] ...)
  (bt-seq [this] ...))
```

**Why B-Tree?**
- Disk-friendly: All I/O is in aligned blocks (4KB nodes)
- Efficient range queries: O(log n) seek + sequential scan
- Copy-on-Write friendly: Only modified nodes are written
- Standard database structure with decades of optimization research

### B-Tree Configuration

```clojure
(def ^:const btree-order 128)       ; Max children per internal node
(def ^:const leaf-capacity 256)     ; Max entries per leaf node
(def ^:const node-size 4096)        ; Target node size (disk alignment)
```

**Why These Values?**
- 4KB = standard disk/filesystem block size → minimizes I/O overhead
- 128 children = good fanout for disk-based trees (reduces tree height)
- 256 leaf entries = balances memory vs. I/O (load more entries per seek)

### Node Types

**Leaf Node:**
```clojure
{:type :leaf
 :entries [[pos id text] ...]  ; Sorted entries (text stored in B-tree!)
 :next-leaf offset}             ; Linked list for sequential scans
```

**Internal Node:**
```clojure
{:type :internal
 :keys [separator-keys ...]     ; Split points between children
 :children [offsets ...]}       ; Child node addresses
```

**Critical Design Choice: Text Storage**

In durable mode, entries are `[pos id text]` not `[pos id]`:

```clojure
;; In-Memory: [pos id]
[0 "doc1"]

;; Durable: [pos id text]
[0 "doc1" "hello world"]
```

**Why Store Text in B-Tree?**
- Enables COW: Each version has its own text, independent of index string
- Prevents corruption: Index string can't get out of sync with B-tree positions
- Structural sharing: Unchanged nodes reference old text without copying

### Insert Operation (COW)

Source: `nebsearch.btree/bt-insert-impl` (btree.cljc:170-259)

**Algorithm:**
```
1. If empty tree: Create first leaf node
2. Else, recursively descend to target leaf:
   a. Load node from storage (lazy - may be on disk)
   b. If internal node: Find child index via binary search of keys
   c. Recurse into child
3. At leaf level:
   a. Insert entry in sorted order
   b. If fits (≤256 entries): Write new leaf node, return offset
   c. If overflow: Split leaf into two nodes
      - Left half + right half
      - Return split key (first key of right node)
4. Propagate splits upward:
   a. Insert split key into parent
   b. If parent overflows: Split parent recursively
5. If root splits: Create new root with split key
6. Return new DurableBTree with updated root-offset
```

**Critical: NO MUTATION**
Every node modification creates a **new node** at a **new offset**. Old nodes remain unchanged on disk.

### Bulk Insert Optimization

Source: `nebsearch.btree/bt-bulk-insert-impl` (btree.cljc:297-347)

**Problem:** Sequential inserts into a B-tree are slow (many node splits).

**Solution:** Build tree bottom-up from pre-sorted entries.

```
1. Sort all entries by [pos id]
2. Build leaf level:
   - Chunk entries into groups of 256
   - Create one leaf node per chunk
   - Record min/max keys for each leaf
3. Build internal levels bottom-up:
   - Group child nodes into chunks of 128
   - Create internal node for each chunk
   - Keys = minimum key of each child (except first)
4. Repeat until single root node remains
```

**Performance Impact:**
- Regular inserts: O(n log n) with many disk writes
- Bulk insert: O(n) with minimal disk writes (one write per node)
- **10-100x faster** for large initial indexes

### Range Query (COW-Aware)

Source: `nebsearch.btree/bt-range-impl` (btree.cljc:121-151)

**Critical Design Choice:**
Traverse tree structure, **NOT** next-leaf pointers.

**Why?**
```
Version 1: Leaf1 -> Leaf2 -> Leaf3
Version 2: Leaf1 -> Leaf4 -> Leaf3  (modified Leaf2 -> Leaf4)

If we follow next-leaf pointers from old root:
- May traverse into new version's nodes
- Results mix old and new data → CORRUPTION
```

**Solution:**
```clojure
(defn- bt-range-impl [btree start end]
  (letfn [(node-range [node-offset]
            (let [node (storage/restore storage node-offset)]
              (case (:type node)
                :leaf
                ;; Filter entries by range
                (filter (fn [[pos _]] (and (>= pos start) (<= pos end)))
                        (:entries node))

                :internal
                ;; Only visit children whose range overlaps [start, end]
                (mapcat node-range (relevant-children node start end)))))]
    (node-range root-offset)))
```

Traverse tree top-down, filtering branches that can't contain results.

### Delete Operation (Simplified COW)

Source: `nebsearch.btree/bt-delete-impl` (btree.cljc:261-295)

**Algorithm:**
```
1. Recursively descend to target leaf
2. Remove entry from leaf
3. Write new leaf node (even if empty - no merging yet)
4. Propagate new child offset upward
5. Each ancestor node gets rewritten with updated child pointer
6. Return new DurableBTree with updated root-offset
```

**Simplified Implementation:**
- No node merging (empty leaves allowed)
- No key redistribution between siblings
- Trades optimal space usage for implementation simplicity

**Future Optimization:**
Could add merging/redistribution, but COW makes this complex:
- Can't modify sibling nodes (would break other versions)
- Would need to clone siblings and update parent → cascading rewrites

---

## Storage Abstraction Layer

Source: `nebsearch.storage` (storage.cljc:1-94)

### IStorage Protocol

```clojure
(defprotocol IStorage
  (store [this node]
    "Store a node and return an address")

  (restore [this address]
    "Restore a node from storage using its address"))
```

**Key Insight:**
Storage implementations are **pluggable**. The B-tree doesn't care about disk format, memory layout, or network protocols - it just stores and restores nodes via addresses.

### Additional Protocols

```clojure
(defprotocol IStorageRoot
  (set-root-offset [this offset])
  (get-root-offset [this]))

(defprotocol IStorageSave
  (save [this]
    "Explicitly save changes to durable storage"))

(defprotocol IStorageClose
  (close [this]))

(defprotocol IStorageStats
  (storage-stats [this]))
```

These are **optional** - only implement if needed for your storage backend.

### Memory Storage Implementation

Source: `nebsearch.memory-storage` (memory_storage.cljc:1-69)

```clojure
(defrecord MemoryStorage [*storage      ; atom: {address -> node-edn-string}
                          *counter      ; atom: monotonic counter for addresses
                          *root-offset] ; atom: current root address
  IStorage
  (store [this node]
    (let [address (swap! *counter inc)
          node-edn (pr-str node)]  ; Serialize to EDN (simulate disk)
      (swap! *storage assoc address node-edn)
      address))

  (restore [this address]
    (-> (get @*storage address)
        edn/read-string
        (assoc :offset address))))
```

**Use Cases:**
- Testing the storage abstraction
- Temporary indexes that don't need persistence
- Reference implementation for new storage backends

**Why EDN Serialization?**
- Simulates disk serialization overhead
- Makes testing realistic (catches serialization bugs)
- Human-readable for debugging

### Disk Storage Implementation

Source: `nebsearch.disk-storage` (disk_storage.cljc:1-201)

```clojure
(defrecord DiskStorage [file-path
                        ^RandomAccessFile raf
                        node-cache        ; atom: {offset -> node}
                        root-offset-atom  ; atom: current root (not saved until explicit save)
                        btree-order])
```

#### File Format

```
[ Header: 256 bytes ]
[ Node 1 ]
[ Node 2 ]
[ Node 3 ]
...
```

**Header Structure:**
```clojure
Bytes 0-7:   Magic number "NEBSRCH\0"
Bytes 8-11:  Version (int32)
Bytes 12-19: Root offset (int64)
Bytes 20-27: Node count (int64)
Bytes 28-35: Free list head (int64) - unused for now
Bytes 36-39: B-tree order (int32)
Bytes 40-255: Padding (reserved for future use)
```

**Node Storage Format:**
```
[ Length: 4 bytes ]
[ Node Data: N bytes (EDN serialized) ]
[ CRC32 Checksum: 4 bytes ]
```

#### Store Operation

```clojure
(store [this node]
  (let [offset (.length raf)]  ; Append at end
    (.seek raf offset)
    (let [node-bytes (serialize-node node)
          checksum (crc32 node-bytes)
          length (alength node-bytes)]
      (.writeInt raf length)
      (.write raf node-bytes)
      (.writeInt raf checksum)
      (swap! node-cache assoc offset (assoc node :offset offset))
      offset)))
```

**Why Append-Only?**
- Copy-on-Write: Never overwrite existing nodes
- Crash safety: New nodes don't corrupt old data
- Multi-version: Multiple B-tree versions coexist in same file

**Why CRC32?**
- Detect disk corruption
- Fast checksum computation (~1 GB/sec)
- Good enough for non-cryptographic integrity checks

#### Restore Operation

```clojure
(restore [this address]
  (if-let [cached (get @node-cache address)]
    cached  ; Cache hit
    (do
      (.seek raf address)
      (let [length (.readInt raf)
            node-bytes (byte-array length)]
        (.read raf node-bytes)
        (let [stored-checksum (.readInt raf)
              computed-checksum (crc32 node-bytes)]
          (when (not= stored-checksum computed-checksum)
            (throw (ex-info "Checksum mismatch" {...})))
          (let [node (deserialize-node node-bytes address)]
            (swap! node-cache assoc address node)
            node))))))
```

**Caching Strategy:**
- Cache all restored nodes in memory
- Never evict (simple unbounded cache)
- **Trade-off:** Memory usage grows with working set, but avoids cache misses

**Future Optimization:**
- LRU eviction policy
- Configurable cache size limit
- Bloom filters for negative lookups

#### Explicit Save Semantics

Source: disk_storage.cljc:127-148

**Critical Design Choice: Changes are NOT saved until explicit `save` call.**

```clojure
;; Make changes
(def btree2 (bt/bt-insert btree [100 "doc1"]))
;; root-offset changed in memory, but NOT on disk

;; Explicitly save
(storage/set-root-offset storage (:root-offset btree2))
(storage/save storage)
;; NOW changes are durable
```

**Why Explicit Save?**
1. **COW correctness:** Multiple versions exist in memory before picking which to persist
2. **Performance:** Batch many operations, save once
3. **Transactions:** Make multiple changes atomically
4. **Recovery:** Abort changes by simply not calling save

**Save Implementation:**
```clojure
(save [this]
  (let [root-offset @root-offset-atom]
    ;; Count nodes by traversing from root
    (let [node-count (count-reachable-nodes this root-offset)]
      ;; Write header with current root
      (write-header raf root-offset node-count btree-order)
      ;; Sync to disk (fsync)
      (.force (.getChannel raf) true)
      this)))
```

The `.force` call is crucial - it flushes OS buffers to disk, ensuring durability.

---

## Design Decisions & Rationale

### 1. Dual-Mode Architecture

**Decision:** Support both in-memory and disk-backed indexes with identical APIs.

**Rationale:**
- **Development workflow:** Start fast (in-memory), persist when needed
- **Testing:** Test with memory storage, deploy with disk storage
- **Flexibility:** Choose performance vs. durability per use case
- **Gradual adoption:** Existing users can add persistence later

**Implementation:**
```clojure
(defn- durable-mode? [flex]
  (or (boolean (:durable? (meta flex)))
      (boolean (:lazy? (meta flex)))
      (instance? DurableBTree (:data flex))))

(defn- data-conj [data entry durable?]
  (if durable?
    (bt/bt-insert data entry)
    (conj data entry)))
```

Polymorphic operations check mode and dispatch appropriately.

### 2. Copy-on-Write (COW) Semantics

**Decision:** Never mutate existing nodes; always create new versions.

**Rationale:**
- **Structural sharing:** Multiple versions share unchanged subtrees
- **Time travel:** Access any historical version
- **Crash safety:** Old versions remain valid if new version fails
- **Multi-version concurrency:** Multiple readers/writers don't conflict

**Cost:**
- More disk writes (can't update in-place)
- Garbage accumulation (need periodic compaction)

**Mitigation:**
- Bulk operations minimize writes
- Compaction via store/restore cycle

### 3. Position-Based Indexing

**Decision:** Store documents as concatenated string with positions, not as separate strings.

**Rationale:**
- **Fast string search:** Single pass through one string
- **Cache-friendly:** Sequential memory access
- **Simple code:** No complex document boundary logic

**Cost:**
- Fragmentation from deletions
- GC required to reclaim space

**Mitigation:**
- Auto-GC threshold (configurable)
- Manual GC on-demand

### 4. Binary Search Position Boundaries

**Decision:** Use sorted vector of document boundaries instead of B-tree lookups.

**Rationale:**
- **Performance:** O(log n) binary search vs. O(log n) B-tree traversal
  - Binary search: Memory only, no I/O, cache-friendly
  - B-tree: May need disk I/O, cache misses
- **Simplicity:** Small vector in memory vs. complex tree traversal

**When Binary Search Wins:**
- Number of documents < 100K (fits in memory)
- Search queries common (hot path optimization)

**When B-Tree Would Win:**
- Millions of documents (boundary vector doesn't fit in memory)
- Insert-heavy workload (vector rebuild cost dominates)

### 5. Text Storage in B-Tree (Durable Mode)

**Decision:** Store full text `[pos id text]` in B-tree nodes, not just `[pos id]`.

**Rationale:**
- **COW correctness:** Index string is immutable per version
- **Version independence:** Each B-tree version owns its text
- **No synchronization issues:** Can't have B-tree/index mismatch

**Cost:**
- Larger nodes (text duplicated in both index string and B-tree)
- More disk space

**Trade-off:** Correctness and simplicity over space efficiency.

### 6. Explicit Save Semantics

**Decision:** Changes to disk-backed indexes are not saved until explicit `save` call.

**Rationale:**
- **Batch optimization:** Amortize fsync cost over many changes
- **Transaction semantics:** All-or-nothing durability
- **COW support:** Create multiple versions in memory, save best one

**User Experience:**
- Must remember to call `save` (error-prone)
- But: Explicit is better than implicit (Python philosophy applies)

### 7. Pluggable Storage Abstraction

**Decision:** Define IStorage protocol instead of hardcoding disk/memory.

**Rationale:**
- **Testability:** Easy to mock storage for unit tests
- **Flexibility:** Users can implement custom backends (S3, Redis, etc.)
- **Portability:** ClojureScript can't use RandomAccessFile (different backend needed)

**Inspired by:** tonsky/persistent-sorted-set's IStorage interface

### 8. No Normalization Configurability

**Decision:** Hardcode normalization to NFD + lower-case + diacritical removal.

**Rationale:**
- **Simplicity:** One code path, one behavior
- **Common case:** Covers 90% of use cases
- **Performance:** No indirection or dynamic dispatch

**Future:** Could add `:encoder` option if users need custom normalization.

### 9. LRU Cache for Search Results

**Decision:** Cache search results with LRU eviction (default 1000 entries).

**Rationale:**
- **Search optimization:** Identical queries return instantly
- **Multi-term queries:** Reuse intermediate results
- **Memory bound:** LRU prevents unbounded growth

**Implementation:**
```clojure
(def ^:dynamic *cache-size* 1000)

(defn- lru-cache-evict [cache max-size]
  (if (<= (count cache) max-size)
    cache
    (let [keep-count (long (* max-size 0.8))]  ; Remove oldest 20%
      (into {} (take keep-count (sort-by (comp :access-time val) > cache))))))
```

**Why 20% eviction?**
- Amortizes eviction cost
- Avoids thrashing at boundary

### 10. Longest-First Term Ordering

**Decision:** Process search terms from longest to shortest.

**Rationale:**
- **Selectivity:** Longer terms are more selective (fewer matches)
- **Early pruning:** Smallest result set first → faster intersections
- **Range narrowing:** More effective when starting with rare terms

**Example:**
```
Query: "the programming"
Order: ["programming", "the"]  (not ["the", "programming"])

"the" might match 1000 docs
"programming" might match 10 docs
Intersect 10 & 1000 = faster than intersect 1000 & 10
```

---

## Performance Characteristics

### Time Complexity

| Operation | In-Memory | Durable (Cold) | Durable (Cached) |
|-----------|-----------|----------------|------------------|
| Insert | O(log n) | O(log n × disk) | O(log n) |
| Bulk Insert (m docs) | O(m log n) | O(m) | O(m) |
| Delete | O(log n) | O(log n × disk) | O(log n) |
| Search (k terms) | O(k × m + k log d) | O(k × m + k log d) | O(k × m + k log d) |
| Range Query | O(log n + r) | O(log n × disk + r) | O(log n + r) |
| GC (Rebuild) | O(n log n) | N/A | N/A |

Where:
- n = total entries in data structure
- d = number of documents
- m = length of index string
- r = number of results
- disk = disk I/O factor (~1000x slower than RAM)
- k = number of search terms

### Search Complexity Breakdown

```
O(k × m + k log d)

k × m:     String search for k terms across m characters
k log d:   k binary searches of d document boundaries
```

**Why This Matters:**
- String search is O(m) with Boyer-Moore or similar
- Binary search is O(log d) not O(log n) because we search documents, not entries
- **If d << n (many entries per document), this is a huge win!**

### Space Complexity

| Component | In-Memory | Durable |
|-----------|-----------|---------|
| :data | O(n) entries | O(n) entries (lazy) |
| :index | O(m) chars | O(m) chars |
| :ids | O(d) entries | O(d) entries |
| :pos-boundaries | O(d) entries | O(d) entries |
| B-Tree nodes | N/A | O(n / 256) nodes on disk |
| Node cache | N/A | O(working set) |

**Key Insight:** Most memory usage is proportional to number of **documents** (d), not entries (n).

### Disk I/O Patterns (Durable Mode)

**Insert (Single):**
```
Read:  O(log n) nodes from root to leaf
Write: O(log n) new nodes (COW path from leaf to root)
Seek:  O(log n) random seeks
```

**Bulk Insert (m entries, pre-sorted):**
```
Read:  0 nodes (build from scratch)
Write: O(n / 256) leaf nodes + O((n / 256) / 128) internal nodes
Seek:  0 seeks (sequential writes)
```

**Search:**
```
Read:  0-1 nodes (binary search avoids B-tree traversal!)
Write: 0 nodes
Seek:  0 seeks
```

**This is why binary search is so clever** - it bypasses the B-tree for reads!

---

## Copy-on-Write Semantics

### How COW Works in NebSearch

#### In-Memory Mode

```clojure
;; Version 1
(def v1 (search-add (init) [["doc1" "hello"]]))

;; Version 2 shares structure with v1
(def v2 (search-add v1 [["doc2" "world"]]))

;; Both versions are independent
(search v1 "world")  ; => #{}
(search v2 "world")  ; => #{"doc2"}
```

**Sharing Mechanism:**
- Persistent sorted set shares tree structure
- Only new entries create new tree nodes
- Old tree remains valid (immutable)

#### Durable Mode

```clojure
;; Version 1
(def storage (disk-storage/open-disk-storage "index.dat"))
(def v1 (search-add (init) [["doc1" "hello"]]))
(def ref1 (store v1 storage))
;; Nodes: [N1, N2, N3] written to disk

;; Version 2 shares most nodes with v1
(def v2 (search-add v1 [["doc2" "world"]]))
(def ref2 (store v2 storage))
;; Nodes: [N4, N5] written (N1 reused!)

;; Both versions coexist in same file
(def v1-restored (restore storage ref1))  ; Uses N1, N2, N3
(def v2-restored (restore storage ref2))  ; Uses N1, N4, N5
```

**Sharing Mechanism:**
- Unchanged nodes are not rewritten
- New nodes reference old nodes via offsets
- Both root offsets are valid simultaneously

### COW Benefits

1. **Efficient Updates**
   ```
   10,000 entry tree, insert 1 entry:

   Without COW:  Rewrite entire tree (10,000 entries)
   With COW:     Write only path to leaf (log₁₂₈ 10000 ≈ 3 nodes)
   ```

2. **Version History**
   ```clojure
   ;; Save checkpoints
   (def ref-v1 (store index-v1 storage))
   (def ref-v2 (store index-v2 storage))
   (def ref-v3 (store index-v3 storage))

   ;; Time travel to any version
   (restore storage ref-v1)  ; Back to version 1
   (restore storage ref-v2)  ; Back to version 2
   ```

3. **Crash Safety**
   ```
   Transaction in progress:
   - Old version valid at offset 1000
   - Writing new version at offset 5000
   - Crash occurs
   - Old version still intact (never modified)
   - New version incomplete (never updated root pointer)
   - Recovery: Just use old root offset
   ```

4. **Concurrent Access**
   ```clojure
   ;; Reader 1: Using version at offset 1000
   ;; Reader 2: Using version at offset 2000
   ;; Writer:   Creating version, will be at offset 3000

   ;; All three can operate simultaneously
   ;; No locks needed (readers never see partial updates)
   ```

### COW Challenges

1. **Garbage Accumulation**
   ```
   After 100 updates:
   - 100 root offsets exist in file
   - Only latest root offset is referenced
   - Old nodes are garbage (unreachable)

   Solution: Compact via store/restore cycle
   ```

2. **Fragmentation**
   ```
   File layout after many COW updates:
   [N1][N2][N3][N4][N5]...[N1000]

   Related nodes scattered across file
   Sequential scan requires many seeks

   Solution: Periodic compaction to cluster related nodes
   ```

3. **Write Amplification**
   ```
   Insert 1 entry (100 bytes)
   → Write entire COW path (3 nodes × 4KB = 12KB)
   Write amplification: 12KB / 100 bytes = 120x

   Mitigation: Bulk operations, batch many changes before save
   ```

### Compaction (Store/Restore Cycle)

```clojure
;; Lazy index with garbage
(def lazy-idx (restore storage ref-old))
(storage-stats storage)
;; => {:node-count 1000, :size 4MB}

;; Compact: Store fresh copy
(def ref-new (store lazy-idx storage-new))
(storage-stats storage-new)
;; => {:node-count 100, :size 400KB}  ; 10x smaller!

;; Close old storage, keep new
(close storage)
```

**What Compaction Does:**
- Traverses only reachable nodes
- Writes them sequentially to new file
- Result: No garbage, clustered layout, optimal size

**When to Compact:**
- After many deletes
- After many small updates
- When file size >> expected size

---

## Conclusion

NebSearch's architecture balances **simplicity, performance, and flexibility** through careful design choices:

1. **Dual-mode architecture** enables easy development (in-memory) → production (durable) workflows
2. **Copy-on-Write semantics** provide time travel, crash safety, and structural sharing
3. **Pluggable storage** allows testing, custom backends, and platform portability
4. **Binary search optimization** bypasses B-tree for search queries (huge performance win)
5. **Explicit save semantics** give users control over durability vs. performance trade-offs

The key innovation is **seamless transition between modes** - start in-memory, persist when needed, restore lazily, all with identical APIs.

**Design Philosophy:**
- **Simple first, optimize later** (e.g., no node merging in B-tree deletes)
- **Correctness over performance** (e.g., tree traversal instead of next-leaf pointers)
- **Explicit over implicit** (e.g., explicit save calls)
- **Flexible but opinionated** (e.g., pluggable storage, but hardcoded normalization)

This architecture is heavily inspired by Rich Hickey's principles (immutability, simplicity) and Nikita Prokopov's (tonsky) pragmatic approach to persistent data structures.
