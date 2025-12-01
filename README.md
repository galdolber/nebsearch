# nebsearch

A high-performance, persistent full-text search library for Clojure/ClojureScript with custom B-tree indexing, inverted index, and optional disk persistence.

## Features

- **Inverted Index** - Fast O(log n) word lookups via B-tree-based inverted index
- **Custom B-tree** - Order-128 B-tree with Copy-on-Write semantics and structural sharing
- **Bulk Insert** - Optimized bottom-up construction for large batches (5-10x faster)
- **LZ4 Compression** - 2-2.5x space savings with minimal CPU overhead
- **Batched I/O** - Strategic batching and fsync for 2-4x faster disk writes
- **LRU Caching** - Configurable search result caching
- **Lazy Loading** - B-tree nodes loaded on-demand from storage
- **Pluggable Storage** - Memory and disk storage via protocol-based design
- **Time Travel** - Multiple index versions with structural sharing
- **Unicode Normalized** - Case-insensitive search with diacritic folding
- **Binary Search** - O(log n) document lookups via position boundaries

## Quick Start

### In-Memory Index

```clojure
(require '[nebsearch.core :as neb])

;; Create empty index
(def idx (neb/init))

;; Add documents [id searchable-text display-text]
(def idx2 (neb/search-add idx [["doc1" "hello world" "Hello World"]
                                ["doc2" "clojure programming" "Clojure Guide"]]))

;; Search (substring matching, case-insensitive)
(neb/search idx2 "hello")       ;; => #{"doc1"}
(neb/search idx2 "prog")        ;; => #{"doc2"} (substring match)
(neb/search idx2 "hello clojure") ;; => #{} (AND query - no match)

;; Update document (replaces existing)
(def idx3 (neb/search-add idx2 [["doc1" "goodbye world" "Goodbye"]]))

;; Remove documents
(def idx4 (neb/search-remove idx3 ["doc2"]))

;; Limit results
(neb/search idx2 "hello" {:limit 10})
```

### Disk Persistence

```clojure
(require '[nebsearch.disk-storage :as disk])

;; Create disk storage
(def storage (disk/open-disk-storage "index.dat" 128 true))

;; Build index
(def idx (neb/search-add (neb/init)
                         [["doc1" "hello world" "Hello"]
                          ["doc2" "clojure rocks" "Clojure"]]))

;; Store to disk (returns reference)
(def ref (neb/store idx storage))

;; Restore from disk (lazy loading)
(def idx-lazy (neb/restore storage ref))

;; Search works transparently
(neb/search idx-lazy "hello") ;; => #{"doc1"}

;; Close when done
(nebsearch.storage/close storage)
```

### Bulk Operations

```clojure
;; Bulk insert (≥50 docs automatically uses optimized bulk insert)
(def large-docs
  (mapv (fn [i] [(str "doc" i) (str "content " i) (str "Title " i)])
        (range 10000)))

(time (def idx (neb/search-add (neb/init) large-docs)))
;; Uses bulk insert automatically - 5-10x faster than incremental
```

## API Reference

### Core Functions

#### `init`
```clojure
(init) => index
```
Create new empty in-memory index.

#### `search-add`
```clojure
(search-add index documents) => new-index
```
Add or update documents. Uses bulk insert optimization for ≥50 documents.

**Parameters:**
- `index` - Search index
- `documents` - Vector of `[id searchable-text display-text]`

**Behavior:**
- `< 50 docs`: Incremental insert
- `≥ 50 docs`: Bulk insert (5-10x faster)
- Invalidates search cache
- Updates inverted index

#### `search`
```clojure
(search index query) => #{doc-ids}
(search index query {:keys [limit]}) => #{doc-ids}
```
Full-text search with LRU caching.

**Query behavior:**
- Multiple words = AND (intersection)
- Substring matching: "prog" matches "programming"
- Case-insensitive
- Accent normalized: "café" matches "cafe"
- Splits on non-alphanumeric

**Example:**
```clojure
(search idx "hello world")           ;; Both words required
(search idx "test" {:limit 10})      ;; Max 10 results
```

#### `search-remove`
```clojure
(search-remove index ids) => new-index
```
Remove documents by ID. Invalidates cache.

#### `search-gc`
```clojure
(search-gc index) => new-index
```
Rebuild in-memory index to remove fragmentation. Only works for in-memory indexes.

For lazy indexes, use store/restore cycle:
```clojure
(def ref2 (neb/store lazy-index storage)) ;; Compacts automatically
```

#### `store`
```clojure
(store index storage) => reference
```
Store index to storage and return reference.

**Returns:** Map with:
- `:root-offset` - B-tree root address
- `:index` - Legacy index string (may be empty)
- `:ids` - Document ID map
- `:pos-boundaries` - Position boundaries for binary search
- `:inverted-root-offset` - Inverted index B-tree root

**Note:** For disk storage, call `(nebsearch.storage/save storage)` to flush, or it will be flushed on next store operation.

