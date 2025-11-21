(ns nebsearch.entries
  "Optimized deftype structures for B-tree entries.

  Using deftypes instead of vector tuples provides:
  - Faster field access (direct vs nth)
  - Reduced allocation overhead (no vector wrapper)
  - Primitive long fields without boxing
  - Better JIT optimization")

#?(:clj
   (do
     (deftype DocumentEntry [^long pos id text]
       Object
       (equals [this other]
         (and (instance? DocumentEntry other)
              (let [^DocumentEntry o other]
                (and (= pos (.-pos o))
                     (= id (.-id o))
                     (= text (.-text o))))))

       (hashCode [this]
         (unchecked-int
          (+ (unchecked-int pos)
             (* 31 (.hashCode ^Object id))
             (if text (* 961 (.hashCode ^Object text)) 0))))

       (toString [this]
         (if text
           (str "[" pos " " id " " text "]")
           (str "[" pos " " id "]")))

       Comparable
       (compareTo [this other]
         (let [^DocumentEntry o other
               pos-cmp (Long/compare pos (.-pos o))]
           (if (zero? pos-cmp)
             (compare id (.-id o))
             pos-cmp)))

       clojure.lang.ILookup
       (valAt [this k]
         (case k
           0 pos
           1 id
           2 text
           nil))
       (valAt [this k not-found]
         (case k
           0 pos
           1 id
           2 text
           not-found))

       clojure.lang.Indexed
       (nth [this i]
         (case i
           0 pos
           1 id
           2 text
           (throw (IndexOutOfBoundsException.))))
       (nth [this i not-found]
         (case i
           0 pos
           1 id
           2 (or text not-found)
           not-found))

       clojure.lang.Counted
       (count [this]
         (if text 3 2))

       clojure.lang.Seqable
       (seq [this]
         (if text
           (list pos id text)
           (list pos id))))

     ;; Custom print method for EDN serialization (MemoryStorage compatibility)
     (defmethod print-method DocumentEntry [entry ^java.io.Writer w]
       (.write w (if (.-text entry)
                  (pr-str [(.-pos entry) (.-id entry) (.-text entry)])
                  (pr-str [(.-pos entry) (.-id entry)]))))

     (deftype InvertedEntry [^long word-hash word doc-id]
       Object
       (equals [this other]
         (and (instance? InvertedEntry other)
              (let [^InvertedEntry o other]
                (and (= word-hash (.-word-hash o))
                     (= word (.-word o))
                     (= doc-id (.-doc-id o))))))

       (hashCode [this]
         (unchecked-int
          (+ (unchecked-int word-hash)
             (* 31 (.hashCode ^Object doc-id)))))

       (toString [this]
         (str "[" word " " doc-id "]"))

       Comparable
       (compareTo [this other]
         (let [^InvertedEntry o other
               ;; Compare by hash first (fast long comparison)
               hash-cmp (Long/compare word-hash (.-word-hash o))]
           (if (zero? hash-cmp)
             ;; Hash collision: compare by actual word, then doc-id
             (let [word-cmp (compare word (.-word o))]
               (if (zero? word-cmp)
                 (compare doc-id (.-doc-id o))
                 word-cmp))
             hash-cmp)))

       clojure.lang.ILookup
       (valAt [this k]
         (case k
           0 word-hash  ;; B-tree sees long hash as key!
           1 doc-id
           2 word       ;; Original word at index 2
           nil))
       (valAt [this k not-found]
         (case k
           0 word-hash
           1 doc-id
           2 word
           not-found))

       clojure.lang.Indexed
       (nth [this i]
         (case i
           0 word-hash  ;; B-tree sees long hash as key!
           1 doc-id
           2 word       ;; Original word at index 2
           (throw (IndexOutOfBoundsException.))))
       (nth [this i not-found]
         (case i
           0 word-hash
           1 doc-id
           2 word
           not-found))

       clojure.lang.Counted
       (count [this] 3)

       clojure.lang.Seqable
       (seq [this]
         (list word-hash doc-id word)))

     ;; Custom print method for EDN serialization (MemoryStorage compatibility)
     (defmethod print-method InvertedEntry [entry ^java.io.Writer w]
       (.write w (pr-str [(.-word entry) (.-doc-id entry)])))

     ;; Factory function that computes word hash
     (defn ->InvertedEntry [word doc-id]
       "Create InvertedEntry with computed word hash for O(1) B-tree operations.
        Hash enables long-key optimization while preserving string for display."
       (let [word-hash (unchecked-long (.hashCode ^String word))]
         (InvertedEntry. word-hash word doc-id))))

   :cljs
   (do
     ;; ClojureScript uses plain vectors for now (deftype support can be added later)
     (defn ->DocumentEntry [pos id text]
       (if text
         [pos id text]
         [pos id]))

     (defn ->InvertedEntry [word doc-id]
       [word doc-id])))

;; Accessor functions for clean API
#?(:clj
   (do
     (defn doc-entry-pos ^long [^DocumentEntry e] (.-pos e))
     (defn doc-entry-id [^DocumentEntry e] (.-id e))
     (defn doc-entry-text [^DocumentEntry e] (.-text e))

     (defn inv-entry-word [^InvertedEntry e] (.-word e))
     (defn inv-entry-doc-id [^InvertedEntry e] (.-doc-id e)))

   :cljs
   (do
     (defn doc-entry-pos [e] (nth e 0))
     (defn doc-entry-id [e] (nth e 1))
     (defn doc-entry-text [e] (nth e 2 nil))

     (defn inv-entry-word [e] (nth e 0))
     (defn inv-entry-doc-id [e] (nth e 1))))
