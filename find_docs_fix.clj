;; FIXED VERSION - using persistent sets instead of nested transient reduces
           ;; OPTIMIZED PATH 2: No exact match - use word cache for substring search
           ;; Build word cache once if not yet populated
           (let [word-cache (or @word-cache-atom
                               (let [cache (build-word-cache inverted)]
                                 (reset! word-cache-atom cache)
                                 cache))
                 ;; Find all words that contain the search term as substring
                 ;; This scans ~100K unique words instead of ~5M inverted entries!
                 matching-words (filter #(string/includes? % word) word-cache)]
             ;; For each matching word, use hash-based range query to get doc-ids
             ;; Use into with sets for proper accumulation
             (set
              (mapcat (fn [w]
                       (let [w-hash (unchecked-long (.hashCode ^String w))
                             w-matches (bt/bt-range inverted w-hash w-hash)]
                         ;; Get all doc-ids for this word
                         (map (fn [entry]
                               (let [^InvertedEntry e entry]
                                 (.-doc-id e)))
                             w-matches)))
                     matching-words)))))
