(ns nebsearch.storage
  "Pluggable storage interface for nebsearch B-tree nodes.

  This allows the B-tree to be backed by different storage implementations:
  - Disk/file storage (default)
  - In-memory storage (for testing)
  - Database storage
  - Cloud storage
  - etc.

  Based on the IStorage interface from tonsky/persistent-sorted-set.")

;; Storage Protocol
;; Based on persistent-sorted-set's IStorage interface
(defprotocol IStorage
  "Protocol for storing and restoring B-tree nodes.

  Implementations must handle:
  - store: Persist a node and return a unique address
  - restore: Load a node from an address

  Nodes are maps with the following structure:
  - Leaf nodes: {:type :leaf, :entries [[pos id text]...], :next-leaf offset}
  - Internal nodes: {:type :internal, :keys [separators], :children [addresses]}

  Implementations should cache nodes for performance."

  (store [this node]
    "Store a node and return an address that can be used to restore it.

    Parameters:
    - node: A map representing either a leaf or internal node

    Returns:
    - address: A unique identifier (can be any type: long, uuid, string, etc.)")

  (restore [this address]
    "Restore a node from storage using its address.

    Parameters:
    - address: The unique identifier returned by store

    Returns:
    - node: The restored node map

    Note: Implementations should cache restored nodes for performance."))

(defprotocol IStorageMetadata
  "Optional protocol for storage implementations that need to persist metadata.

  This handles:
  - Index string (concatenated normalized search tokens)
  - IDs map (id -> position lookup)
  - Version information"

  (store-metadata [this metadata]
    "Store metadata (index string, ids map, version info).

    Parameters:
    - metadata: {:index string, :ids map, :version number, :timestamp millis}")

  (restore-metadata [this]
    "Restore metadata from storage.

    Returns:
    - metadata map or nil if not found"))

(defprotocol IStorageSave
  "Protocol for explicit save operations.

  Storage implementations that support explicit saves (not automatic)
  should implement this protocol. This allows COW semantics where
  changes are only made durable on explicit save."

  (save [this]
    "Explicitly save all pending changes to durable storage.

    This should:
    1. Flush any buffers
    2. Update root pointers atomically
    3. Sync to disk (if applicable)

    Returns the storage instance."))

(defprotocol IStorageClose
  "Protocol for closing/cleaning up storage resources."

  (close [this]
    "Close the storage and release any resources (file handles, connections, etc.)"))

(defprotocol IStorageStats
  "Protocol for getting storage statistics."

  (storage-stats [this]
    "Get statistics about the storage.

    Returns a map with implementation-specific stats, e.g.:
    - :type - storage type keyword
    - :node-count - number of nodes stored
    - :cache-size - number of cached nodes
    - :size - total size in bytes (if applicable)"))
