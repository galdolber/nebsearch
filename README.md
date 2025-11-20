# nebsearch

A fast, flexible full-text search library for Clojure with pluggable storage and optional persistence.

## Features

- **Fast in-memory search** - Optimized string matching with position-based indexing
- **Pluggable storage** - Support for memory and disk-backed storage via protocol-based design
- **Lazy loading** - B-tree nodes loaded on-demand for efficient memory usage
- **Copy-on-Write (COW)** - Structural sharing across versions, enabling time travel and efficient updates
- **Explicit persistence** - Control when data is saved to storage
- **Binary search optimization** - O(log n) document lookups where n = number of documents
- **LRU caching** - Automatic caching of search results for performance
- **Unicode support** - Normalized search with diacritic folding

## Quick Start

### In-Memory Index (Default)

```clojure
(require '[nebsearch.core :as neb])

;; Create an empty index
(def idx (neb/init))

;; Add documents [id, searchable-text, display-text]
(def idx2 (neb/search-add idx [["doc1" "hello world" "Hello World"]
                                ["doc2" "clojure programming" "Clojure Guide"]]))

;; Search
(neb/search idx2 "hello")       ;; => #{"doc1"}
(neb/search idx2 "programming") ;; => #{"doc2"}
(neb/search idx2 "hello clojure") ;; => #{} (intersection - no match)

;; Update a document (replaces existing)
(def idx3 (neb/search-add idx2 [["doc1" "goodbye world" "Goodbye"]]))

;; Remove documents
(def idx4 (neb/search-remove idx3 ["doc2"]))
```

### Persistent Storage with Store/Restore

```clojure
(require '[nebsearch.core :as neb]
         '[nebsearch.memory-storage :as mem-storage]
         '[nebsearch.disk-storage :as disk-storage])

;; 1. Create in-memory index as usual
(def idx (neb/search-add (neb/init)
                         [["doc1" "hello world" "Hello"]
                          ["doc2" "clojure rocks" "Clojure"]]))

;; 2. Create storage (memory or disk)
(def storage (mem-storage/create-memory-storage))
;; OR for disk persistence:
;; (def storage (disk-storage/open-disk-storage "index.dat" 128 true))

;; 3. Store the index and get a reference
(def ref (neb/store idx storage))
;; => {:root-offset 1234, :index "...", :ids {...}, :pos-boundaries [...]}

;; 4. Later, restore from the reference (lazy loading)
(def idx-lazy (neb/restore storage ref))

;; 5. Use transparently - works exactly like in-memory
(neb/search idx-lazy "hello") ;; => #{"doc1"}

;; 6. Make changes and store again (structural sharing!)
(def idx2 (neb/search-add idx-lazy [["doc3" "new content" "New"]]))
(def ref2 (neb/store idx2 storage))

;; 7. Clean up when done
(nebsearch.storage/close storage)
```

## API Reference

### Core Functions

#### `init`
```clojure
(init)
```
Create a new empty in-memory search index.

**Returns:** Index map

#### `search-add`
```clojure
(search-add index documents)
```
Add or update documents in the index.

**Parameters:**
- `index` - The search index
- `documents` - Vector of `[id searchable-text display-text]` tuples

**Returns:** New index with documents added

**Example:**
```clojure
(search-add idx [["doc1" "hello world" "Hello World"]
                 ["doc2" "clojure" "Clojure"]])
```

#### `search`
```clojure
(search index query)
(search index query {:keys [limit]})
```
Search for documents matching all words in the query.