#### `restore`
```clojure
(restore storage reference) => lazy-index
```
Restore index from storage reference. B-tree nodes loaded on-demand.

#### `index-stats`
```clojure
(index-stats index) => stats-map
```
Get index statistics.

**Returns:**
- `:mode` - `:in-memory` or `:durable`
- `:document-count` - Number of documents
- `:index-size` - Index string bytes
- `:fragmentation` - Ratio (0.0-1.0)
- `:cache-size` - Cached search results
- `:btree-stats` - B-tree stats (lazy mode)

### Storage API

#### Memory Storage

```clojure
(require '[nebsearch.memory-storage :as mem])

(def storage (mem/create-memory-storage))
```

**Features:**
- Atom-based in-memory storage
- Lazy inverted index (built on first search)
- Fast for testing and development

#### Disk Storage

```clojure
(require '[nebsearch.disk-storage :as disk])

;; Create new file
(def storage (disk/open-disk-storage "index.dat" 128 true))

;; Open existing file
(def storage (disk/open-disk-storage "index.dat" 128 false))
```

**Parameters:**
- `file-path` - Storage file path
- `btree-order` - B-tree order (default 128, max 256)
- `create?` - Create new file if true

**Features:**
- RandomAccessFile-based persistence
- Custom binary serialization (not EDN)
- CRC32 checksums for integrity
- LZ4 compression (2-2.5x savings)
- Batched I/O with strategic fsync
- Pre-computed inverted index
- 256-byte header with magic "NEBSRCH\0"

#### Storage Protocols

```clojure
;; Core storage
nebsearch.storage/IStorage
  (store [this node])      ;; Returns address
  (restore [this address])  ;; Returns node

;; Root management
nebsearch.storage/IStorageRoot
  (set-root-offset [this offset])
  (get-root-offset [this])

;; Explicit save
nebsearch.storage/IStorageSave
  (save [this])  ;; Flush pending changes

;; Resource management
nebsearch.storage/IStorageClose
  (close [this])

;; Statistics
nebsearch.storage/IStorageStats
  (storage-stats [this])

;; Inverted index strategy
nebsearch.storage/IStorageInvertedStrategy
  (inverted-strategy [this])  ;; :lazy or :precomputed

;; Batched I/O
nebsearch.storage/IBatchedStorage
  (begin-batch [this])
  (end-batch [this])
```

## Advanced Usage

### Time Travel with Multiple Versions

```clojure
(def storage (mem/create-memory-storage))

;; Version 1
(def idx1 (neb/search-add (neb/init) [["doc1" "version one" "V1"]]))
(def ref1 (neb/store idx1 storage))

;; Version 2 (shares structure with v1)
(def idx2 (neb/search-add (neb/restore storage ref1)
                          [["doc2" "version two" "V2"]]))
(def ref2 (neb/store idx2 storage))

;; Access any version
(neb/search (neb/restore storage ref1) "version") ;; => #{"doc1"}
(neb/search (neb/restore storage ref2) "version") ;; => #{"doc1" "doc2"}
```

### Large Datasets with Lazy Loading

```clojure
(def storage (disk/open-disk-storage "large.dat" 128 true))

;; Create large index
(def large-idx
  (neb/search-add (neb/init)
                  (mapv (fn [i] [(str "doc" i) (str "content " i) (str "Title " i)])
                        (range 100000))))

;; Store to disk
(def ref (neb/store large-idx storage))
(nebsearch.storage/save storage) ;; Flush to disk

;; Restore lazily - only loads needed nodes
(def idx-lazy (neb/restore storage ref))

;; Search loads only necessary B-tree nodes
(neb/search idx-lazy "content 5000") ;; Fast!

(nebsearch.storage/close storage)
```

## Configuration

Dynamic vars for performance tuning:

```clojure
(require '[nebsearch.core :as neb])

;; Individual settings
(binding [neb/*cache-size* 2000              ;; LRU cache entries (default 1000)
          neb/*bulk-insert-threshold* 100    ;; Bulk insert for ≥N docs (default 50)
          neb/*batch-threshold* 200          ;; StringBuilder for >N docs (default 100)
          neb/*storage-cache-size* 512]      ;; Storage node cache (default 256)
  ;; Your code here
  )

;; Or use presets
(neb/with-config :large  ;; :small, :medium, :large
  ;; Your code here
  )
```

**Presets:**
- `:small` - 500 cache, 25 bulk threshold
- `:medium` - 1000 cache (default), 50 bulk threshold
- `:large` - 2000 cache, 100 bulk threshold

**Note:** `*auto-gc-threshold*` is deprecated (set to 1.0 to disable auto-GC for COW semantics).

## Architecture

### Three-Layer Design

