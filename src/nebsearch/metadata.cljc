(ns nebsearch.metadata
  "Metadata persistence for durable search indexes.

  Handles persisting and loading:
  - Index string (the concatenated normalized search tokens)
  - IDs map (id -> position lookup)
  - Version history and snapshots"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  #?(:clj (:import [java.io File RandomAccessFile]
                   [java.nio.file Files StandardCopyOption]
                   [java.nio.charset StandardCharsets])))

#?(:clj
   (do
     (defn metadata-path [index-path]
       "Get the metadata file path from the main index path"
       (str index-path ".meta"))

     (defn version-log-path [index-path]
       "Get the version log file path"
       (str index-path ".versions"))

     (defn write-metadata
       "Write index metadata to disk.

       Metadata includes:
       - :index - the concatenated search string
       - :ids - the id -> position map
       - :version - monotonic version number
       - :timestamp - when this version was created"
       [index-path {:keys [index ids version timestamp]}]
       (let [meta-path (metadata-path index-path)
             temp-path (str meta-path ".tmp")
             metadata {:index index
                       :ids ids
                       :version (or version 0)
                       :timestamp (or timestamp (System/currentTimeMillis))}]
         ;; Write to temp file first (atomic write pattern)
         (spit temp-path (pr-str metadata))
         ;; Atomic rename
         (Files/move (.toPath (io/file temp-path))
                     (.toPath (io/file meta-path))
                     (into-array [StandardCopyOption/REPLACE_EXISTING
                                  StandardCopyOption/ATOMIC_MOVE]))
         metadata))

     (defn read-metadata
       "Read index metadata from disk.

       Returns nil if metadata file doesn't exist."
       [index-path]
       (let [meta-path (metadata-path index-path)
             meta-file (io/file meta-path)]
         (when (.exists meta-file)
           (edn/read-string (slurp meta-path)))))

     (defn append-version
       "Append a version entry to the version log.

       Version entries track:
       - :version - version number
       - :timestamp - when created
       - :root-offset - B-tree root offset for this version
       - :snapshot-name - optional name for this snapshot
       - :parent-version - previous version number"
       [index-path {:keys [version timestamp root-offset snapshot-name parent-version]}]
       (let [log-path (version-log-path index-path)
             entry {:version version
                    :timestamp timestamp
                    :root-offset root-offset
                    :snapshot-name snapshot-name
                    :parent-version parent-version}]
         ;; Append to log file
         (spit log-path
               (str (pr-str entry) "\n")
               :append true)
         entry))

     (defn read-version-log
       "Read all version entries from the version log.

       Returns a vector of version entries sorted by version number."
       [index-path]
       (let [log-path (version-log-path index-path)
             log-file (io/file log-path)]
         (if (.exists log-file)
           (vec (map edn/read-string
                     (filter seq (clojure.string/split-lines (slurp log-path)))))
           [])))

     (defn get-version
       "Get a specific version entry from the log.

       Can lookup by:
       - Version number: (get-version path {:version 42})
       - Snapshot name: (get-version path {:snapshot-name \"checkpoint-1\"})"
       [index-path query]
       (let [versions (read-version-log index-path)]
         (cond
           (:version query)
           (first (filter #(= (:version %) (:version query)) versions))

           (:snapshot-name query)
           (first (filter #(= (:snapshot-name %) (:snapshot-name query)) versions))

           :else
           (last versions)))) ;; Latest version

     (defn list-snapshots
       "List all named snapshots in the version log."
       [index-path]
       (let [versions (read-version-log index-path)]
         (filter :snapshot-name versions)))

     (defn next-version-number
       "Get the next version number."
       [index-path]
       (let [versions (read-version-log index-path)]
         (if (seq versions)
           (inc (apply max (map :version versions)))
           0)))

     (defn gc-old-versions
       "Garbage collect old versions, keeping only specified ones.

       keep-versions: set of version numbers to keep
       Returns: set of root-offsets that should be kept in the B-tree"
       [index-path keep-versions]
       (let [all-versions (read-version-log index-path)
             keep-set (set keep-versions)
             kept-versions (filter #(keep-set (:version %)) all-versions)
             temp-path (str (version-log-path index-path) ".tmp")]
         ;; Write new log with only kept versions
         (spit temp-path
               (clojure.string/join "\n"
                                    (map pr-str kept-versions)))
         ;; Atomic rename
         (Files/move (.toPath (io/file temp-path))
                     (.toPath (io/file (version-log-path index-path)))
                     (into-array [StandardCopyOption/REPLACE_EXISTING
                                  StandardCopyOption/ATOMIC_MOVE]))
         ;; Return set of root offsets to keep
         (set (map :root-offset kept-versions))))

     (defn initialize-metadata
       "Initialize metadata and version log for a new index."
       [index-path]
       (write-metadata index-path {:index ""
                                    :ids {}
                                    :version 0
                                    :timestamp (System/currentTimeMillis)})
       (spit (version-log-path index-path) "")
       {:index "" :ids {} :version 0})))

;; ClojureScript stubs
#?(:cljs
   (do
     (defn metadata-path [index-path] nil)
     (defn version-log-path [index-path] nil)
     (defn write-metadata [index-path meta] (throw (ex-info "Not supported" {})))
     (defn read-metadata [index-path] nil)
     (defn append-version [index-path entry] (throw (ex-info "Not supported" {})))
     (defn read-version-log [index-path] [])
     (defn get-version [index-path query] nil)
     (defn list-snapshots [index-path] [])
     (defn next-version-number [index-path] 0)
     (defn gc-old-versions [index-path keep-versions] #{})
     (defn initialize-metadata [index-path] nil)))
