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

(def join-char \ñ) ;; normalized to 'n' by encoder, ensures it never appears in indexed text

(defn default-encoder [value]
  (when value
    (normalize (string/lower-case value))))


(defn default-splitter [^String s]
  (set (remove string/blank? (string/split s #"[^a-zA-Z0-9\.+]"))))

(defn init []
  ^{:cache (atom {})}
  {:data (pss/sorted-set)
   :index ""
   :ids {}})

(defn serialize [flex]
  (update flex :data #(into #{} %)))

(defn deserialize [flex]
  (-> flex
      (update :data #(apply pss/sorted-set %))
      (vary-meta #(or % {:cache (atom {})}))))

(defn find-len [index pos]
  (if-let [end (string/index-of index join-char pos)]
    (- end pos)
    (throw (ex-info "Invalid index position: join-char not found"
                    {:pos pos :index-length (count index)}))))

(defn search-remove [{:keys [index data ids] :as flex} id-list]
  {:pre [(map? flex)
         (or (nil? id-list) (sequential? id-list))]}
  (let [existing (filter identity (mapv (fn [id] [(get ids id) id]) id-list))]
    (swap! (:cache (meta flex)) update-vals #(apply disj % id-list))
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
  {:pre [(map? flex)
         (or (map? pairs) (sequential? pairs))]}
  (let [pairs (if (map? pairs) (seq pairs) pairs)
        updated-pairs (filter (comp ids first) pairs)
        {:keys [ids ^String index data] :as flex}
        (if (seq updated-pairs) (search-remove flex (mapv first updated-pairs)) flex)]
    (reset! (:cache (meta flex)) {})
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

(defn rebuild-index [pairs]
  (loop [[[_ w] & ws] pairs
         r (transient [])]
    (if w
      (recur ws (conj! r (default-encoder w)))
      (str (string/join join-char (persistent! r)) join-char))))

(defn find-positions [text min-pos max-pos search]
  (let [search-len (count search)]
    (loop [from min-pos
           r []]
      (if-let [i (string/index-of text search from)]
        (if (or (not max-pos) (<= i max-pos))
          (recur (+ (int i) search-len) (conj r i))
          r)
        r))))

(defn search [{:keys [index data] :as flex} search]
  {:pre [(map? flex)]}
  (if-not (and search data)
    #{}
    (let [cache (:cache (meta flex))
          words (default-splitter (default-encoder search))]
      (if (empty? words)
        #{}
        (or (get @cache words)
            (let [result
                  (apply
                   sets/intersection
                   (loop [[w & ws] (reverse (sort-by count words))
                          r []
                          min-pos 0
                          max-pos (count index)]
                     (if w
                       (let [positions (find-positions index min-pos max-pos w)
                             pairs (filterv some? (mapv (fn [i] (first (pss/rslice data [(inc i) nil] nil)))
                                                        positions))]
                         (if (seq pairs)
                           (let [new-min (long (apply min (map first pairs)))
                                 new-max (long (reduce (fn [mx [pos _]]
                                                         (max mx (+ pos (find-len index pos))))
                                                       0
                                                       pairs))]
                             (recur ws (conj r (set (map last pairs)))
                                    new-min new-max))
                           (recur ws (conj r #{}) min-pos max-pos)))
                       r)))]
              (swap! cache assoc words result)
              result))))))

(defn search-gc [{:keys [index data] :as flex}]
  (let [pairs (mapv (fn [[pos id :as pair]]
                      (let [len (find-len index (first pair))]
                        [id (subs index pos (+ pos len))])) data)
        new-flex (search-add (init) pairs)]
    (with-meta new-flex (meta flex))))
