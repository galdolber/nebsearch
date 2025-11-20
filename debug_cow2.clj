(require '[nebsearch.core :as neb])
(require '[nebsearch.btree :as bt])
(import '[java.io RandomAccessFile])

;; Helper to read a node directly from file
(defn read-node-at-offset [file-path offset]
  (let [raf (RandomAccessFile. (clojure.java.io/file file-path) "r")]
    (try
      (.seek raf offset)
      (let [length (.readInt raf)
            node-bytes (byte-array length)]
        (.read raf node-bytes)
        (let [node (read-string (String. node-bytes))]
          node))
      (finally
        (.close raf)))))

;; Create durable index
(def idx (neb/init {:durable? true :index-path "/tmp/cow-test2.dat"}))

;; Add doc1
(def idx1 (neb/search-add idx {"doc1" "version one"}))
(println "\nAfter doc1:")
(println "  idx1 root offset:" (:root-offset (:data idx1)))
(println "  Node at idx1 root:" (read-node-at-offset "/tmp/cow-test2.dat" (:root-offset (:data idx1))))

;; Add doc2
(def idx2 (neb/search-add idx1 {"doc2" "version two"}))
(println "\nAfter doc2:")
(println "  idx2 root offset:" (:root-offset (:data idx2)))
(println "  Node at idx2 root:" (read-node-at-offset "/tmp/cow-test2.dat" (:root-offset (:data idx2))))
(println "  Node at idx1 root (should be unchanged):" (read-node-at-offset "/tmp/cow-test2.dat" (:root-offset (:data idx1))))

;; Add doc3
(def idx3 (neb/search-add idx2 {"doc3" "version three"}))
(println "\nAfter doc3:")
(println "  idx3 root offset:" (:root-offset (:data idx3)))
(println "  Node at idx3 root:" (read-node-at-offset "/tmp/cow-test2.dat" (:root-offset (:data idx3))))
(println "  Node at idx2 root (should be unchanged):" (read-node-at-offset "/tmp/cow-test2.dat" (:root-offset (:data idx2))))
(println "  Node at idx1 root (should be unchanged):" (read-node-at-offset "/tmp/cow-test2.dat" (:root-offset (:data idx1))))

;; Clean up
(neb/close idx3)
(System/exit 0)
