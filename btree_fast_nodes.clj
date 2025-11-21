(ns nebsearch.btree-fast-nodes
  "B-tree node deftypes for maximum performance")

;; Deftype nodes for zero overhead
#?(:clj
   (do
     (deftype InternalNode [keys children]
       Object
       (equals [this other]
         (and (instance? InternalNode other)
              (let [^InternalNode o other]
                (and (= keys (.-keys o))
                     (= children (.-children o))))))

       clojure.lang.ILookup
       (valAt [this k]
         (case k
           :type :internal
           :keys keys
           :children children
           nil))
       (valAt [this k not-found]
         (case k
           :type :internal
           :keys keys
           :children children
           not-found)))

     (deftype LeafNode [entries next-leaf]
       Object
       (equals [this other]
         (and (instance? LeafNode other)
              (let [^LeafNode o other]
                (and (= entries (.-entries o))
                     (= next-leaf (.-next-leaf o))))))

       clojure.lang.ILookup
       (valAt [this k]
         (case k
           :type :leaf
           :entries entries
           :next-leaf next-leaf
           nil))
       (valAt [this k not-found]
         (case k
           :type :leaf
           :entries entries
           :next-leaf next-leaf
           not-found)))

     (defn internal-node-fast [keys children]
       (->InternalNode keys children))

     (defn leaf-node-fast [entries next-leaf]
       (->LeafNode entries next-leaf))))

;; Usage:
;; (let [leaf (leaf-node-fast [entry1 entry2] nil)
;;       entries (.-entries leaf)] ; direct field access
;;   ...)
