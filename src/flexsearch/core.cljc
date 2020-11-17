(ns flexsearch.core
  #?(:clj (:gen-class))
  (:require [clojure.string :as string]
            [clojure.set :as sets]
            [me.tonsky.persistent-sorted-set :as pss]))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn ^String normalize [^String str]
     (let [^String normalized (java.text.Normalizer/normalize str java.text.Normalizer$Form/NFD)]
       (clojure.string/replace normalized #"\p{InCombiningDiacriticalMarks}+" "")))

   :cljs (defn normalize [^string s]
           (.replaceAll
            ^string (.normalize s "NFD")
            (js/RegExp. "[\u0300-\u036f]" \g) "")))

(defn default-encoder [value]
  (when value
    (normalize (string/lower-case value))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn default-splitter [^String s]
  (mapv #(if (= (count %) 1)
           (str " " %)
           %)
        (remove string/blank? (string/split s #"[^a-zA-Z0-9+]"))))

(defn init [{:keys [tokenizer filter encoder] :as options}]
  (assoc options
         :data (pss/sorted-set)
         :index ""
         :garbage 0
         :ids {}
         :encoder (or encoder default-encoder)
         :tokenizer (if (fn? tokenizer) tokenizer default-splitter)
         :filter (set (mapv encoder filter))))

(def join-char \,)

(defn flex-remove [{:keys [index data ids garbage] :as flex} id-list]
  (let [existing (filter identity (map ids id-list))]
    (loop [[[pos :as pair] & ps] existing
           data (transient data)
           index index
           garbage garbage]
      (if pair
        (let [len (:len (meta pair))]
          (recur ps (disj! data pair)
                 (str (subs index 0 pos)
                      (apply str (repeat len " "))
                      (subs index (+ pos len)))
                 (+ garbage len)))
        (assoc flex :garbage garbage :ids (apply dissoc ids id-list) :data (persistent! data) :index index)))))

(defn flex-add [{:keys [ids encoder] :as flex} pairs]
  (let [updated-pairs (filter (comp ids first) pairs)
        {:keys [ids ^String index data] :as flex}
        (if (seq updated-pairs) (flex-remove flex (mapv first updated-pairs)) flex)]
    (loop [[[id w] & ws] pairs
           pos #?(:clj (.length index) :cljs (.-length index))
           data (transient data)
           r (transient [])
           ids (transient ids)]
      (if w
        (let [^String w (encoder w)
              len #?(:clj (.length w) :cljs (.-length w))
              pair (with-meta [pos id] {:len len})]
          (recur ws (+ pos len 1) (conj! data pair) (conj! r w) (assoc! ids id pair)))
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

(defn flex-search [{:keys [index data tokenizer filter encoder]} search]
  (when (and search data)
    (let [search (encoder search)
          words (tokenizer search)
          words (set (if filter (filter-words words filter) words))]
      (apply
       sets/intersection
       (loop [[w & ws] (reverse (sort-by count words))
              r []
              min-pos 0
              max-pos (count index)]
         (if w
           (let [pairs (mapv (fn [i] (first (pss/rslice data [(inc i) nil] [-1 nil]))) (find-positions index min-pos max-pos w))]
             (recur ws (conj r (set (map last pairs)))
                    (int (if (seq pairs) (int (apply min (map first pairs))) min-pos))
                    (int (if (seq pairs) (int (apply max (map #(+ (:len (meta %)) (first %)) pairs))) max-pos))))
           r))))))

(defn flex-gc [{:keys [index data] :as flex}]
  (flex-add (assoc flex :data (pss/sorted-set) :index "" :ids {} :garbage 0)
            (mapv (fn [[pos id :as pair]]
                    (let [len (:len (meta pair))]
                      [id (subs index pos (+ pos len))])) data)))
