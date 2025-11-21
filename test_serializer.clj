(ns test-serializer
  (:require [nebsearch.core :as neb]
            [nebsearch.disk-storage :as disk-storage]
            [nebsearch.storage :as storage]
            [clojure.java.io :as io]))

(defn -main [& args]
  (println "\n═══════════════════════════════════════════════════════════════")
  (println "           SERIALIZER TEST")
  (println "═══════════════════════════════════════════════════════════════\n")

  (try
    (let [index-file "/tmp/serializer-test.dat"

          ;; Clean up old file
          _ (when (.exists (io/file index-file))
              (.delete (io/file index-file)))

          ;; Create a simple index with some documents
          _ (println "Creating test index...")
          docs [["doc1" "hello world" "Title 1"]
                ["doc2" "world peace" "Title 2"]
                ["doc3" "hello there" "Title 3"]]

          ;; Build index in memory
          _ (println "Building index...")
          idx (neb/search-add (neb/init) docs)

          ;; Save to disk
          _ (println "Saving to disk...")
          storage (disk-storage/open-disk-storage index-file 128 true)
          ref (neb/store idx storage)

          ;; Restore from disk
          _ (println "Restoring from disk...")
          restored-idx (neb/restore storage ref)

          ;; Test searches
          _ (println "\nTesting searches...")
          results1 (neb/search restored-idx "hello")
          results2 (neb/search restored-idx "world")

          _ (println (format "  'hello' -> %d results: %s"
                           (count results1)
                           (vec (map first results1))))
          _ (println (format "  'world' -> %d results: %s"
                           (count results2)
                           (vec (map first results2))))

          ;; Cleanup
          _ (storage/close storage)

          ;; Verify results
          _ (if (and (= 2 (count results1))
                    (= 2 (count results2)))
              (println "\n✓ Serializer test PASSED!")
              (do
                (println "\n✗ Serializer test FAILED!")
                (System/exit 1)))]

      (println "═══════════════════════════════════════════════════════════════\n"))

    (catch Exception e
      (println "\nERROR:" (.getMessage e))
      (.printStackTrace e)
      (System/exit 1))))

(apply -main *command-line-args*)