**Parameters:**
- `index` - The search index
- `query` - Search string (words are AND'ed together)
- `options` - Optional map with `:limit` key

**Returns:** Set of document IDs

**Example:**
```clojure
(search idx "hello world")           ;; All docs with both "hello" AND "world"
(search idx "test" {:limit 10})      ;; At most 10 results
```

#### `search-remove`
```clojure
(search-remove index ids)
```
Remove documents from the index.

**Parameters:**
- `index` - The search index
- `ids` - Vector or seq of document IDs to remove

**Returns:** New index without the specified documents

#### `store`
```clojure
(store index storage)
```
Store an index to pluggable storage and return a reference.

**Parameters:**
- `index` - The search index (in-memory or lazy)
- `storage` - An IStorage implementation

**Returns:** Reference map with `:root-offset`, `:index`, `:ids`, `:pos-boundaries`

**Note:** This does NOT automatically save to disk. Call `(nebsearch.storage/save storage)` for disk persistence, or it will be saved on the next store operation.

#### `restore`
```clojure
(restore storage reference)
```
Restore an index from storage using a reference (lazy loading).

**Parameters:**
- `storage` - An IStorage implementation (same one used for store)
- `reference` - Reference map returned from `store`

**Returns:** Lazy index that loads B-tree nodes on-demand

#### `close`
```clojure
(close index)
```
Close a lazy index and release storage resources. No-op for in-memory indexes.

#### `search-gc`
```clojure
(search-gc index)
```
Rebuild index to remove fragmentation. **Only works with in-memory indexes.**

For lazy indexes, use store/restore to compact:
```clojure
(def ref2 (neb/store lazy-index storage)) ;; Creates compacted version
```

#### `index-stats`
```clojure
(index-stats index)
```
Get statistics about the index.

**Returns:** Map with:
- `:mode` - `:in-memory` or `:durable`
- `:document-count` - Number of indexed documents
- `:index-size` - Size of index string in bytes
- `:fragmentation` - Fragmentation ratio (0.0 to 1.0)
- `:cache-size` - Number of cached search results
- `:btree-stats` - B-tree statistics (lazy mode only)

### Storage API

#### Memory Storage

```clojure
(require '[nebsearch.memory-storage :as mem])

(def storage (mem/create-memory-storage))
```

Fast in-memory storage for testing or temporary indexes.

#### Disk Storage

```clojure
(require '[nebsearch.disk-storage :as disk])

;; Create new file
(def storage (disk/open-disk-storage "index.dat" 128 true))

;; Open existing file
(def storage (disk/open-disk-storage "index.dat" 128 false))
```

**Parameters:**
- `file-path` - Path to storage file
- `btree-order` - B-tree order (default 128)
- `create?` - Create new file if true, open existing if false

**Features:**
- RandomAccessFile for efficient random access
- CRC32 checksums for data integrity
- Node caching for performance
- Explicit save semantics

#### Storage Protocols

All storage implementations must satisfy:

```clojure
;; Store and restore B-tree nodes
storage/IStorage
  (store [this node])     ;; Returns address
  (restore [this address]) ;; Returns node

;; Manage root offset
storage/IStorageRoot
  (set-root-offset [this offset])
  (get-root-offset [this])

;; Explicit save
storage/IStorageSave
  (save [this]) ;; Flush all pending changes

;; Resource management
storage/IStorageClose
  (close [this])

;; Statistics
storage/IStorageStats
  (storage-stats [this])
```

## Advanced Usage

### Time Travel with Multiple Versions

```clojure
(def storage (mem-storage/create-memory-storage))

;; Create version 1
(def idx1 (neb/search-add (neb/init) [["doc1" "version one" "V1"]]))
(def ref1 (neb/store idx1 storage))

;; Create version 2 (adds doc2)
(def idx2 (neb/search-add (neb/restore storage ref1)
                          [["doc2" "version two" "V2"]]))
(def ref2 (neb/store idx2 storage))

;; Create version 3 (adds doc3)
(def idx3 (neb/search-add (neb/restore storage ref2)
                          [["doc3" "version three" "V3"]]))
(def ref3 (neb/store idx3 storage))

;; Access any version at any time
(neb/search (neb/restore storage ref1) "version") ;; => #{"doc1"}
(neb/search (neb/restore storage ref2) "version") ;; => #{"doc1" "doc2"}
(neb/search (neb/restore storage ref3) "version") ;; => #{"doc1" "doc2" "doc3"}
```

### Structural Sharing (Copy-on-Write)

Changes share structure with previous versions, so only modified nodes are written:

```clojure
(def storage (mem-storage/create-memory-storage))
(def idx1 (neb/search-add (neb/init) [["doc1" "test" "Test"]]))
(def ref1 (neb/store idx1 storage))

;; Add one more document
(def idx2 (neb/search-add idx1 [["doc2" "more" "More"]]))
(def ref2 (neb/store idx2 storage))

;; Storage stats show structural sharing
(nebsearch.storage/storage-stats storage)
;; => {:type :memory, :node-count N, ...}
;; Most nodes are shared between ref1 and ref2!
```

### Hybrid Workflow (In-Memory + Lazy)

```clojure
;; Start with in-memory for fast development
(def idx (neb/search-add (neb/init) [["doc1" "data" "Data"]]))

;; Store checkpoint when needed
(def storage (mem-storage/create-memory-storage))
(def checkpoint (neb/store idx storage))

;; Continue working in-memory
(def idx2 (neb/search-add idx [["doc2" "more" "More"]]))

;; Or restore and work with lazy version
(def idx-lazy (neb/restore storage checkpoint))
(def idx-lazy2 (neb/search-add idx-lazy [["doc3" "lazy" "Lazy"]]))

;; Store again for another checkpoint
(def checkpoint2 (neb/store idx-lazy2 storage))
```

### Large Datasets with Lazy Loading

```clojure
(def storage (disk-storage/open-disk-storage "large.dat" 128 true))

;; Create large index (10,000 documents)
(def large-idx
  (reduce (fn [idx i]
            (neb/search-add idx [[(str "doc" i)
                                  (str "content " i)
                                  (str "Document " i)]]))
          (neb/init)
          (range 10000)))

;; Store to disk
(def ref (neb/store large-idx storage))

;; Restore lazily - only loads nodes as needed
(def idx-lazy (neb/restore storage ref))

;; Search only loads necessary B-tree nodes!
(neb/search idx-lazy "content 5000") ;; Fast - loads only needed nodes

(nebsearch.storage/close storage)
```

## Performance Characteristics

### In-Memory Mode

- **Search:** O(n × m) where n = query words, m = index string length
- **Insert:** O(log k) where k = number of entries
- **Delete:** O(log k)
- **Memory:** O(k) - all data in RAM
- **Best for:** Fast searches, volatile data, development

### Lazy Mode (with Storage)

- **Search:** O(n × (m + log k × I/O))
- **Insert:** O(log k × I/O) - B-tree operations with disk writes
- **Delete:** O(log k × I/O)
- **Memory:** O(cache size) - only cached nodes in RAM
- **Disk:** O(k × versions) - nodes shared via COW
- **Best for:** Large datasets, persistence, multiple versions

### Optimizations

- **Binary search position index** - O(log n) document lookups
- **LRU cache** - Configurable via `*cache-size*` (default 1000)
- **Auto-GC** - Triggers at `*auto-gc-threshold*` (default 0.3 = 30%)
- **Batch optimization** - Uses StringBuilder for batches > `*batch-threshold*` (default 100)
- **Node caching** - Storage implementations cache restored nodes

## Configuration

Dynamic vars for tuning performance:

```clojure
(binding [neb/*cache-size* 2000        ;; Max LRU cache entries
          neb/*auto-gc-threshold* 0.5   ;; Auto-GC at 50% fragmentation
          neb/*batch-threshold* 200]    ;; StringBuilder for batches > 200
  ;; Your code here
  )
```

## Examples

See the `examples/` directory for comprehensive examples:

- **`store_restore_example.clj`** - Complete guide to store/restore API with:
  - Basic store and restore
  - Structural sharing
  - Time travel with multiple versions
  - Disk persistence
  - Hybrid workflows
  - Large dataset lazy loading

- **`pluggable_storage_example.clj`** - Lower-level storage API examples

## Testing

Run tests with Clojure CLI:

```bash
clojure -X:test
```

Or with Leiningen:

```bash
lein test
```

Tests include:
- `core_test.clj` - In-memory index operations
- `storage_test.clj` - Store/restore API, memory storage, disk storage, COW semantics

## Architecture

### Storage Abstraction

```
┌─────────────────────────────────────────┐
│         nebsearch.core                  │
│  (search-add, search, store, restore)   │
└──────────────┬──────────────────────────┘
               │
               ├─► In-Memory: persistent-sorted-set
               │
               └─► Lazy: nebsearch.btree (DurableBTree)
                          │
                          ▼
                   ┌──────────────┐
                   │   IStorage   │ Protocol
                   └──────┬───────┘
                          │
                ┌─────────┴─────────┐
                │                   │
         MemoryStorage      DiskStorage
         (testing)          (persistence)
```

### File Format (Disk Storage)

```
[Header - 256 bytes]
  - Magic: "NEBSRCH\0"
  - Version: 1
  - Root offset
  - Node count
  - B-tree order

[Nodes - variable size]
  Each node:
    - Length (4 bytes)
    - EDN data (variable)
    - CRC32 checksum (4 bytes)
```

## Upgrading from Old API

The old durable mode API (`init {:durable? true}`) has been removed. Use the new store/restore API:

**Old:**
```clojure
(def idx (neb/init {:durable? true :index-path "index.dat"}))
(def idx2 (neb/search-add idx docs))
(neb/flush idx2)
(neb/close idx2)
```

**New:**
```clojure
(def storage (disk-storage/open-disk-storage "index.dat" 128 true))
(def idx (neb/init))
(def idx2 (neb/search-add idx docs))
(def ref (neb/store idx2 storage))
(nebsearch.storage/close storage)
```

## Requirements

- Clojure 1.10+
- Java 8+

## Dependencies

- `me.tonsky/persistent-sorted-set` - For in-memory sorted sets

## License

See LICENSE file for details.

## Contributing

Contributions welcome! Please:
1. Add tests for new functionality
2. Ensure all tests pass
3. Follow existing code style
4. Update documentation

## Credits

- Storage abstraction inspired by [tonsky/persistent-sorted-set](https://github.com/tonsky/persistent-sorted-set)
- B-tree implementation based on standard COW B-tree algorithms
