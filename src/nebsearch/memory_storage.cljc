(ns nebsearch.memory-storage
  "In-memory storage implementation for nebsearch B-tree nodes.

  This is useful for:
  - Testing the pluggable storage API
  - Temporary indexes that don't need persistence
  - As a reference implementation

  Based on the example from tonsky/persistent-sorted-set."
  (:require [nebsearch.storage :as storage]
            [clojure.edn :as edn]))

(defrecord MemoryStorage [*storage     ;; atom: {address -> node-edn-string}
                          *counter     ;; atom: monotonic counter for addresses
                          *root-offset ;; atom: current root address
                          *metadata]   ;; atom: metadata map
  storage/IStorage
  (store [this node]
    "Store a node in memory and return a unique address"
    (let [address (swap! *counter inc)
          ;; Serialize to EDN string (simulates disk serialization)
          node-edn (pr-str (dissoc node :offset :cached))]
      (swap! *storage assoc address node-edn)
      address))

  (restore [this address]
    "Restore a node from memory using its address"
    (if-let [node-edn (get @*storage address)]
      (-> node-edn
          edn/read-string
          (assoc :offset address))
      (throw (ex-info "Node not found" {:address address}))))

  storage/IStorageMetadata
  (store-metadata [this metadata]
    "Store metadata in memory"
    (reset! *metadata metadata)
    metadata)

  (restore-metadata [this]
    "Restore metadata from memory"
    @*metadata)

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
    {:type :memory
     :node-count (count @*storage)
     :root-offset @*root-offset
     :size-bytes (reduce + (map #(count %) (vals @*storage)))}))

(defn create-memory-storage
  "Create a new in-memory storage instance.

  Returns: MemoryStorage instance"
  []
  (->MemoryStorage (atom {})       ;; empty storage
                   (atom 0)        ;; counter starts at 0
                   (atom nil)      ;; no root yet
                   (atom nil)))    ;; no metadata yet

;; Helper functions
(defn get-root-offset
  "Get the current root offset from storage"
  [^MemoryStorage storage]
  @(.-*root-offset storage))

(defn set-root-offset!
  "Set the root offset in storage"
  [^MemoryStorage storage offset]
  (reset! (.-*root-offset storage) offset)
  storage)
