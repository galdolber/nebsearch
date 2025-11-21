(ns nebsearch.memory-storage
  "In-memory storage implementation for nebsearch B-tree nodes.

  This is useful for:
  - Testing the pluggable storage API
  - Temporary indexes that don't need persistence
  - As a reference implementation

  Based on the example from tonsky/persistent-sorted-set."
  (:require [nebsearch.storage :as storage]))

(defrecord MemoryStorage [*storage     ;; atom: {address -> node}
                          *counter     ;; atom: monotonic counter for addresses
                          *root-offset] ;; atom: current root address
  storage/IStorage
  (store [this node]
    "Store a node in memory and return a unique address"
    (let [address (swap! *counter inc)
          ;; Store node directly - deftypes don't need dissoc, maps do
          clean-node (if (map? node)
                      (dissoc node :offset :cached :storage :root-offset)
                      node)] ; deftypes are already clean
      (swap! *storage assoc address clean-node)
      address))

  (restore [this address]
    "Restore a node from memory using its address"
    (if-let [node (get @*storage address)]
      ;; Deftype nodes store offset in metadata, maps use assoc
      (if (map? node)
        (assoc node :offset address)
        (with-meta node (assoc (meta node) :offset address)))
      (throw (ex-info "Node not found" {:address address}))))

  storage/IStorageRoot
  (set-root-offset [this offset]
    "Set the root offset in storage"
    (reset! *root-offset offset)
    this)

  (get-root-offset [this]
    "Get the current root offset from storage"
    @*root-offset)

  storage/IStorageSave
  (save [this]
    "No-op for memory storage (already 'saved')"
    this)

  storage/IStorageClose
  (close [this]
    "No-op for memory storage (nothing to close)"
    this)

  storage/IStorageStats
  (storage-stats [this]
    "Get storage statistics"
    (let [node-count (count @*storage)]
      {:type :memory
       :node-count node-count
       :root-offset @*root-offset
       ;; Approximate size (nodes * avg size) - not exact but reasonable for stats
       :size-bytes (* node-count 100)}))

  storage/IStorageInvertedStrategy
  (precompute-inverted? [this]
    "Memory storage uses lazy inverted index (build on first search)"
    false))

(defn create-memory-storage
  "Create a new in-memory storage instance.

  Returns: MemoryStorage instance"
  []
  (->MemoryStorage (atom {})       ;; empty storage
                   (atom 0)        ;; counter starts at 0
                   (atom nil)))    ;; no root yet
