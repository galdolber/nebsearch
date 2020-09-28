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

(defn index-reverse [data operation ^String value id]
  (reduce (fn [data n]
            (operation data (subs value n) id)) data (range (count value))))

(defn index-forward [data operation ^String value id]
  (reduce (fn [data n]
            (operation data (subs value 0 n) id)) data (range 1 (inc (count value)))))

(defn index-both [data operation ^String value id]
  (index-forward (index-reverse data operation value id) operation value id))

(defn index-full [data operation ^String value id]
  (reduce
   (fn [data from]
     (let [value (subs value from)]
       (reduce (fn [data n]
                 (operation data (subs value 0 n) id)) data
               (range 1 (inc (count value))))))
   data
   (range (dec (count value)))))

(defn get-indexer [indexing]
  (case indexing
    :forward index-forward
    :reverse index-reverse
    :both index-both
    :full index-full
    index-forward))

(defn default-splitter [^String s]
  (string/split s #"[\W+|[^A-Za-z0-9]]"))

(defn init [{:keys [tokenizer indexer filter] :as options}]
  (let [encoder (get-encoder (:encoder options))]
    (assoc (merge {:ids {} :data {}} options)
           :indexer (get-indexer indexer)
           :encoder encoder
           :tokenizer (if (fn? tokenizer) tokenizer default-splitter)
           :filter (set (mapv encoder filter)))))

(defn add-index [data ^String value id]
  #_(assoc data value (conj (or (get data value) #{}) id))
  (update data value #(conj (or % #{}) id)))

(defn remove-index [data ^String value id]
  #_(assoc data value (disj (or (get data value) #{}) id))
  (update data value #(disj (or % #{}) id)))

(defn remove-indexes [flex indexer words id]
  (assoc flex :data
         (reduce (fn [data word]
                   (indexer data remove-index word id)) (:data flex) words)))

(defn add-indexes [flex indexer words id]
  (assoc flex :data
         (reduce (fn [data word]
                   (indexer data add-index word id)) (:data flex) words)))

(defn flex-add
  [{:keys [ids tokenizer indexer filter] :as flex} id content]
  (let [content (encode-value flex content)
        words (tokenizer content)
        words (set (if filter (filter-words words filter) words))]
    (if-let [old-words (get ids id)]
      (let [added (sets/difference words old-words)
            deleted (sets/difference old-words words)]
        (-> flex
            (remove-indexes indexer deleted id)
            (add-indexes indexer added id)
            (assoc-in [:ids id] words)))
      (-> flex
          (add-indexes indexer words id)
          (assoc-in [:ids id] words)))))

(defn flex-remove [{:keys [ids indexer] :as flex} id]
  (if-let [old-words (get ids id)]
    (-> flex
        (remove-indexes indexer old-words id)
        (update :ids #(dissoc % id)))
    flex))

(defn flex-search [{:keys [data tokenizer filter] :as flex} search]
  (when (and search data)
    (let [search (encode-value flex search)
          words (tokenizer search)
          words (set (if filter (filter-words words filter) words))]
      ;; TODO? add threshold?
      #_(reduce into #{} (mapv data words))
      (apply sets/intersection (mapv data words)))))

(defn -main [& args]
  (let [sample-data (read-string (slurp "data.edn"))
        _ (read-line)
        flex (init {:indexer :full :encoder :advanced})
        flex (time (reduce (fn [flex [k v]]
                             (flex-add flex k v)) flex (map vector (range) sample-data)))]
    (time (flex-search flex "and jus"))
    (time (flex-search flex "and jus"))
    (read-line)))
