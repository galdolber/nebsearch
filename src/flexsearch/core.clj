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

(defn encoder-icase [value]
  (string/lower-case value))

(defn encoder-simple [value]
  (when value
    (let [s (normalize (string/lower-case value))]
      (if (string/blank? s) "" s))))

(defn encoder-advanced [string]
  (when string
    (let [string (encoder-simple string)]
      (collapse-repeating-chars string))))

(defn get-encoder [encoder]
  (case encoder
    :icase encoder-icase
    :simple encoder-simple
    :advanced encoder-advanced
    encoder-icase))

(defn encode-value [{:keys [encoder stemmer]} value]
  (when value
    (-> value
        encoder
        (replace-regexes stemmer))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn index-reverse [^String value]
  (let [len (count value)]
    (loop [i 0
           r (transient #{})]
      (if (= len i)
        (persistent! r)
        (recur (inc i) (conj! r (.substring value i)))))))

(defn index-forward [^String value]
  (let [len (inc (count value))]
    (loop [i 1
           r (transient [])]
      (if (= len i)
        (persistent! r)
        (recur (inc i) (conj! r (.substring value 0 i)))))))

(defn index-both [^String value]
  (let [len (inc (count value))]
    (loop [i 1
           r (transient [])]
      (if (= len i)
        (persistent! r)
        (recur (inc i) (conj! (conj! r (.substring value 0 i)) (.substring value (dec i))))))))

(defn index-full [^String value]
  (let [len (count value)
        llen (dec len)]
    (loop [i 0
           j 1
           r (transient [])]
      (if (and (= llen i) (= len j))
        (persistent! r)
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
    (assoc (merge {:ids {} :data {}} options)
           :indexer (get-indexer indexer)
           :encoder encoder
           :tokenizer (if (fn? tokenizer) tokenizer default-splitter)
           :filter (set (mapv encoder filter)))))

(defn flex-add
  [{:keys [tokenizer indexer filter] :as flex} id content]
  (let [content (encode-value flex content)
        words (tokenizer content)
        words (set (if filter (filter-words words filter) words))
        words (set (mapcat indexer words))]
    (assoc-in flex [:ids id] words)))

(defn flex-remove [flex id]
  (update flex :ids #(dissoc % id)))

(defn flex-search [{:keys [ids tokenizer] :as flex} search]
  (when (and search ids)
    (let [search (encode-value flex search)
          words (tokenizer search)
          words (set (if-let [f (:filter flex)] (filter-words words f) words))]
      ;; TODO? add threshold?
      #_(reduce into #{} (mapv data words))
      (set
       (mapv first
             (filter (fn [[_ tokens]]
                       (sets/superset? tokens words)) ids)))
      #_(apply sets/intersection (mapv data words)))))

(def sample-data (read-string (slurp "data.edn")))

(defn -main [& args]
  (println (read-line))
  (time (let [flex (init {:indexer :full :encoder :advanced})
              flex (reduce (fn [flex [k v]]
                             (flex-add flex k v)) flex (map vector sample-data #_(range) sample-data))]
        (time (flex-search flex "midnig boston")))))
