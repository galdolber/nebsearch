# Durable Index Optimization Proposals

## Current Performance Issues

### Benchmark Results
- **In-memory search**: 302ms for 100 queries on 5,000 docs
- **Durable search**: 87.76s for 100 queries on 5,000 docs
- **Performance gap**: **291x slower**

### Root Cause Analysis

The bottleneck is in the search path:

```clojure
;; For EVERY word position found in index string:
pairs (filterv some? (mapv (fn [i]
                            (first (data-rslice data [(inc i) nil] nil durable?)))
                           positions))
```

**Problem**: Each `data-rslice` call triggers a B-tree range query that:
1. Starts at root node (disk read)
2. Traverses down tree (multiple disk reads)
3. Filters entries
4. Returns result

If a word appears 1000 times, we do **1000 separate B-tree traversals**.

---

## Optimization Proposals (Ranked by Impact)

### 🔥 **Option 1: In-Memory Position Index (HIGHEST IMPACT)**

**Impact**: 50-100x speedup
**Complexity**: Low
**Memory**: ~40 bytes per document

#### Concept
Keep a reverse index `position -> document-id` in memory alongside the existing `:ids` map.

#### Implementation
```clojure
;; Add to index structure
{:ids {doc-id -> position}     ; existing
 :pos-idx {position -> doc-id}  ; NEW - reverse lookup
 :index "..."
 :data btree}
```

#### Changes Required
1. Build `pos-idx` during `search-add` (O(1) per insert)
2. Update `pos-idx` during `search-remove` (O(1) per remove)
3. Rebuild `pos-idx` during `search-gc`
4. Use `pos-idx` in search instead of B-tree lookups

#### Search Optimization
```clojure
;; BEFORE: B-tree traversal for each position
pairs (mapv (fn [i] (first (data-rslice data [(inc i) nil] nil true))) positions)

;; AFTER: Direct hash map lookup
pairs (mapv (fn [i] [i (get pos-idx i)]) positions)
```

#### Pros
- ✅ Massive search speedup (eliminates 90%+ of B-tree reads)
- ✅ Simple implementation
- ✅ Minimal memory overhead (~40 bytes per doc)
- ✅ No changes to persistence format

#### Cons
- ⚠️ Extra memory usage (acceptable for most use cases)
- ⚠️ Need to rebuild on open (fast - single B-tree traversal)

---

### 🚀 **Option 2: Memory-Mapped Files**

**Impact**: 10-20x speedup
**Complexity**: Medium
**Memory**: Depends on OS page cache

#### Concept
Use Java NIO `MappedByteBuffer` to memory-map the B-tree file instead of using `RandomAccessFile`.

#### Implementation
```clojure
;; Replace RandomAccessFile with MappedByteBuffer
(def file-channel (.getChannel (RandomAccessFile. file "rw")))
(def mapped-buffer (.map file-channel
                         FileChannel$MapMode/READ_WRITE
                         0
                         file-size))
```

#### Pros
- ✅ Significantly faster reads (no system calls after mapping)
- ✅ OS handles paging and caching
- ✅ No change to B-tree structure
- ✅ Works well with large datasets

#### Cons
- ⚠️ JVM-only (not ClojureScript compatible)
- ⚠️ More complex error handling
- ⚠️ Need to handle file growth for appends

---

### ⚡ **Option 3: Batch B-Tree Lookups**

**Impact**: 5-10x speedup
**Complexity**: Medium
**Memory**: Minimal

#### Concept
Instead of N separate B-tree lookups, batch them into a single tree traversal.

#### Implementation
```clojure
(defn bt-batch-lookup [btree positions]
  "Look up multiple positions in a single tree traversal"
  (let [sorted-positions (sort positions)]
    (loop [node-offset root
           remaining sorted-positions
           results {}]
      ;; Traverse tree once, collecting all matching positions
      ...)))
```

#### Pros
- ✅ Significantly reduces tree traversals
- ✅ Better cache locality
- ✅ No extra memory overhead
- ✅ Works with existing structure

#### Cons
- ⚠️ More complex B-tree code
- ⚠️ Still slower than Option 1

---

### 📦 **Option 4: Compressed Index String**

**Impact**: 2-5x speedup (for large indexes)
**Complexity**: Medium
**Memory**: Reduced

#### Concept
Compress the index string using LZ4 or Snappy compression.

#### Implementation
```clojure
;; Compress on write
(defn write-metadata [path data]
  (let [compressed-index (compress (:index data))]
    ...))

;; Decompress on read (or keep uncompressed in memory)
(defn read-metadata [path]
  (let [compressed (read-bytes ...)
        index (decompress compressed)]
    ...))
```