```
┌─────────────────────────────────────────┐
│         Search API Layer                │
│  (init, search-add, search, remove)     │
│  - Query processing                     │
│  - LRU caching                          │
│  - Batch optimization                   │
└─────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────┐
│      Data Structure Layer               │
│  - B-Tree (pos → doc)                   │
│  - Inverted Index (word-hash → doc-id)  │
│  - Position Boundaries (binary search)  │
│  - Index String (legacy fallback)       │
└─────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────┐
│       Storage Layer                     │
│  - Memory Storage (atoms)               │
│  - Disk Storage (RAF + LZ4)             │
│  - Pluggable via IStorage protocol      │
└─────────────────────────────────────────┘
```

### B-Tree Implementation

- **Order:** 128 (internal nodes), 256 entries per leaf
- **Structure:** InternalNode (keys + child offsets), LeafNode (entries + next pointer)
- **COW:** All modifications create new versions with structural sharing
- **Bulk Insert:** Bottom-up construction for ≥50 documents
- **Lazy Loading:** Nodes loaded on-demand from storage

### Inverted Index

Two strategies based on storage type:

**Lazy (Memory Storage):**
- Atom with map: `{word → #{doc-ids}}`
- Built on first search for each word
- Cached for subsequent searches

**Pre-computed (Disk Storage):**
- Separate B-tree: `[word-hash word doc-id]`
- Built during search-add
- Always consistent
- Faster searches, slower writes

### File Format (Disk Storage)

```
[Header - 256 bytes]
  Magic: "NEBSRCH\0"
  Version: 1
  Root offset: 8 bytes
  Node count: 8 bytes
  B-tree order: 4 bytes

[Nodes - variable size]
  [flags-byte]                    ;; bit 0: compressed, bits 1-7: node type
  [original-size]                 ;; 4 bytes (if compressed)
  [compressed-or-raw-data]        ;; LZ4 or raw
  [CRC32 checksum]                ;; 4 bytes

Node types:
  Type 0: InternalNode (long keys + child offsets)
  Type 1: LeafNode (entries + next-leaf pointer)

Entry types:
  Type 0: DocumentEntry [pos id text]
  Type 1: InvertedEntry [word-hash word doc-id]
```

### Compression

- **Algorithm:** LZ4 (fast compression/decompression)
- **Threshold:** Only compresses nodes >128 bytes
- **Savings:** 2-2.5x typical compression ratio
- **Conditional:** Only compresses if >10% savings

## Performance Characteristics

### Search
- **Inverted Index Lookup:** O(log n) per word via B-tree
- **Multi-word AND:** O(k × log n) where k = word count
- **Document Retrieval:** O(log d) via binary search on position boundaries
- **Cache Hits:** O(1)

### Insert
- **Incremental:** O(log n) per document
- **Bulk (≥50 docs):** O(n log n) amortized - 5-10x faster
- **Inverted Index Update:** O(w × log n) where w = unique words

### Storage
- **Memory:** O(total documents)
- **Disk:** O(cache size) - lazy loading
- **Structural Sharing:** Multiple versions share unchanged nodes

### Benchmarks

Typical performance (memory storage):
- **Search:** 20,000-50,000 queries/sec (10K docs)
- **Bulk Insert:** 5,000-10,000 docs/sec (memory)
- **Bulk Insert:** 1,000-3,000 docs/sec (disk)
- **Cache Hits:** Near-instant

## Testing

```bash
# Run all tests
clj -M:test -m cognitect.test-runner

# Or with script
clj run_tests.clj
```

## Benchmarks

```bash
# Search performance
clj benchmark_search.clj

# Bulk insert performance
clj benchmark_bulk_insert.clj

# Comprehensive suite
clj benchmark_comprehensive.clj
```

## Requirements

- Clojure 1.11+
- Java 8+

## Dependencies

From `deps.edn`:
```clojure
{org.clojure/clojure {:mvn/version "1.11.1"}
 org.lz4/lz4-java {:mvn/version "1.8.0"}}
```

## Upgrading from Old API

The old durable mode API has been removed. Use store/restore:

**Old:**
```clojure
(def idx (neb/init {:durable? true :index-path "index.dat"}))
```

**New:**
```clojure
(def storage (disk/open-disk-storage "index.dat" 128 true))
(def idx (neb/init))
;; ... build index ...
(def ref (neb/store idx storage))
```

## License

See LICENSE file for details.

## Contributing

Contributions welcome! Please:
1. Add tests for new functionality
2. Ensure all tests pass
3. Follow existing code style
4. Update documentation as needed

## Implementation Notes

- **B-tree:** Custom deftype-based implementation with COW semantics
- **Serialization:** Hand-optimized binary format (not EDN) for performance
- **Word Normalization:** Lowercase + NFD decomposition + ASCII folding
- **Position-Based Indexing:** Documents stored at positions in conceptual index string
- **Batched I/O:** Strategic batching and fsync for 2-4x faster disk writes
- **Deftype Nodes:** Zero-overhead field access for B-tree nodes
- **Array Operations:** Direct array slicing instead of sequence operations
