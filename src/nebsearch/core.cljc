(ns nebsearch.core
  #?(:clj (:gen-class))
  (:require [clojure.string :as string]
            [clojure.set :as sets]
            [me.tonsky.persistent-sorted-set :as pss]))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn normalize ^String [^String str]
     (let [^String normalized (java.text.Normalizer/normalize str java.text.Normalizer$Form/NFD)]
       (clojure.string/replace normalized #"\p{InCombiningDiacriticalMarks}+" "")))

   :cljs (defn normalize [^string s]
           (let [^string s (.normalize s "NFD")]
             (clojure.string/replace s #"[\u0300-\u036f]" ""))))

(def join-char \ñ) ;; this char is replaced by the encoder

(defn default-encoder [value]
  (when value
    (normalize (string/lower-case value))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn default-splitter [^String s]
  (remove string/blank? (string/split s #"[^a-zA-Z0-9\.+]")))

(defn init []
  {:data (pss/sorted-set)
   :index ""
   :ids {}})

(defn find-len [index pos]
  (- (string/index-of index join-char pos) pos))

(defn search-remove [{:keys [index data ids] :as flex} id-list]
  (let [existing (filter identity (mapv (fn [id] [(get ids id) id]) id-list))]
    (loop [[[pos :as pair] & ps] existing
           data (transient data)
           index index]
      (if pair
        (let [len (find-len index pos)]
          (recur ps (disj! data pair)
                 (str (subs index 0 pos)
                      (apply str (repeat len " "))
                      (subs index (+ pos len)))))
        (assoc flex
               :ids (apply dissoc ids id-list)
               :data (persistent! data) :index index)))))

(defn search-add [{:keys [ids] :as flex} pairs]
  (let [updated-pairs (filter (comp ids first) pairs)
        {:keys [ids ^String index data] :as flex}
        (if (seq updated-pairs) (search-remove flex (mapv first updated-pairs)) flex)]
    (loop [[[id w] & ws] pairs
           pos #?(:clj (.length index) :cljs (.-length index))
           data (transient data)
           r (transient [])
           ids (transient ids)]
      (if w
        (let [^String w (default-encoder w)
              len #?(:clj (.length w) :cljs (.-length w))
              pair [pos id]]
          (recur ws (+ pos len 1) (conj! data pair) (conj! r w)
                 (assoc! ids id (first pair))))
        (assoc flex
               :ids (persistent! ids)
               :index (str index (string/join join-char (persistent! r)) join-char)
               :data (persistent! data))))))

(defn find-positions [text min-pos max-pos search]
  (let [search-len (count search)]
    (loop [from min-pos
           r []]
      (if-let [i (string/index-of text search from)]
        (if (or (not max-pos) (<= i max-pos))
          (recur (+ (int i) search-len) (conj r i))
          r)
        r))))

(defn search [{:keys [index data]} search]
  (when (and search data)
    (let [search (default-encoder search)
          words (default-splitter search)]
      (if (empty? words)
        #{}
        (apply
         sets/intersection
         (loop [[w & ws] (reverse (sort-by count words))
                r []
                min-pos 0
                max-pos (count index)]
           (if w
             (let [pairs (mapv (fn [i] (first (pss/rslice data [(inc i) nil] nil))) (find-positions index min-pos max-pos w))]
               (recur ws (conj r (set (map last pairs)))
                      (int (if (seq pairs) (int (apply min (map first pairs))) min-pos))
                      (int (if (seq pairs)
                             (int (apply max (map #(+ (find-len index (first %)) (first %)) pairs)))
                             max-pos))))
             r)))))))

(defn search-gc [{:keys [index data] :as flex}]
  (search-add (assoc flex :data (pss/sorted-set) :index "" :ids {})
              (mapv (fn [[pos id :as pair]]
                      (let [len (find-len index (first pair))]
                        [id (subs index pos (+ pos len))])) data)))
