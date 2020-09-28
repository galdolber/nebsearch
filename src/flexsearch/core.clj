(ns flexsearch.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.set :as sets]))

(set! *warn-on-reflection* true)

(defn collapse-repeating-chars [string]
  (apply str (dedupe string)))

(defn ^String normalize [^String str]
  (let [^String normalized (java.text.Normalizer/normalize str java.text.Normalizer$Form/NFD)]
    (clojure.string/replace normalized #"\p{InCombiningDiacriticalMarks}+" "")))

#_(defn ^string normalize-cljs [^string s] (.replace (.normalize s "NFD") #"[\u0300-\u036f]" ""))

(defn replace-regexes [^String str regexp]
  (reduce (fn [^String str [regex rep]]
            (if (keyword? regex)
              (.replaceAll str (name regex) rep)
              (string/replace str regex rep)))
          str
          regexp))

(defn encoder-icase [^String value]
  (string/lower-case value))

(defn encoder-simple [^String value]
  (when value
    (let [s (normalize (string/lower-case value))]
      (if (string/blank? s) "" s))))

(defn encoder-advanced [^String string]
  (when string
    (let [string (encoder-simple string)]
      (collapse-repeating-chars string))))

(defn get-encoder [encoder]
  (case encoder
    :icase encoder-icase
    :simple encoder-simple
    :advanced encoder-advanced
    encoder-icase))

#_(defn encode-value [{:keys [encoder stemmer]} value]
  (when value
    (-> value
        encoder
        (replace-regexes stemmer))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn index-reverse [data ^String value]
  (let [len (count value)]
    (loop [i 0
           r data]
      (if (= len i)
        r
        (recur (inc i) (conj! r (.substring value i)))))))

(defn index-forward [data ^String value]
  (let [len (inc (count value))]
    (loop [i 1
           r data]
      (if (= len i)
        r
        (recur (inc i) (conj! r (.substring value 0 i)))))))

(defn index-both [data ^String value]
  (let [len (inc (count value))]
    (loop [i 1
           r data]
      (if (= len i)
        r
        (recur (inc i) (conj! (conj! r (.substring value 0 i)) (.substring value (dec i))))))))

(defn index-full [data ^String value]
  (let [len (count value)
        llen (dec len)]
    (loop [i 0
           j 1
           r data]
      (if (and (= llen i) (= len j))
        r
        (let [r (conj! r (.substring value i j))]
          (if (= len j)
            (recur (inc i) (+ i 2) r)
            (recur i (inc j) r)))))))

(defn get-indexer [indexing]
  (case indexing
    :forward index-forward
    :reverse index-reverse
    :both index-both
    :full index-full
    index-forward))

(defn default-splitter [^String s]
  (remove string/blank? (string/split s #"[\W+|[^A-Za-z0-9]]")))

(defn init [{:keys [tokenizer indexer filter] :as options}]
  (let [encoder (get-encoder (:encoder options))]
    (assoc (merge {:ids {}} options)
           :indexer (get-indexer indexer)
           :encoder encoder
           :tokenizer (if (fn? tokenizer) tokenizer default-splitter)
           :filter (when-not (empty? filter) (set (mapv encoder filter))))))

(defn flex-add
  [{:keys [tokenizer indexer encoder filter] :as flex} id content]
  (let [content (encoder content)
        words (tokenizer content)
        words (if filter (filter-words words filter) words)
        words (persistent! (reduce indexer (transient #{}) words))]
    (assoc-in flex [:ids id] words)))

(defn flex-remove [flex id]
  (update flex :ids #(dissoc % id)))

(defn flex-search [{:keys [ids tokenizer encoder] :as flex} search]
  (when (and search ids)
    (let [search (encoder search)
          words (tokenizer search)
          words (set (if-let [f (:filter flex)] (filter-words words f) words))]
      ;; TODO? add threshold?
      (set
       (mapv first
             (filter (fn [[_ tokens]]
                       (sets/superset? (set tokens) words)) ids)))
      #_(apply sets/intersection (mapv data words)))))

(defn -main [& args]
  (let [data (map vector (range) (read-string (slurp "data.edn")))
        _ (read-line)
        flex
        (time (reduce (fn [flex [k v]]
                        (flex-add flex k v))
                      (init {:indexer :full :encoder :simple})
                      data))]
    (read-line)
    (time (flex-search flex "Things I Hate About"))
    (time (flex-search flex "Things I Hate About"))
    (read-line)))

#_(def dd (map vector (range) (read-string (slurp "data.edn"))))
#_(let [flex (time (reduce (fn [flex [k v]]
                           (flex-add flex k v))
                         (init {:indexer :forward :encoder :simple})
                         dd))]
  (time (flex-search flex "Things I Hate About"))
  (time (flex-search flex "Things I Hate About")))


;;commit de branch alternativo