#### Pros
- ✅ Reduces I/O overhead
- ✅ Smaller disk footprint
- ✅ Faster flushes

#### Cons
- ⚠️ CPU overhead for compression/decompression
- ⚠️ Need to keep uncompressed version in memory for search

---

### 🗂️ **Option 5: Hybrid Architecture (BEST LONG-TERM)**

**Impact**: 100-200x speedup
**Complexity**: High
**Memory**: Moderate

#### Concept
Keep "hot" data in memory, persist everything to disk.

#### Implementation
```clojure
{:ids {doc-id -> position}       ; in memory
 :pos-idx {position -> doc-id}   ; in memory (Option 1)
 :index "..."                    ; in memory
 :btree btree                    ; on disk (for crash recovery)
 :metadata metadata-file}        ; on disk

;; On open:
(defn open-index [path]
  (let [metadata (read-metadata path)
        btree (open-btree path)]
    ;; Build pos-idx from btree (one-time cost)
    (assoc metadata
      :pos-idx (build-pos-idx btree)
      :btree btree)))
```

#### Pros
- ✅ Near in-memory search performance
- ✅ Full durability and crash recovery
- ✅ Reasonable memory usage
- ✅ Fast reopens (just rebuild pos-idx)

#### Cons
- ⚠️ Slightly higher memory usage
- ⚠️ Need to rebuild pos-idx on open (acceptable)

---

## Performance Projections

| Optimization | Search Speed | Add Speed | Memory | Complexity |
|--------------|-------------|-----------|---------|------------|
| Current      | 1x          | 1x        | 1x      | ✓          |
| Option 1     | **50-100x** | 1x        | 1.2x    | ✓✓         |
| Option 2     | 10-20x      | 5-10x     | 1x      | ✓✓✓        |
| Option 3     | 5-10x       | 1x        | 1x      | ✓✓✓        |
| Option 4     | 2-5x        | 0.8x      | 0.5x    | ✓✓         |
| Option 5     | **100-200x**| 1x        | 1.3x    | ✓✓✓✓       |

---

## Recommended Implementation Plan

### Phase 1: Quick Win (Option 1)
**Goal**: 50-100x search speedup with minimal changes

1. Add `:pos-idx` field to index structure
2. Update `search-add` to maintain `pos-idx`
3. Update `search-remove` to maintain `pos-idx`
4. Update `search-gc` to rebuild `pos-idx`
5. Update `search` to use `pos-idx` instead of B-tree lookups
6. Build `pos-idx` on `open-index` (one-time cost)

**Estimated effort**: 2-3 hours
**Risk**: Low

### Phase 2: Further Optimization (Options 2 + 4)
**Goal**: Additional 5-10x speedup for flushes and large datasets

1. Add memory-mapped file support for B-tree
2. Add compression for index string and metadata
3. Benchmark and tune

**Estimated effort**: 1-2 days
**Risk**: Medium

### Phase 3: Long-term (Option 5)
**Goal**: Production-ready hybrid architecture

1. Refine memory management
2. Add eviction policies for large indexes
3. Optimize for different workload patterns
4. Add monitoring and instrumentation

**Estimated effort**: 1 week
**Risk**: Medium-High

---

## Alternative Approaches (Not Recommended)

### ❌ Switch to LSM Tree
- **Pros**: Better write performance
- **Cons**: Worse read performance, compaction overhead, more complexity
- **Verdict**: Not worth it - our reads dominate

### ❌ Use External Database (SQLite, RocksDB)
- **Pros**: Battle-tested, many features
- **Cons**: Heavy dependencies, less control, harder to embed
- **Verdict**: Against project goals

### ❌ Keep Everything in Memory
- **Pros**: Maximum performance
- **Cons**: Not durable, defeats the purpose
- **Verdict**: Already have in-memory mode

---

## Open Questions

1. **Memory limits**: What's an acceptable memory overhead? 20%? 50%?
2. **Dataset size**: What's the target max dataset size? 100K docs? 1M? 10M?
3. **Workload pattern**: More reads or writes? Read-heavy favors different optimizations.
4. **Reopening frequency**: How often are indexes reopened? Affects amortized cost.

---

## Conclusion

**Recommended**: Start with **Option 1 (In-Memory Position Index)**

- Provides 50-100x search speedup
- Low complexity and risk
- Small memory overhead
- Can be implemented quickly
- Doesn't preclude other optimizations later

This single change would bring durable search performance from 291x slower to ~3-5x slower than in-memory, which is acceptable for most use cases given the durability benefits.
