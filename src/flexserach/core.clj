(ns flexsearch.core
  (:require [clojure.string :as string]
            [clojure.set :as sets]))

(defn collapse-repeating-chars [string]
  (string/replace string #"(.)(?=.*\1)" ""))

(defn ^String normalize [^String str]
  (let [^String normalized (java.text.Normalizer/normalize str java.text.Normalizer$Form/NFD)]
    (clojure.string/replace normalized #"\p{InCombiningDiacriticalMarks}+" "")))

#_(defn ^string normalize-cljs [^string s] (.replace (.normalize s "NFD") #"[\u0300-\u036f]" ""))

(defn replace-regexes [^String str regexp]
  (reduce (fn [^String str [regex rep]]
            (string/replace str regex rep))
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

(defn encode-value [{:keys [encoder stemmer matcher]} value]
  (when value
    (-> value
        (replace-regexes matcher)
        encoder
        (replace-regexes stemmer))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn init [{:keys [tokenizer split] :as options}]
  (let [encoder (get-encoder (:encoder options))]
    (-> {:indexer :forward}
        (merge options)
        (assoc :encoder encoder)
        (assoc :tokenizer (if (fn? tokenizer) tokenizer #(string/split % (or split #"\W+"))))
        (update :filter #(when % (set (mapv encoder %)))))))

(defn add-index [data value id]
  ;;(update-in data (conj (vec (seq value)) :<) #(conj (or % #{}) id))
  (update data value #(conj (or % #{}) id)))

(defn remove-index [data value id]
  #_(update-in data (conj (vec (seq value)) :<) #(disj (or % #{}) id))
  (update data value #(disj (or % #{}) id)))

(defn index-reverse [data operation value id]
  (reduce (fn [data n]
            (operation data (subs value n) id)) data (range (count value))))

(defn index-forward [data operation value id]
  (reduce (fn [data n]
            (operation data (subs value 0 n) id)) data (range (count value))))

(defn index-both [data operation value id]
  (index-forward (index-reverse data operation value id) operation value id))

(defn index-full [data operation value id]
  (reduce
   (fn [data from]
     (let [value (subs value from)]
       (reduce (fn [data n]
                 (operation data (subs value 0 n) id)) data
               (range (inc (count value))))))
   data
   (range (dec (count value)))))

(defn get-indexer [indexing]
  (case indexing
    :forward index-forward
    :reverse index-reverse
    :both index-both
    :full index-full
    index-forward))

(defn remove-indexes [flex indexer words id]
  (assoc flex :data
         (reduce (fn [data word]
                   (indexer data remove-index word id)) (:data flex) words)))

(defn add-indexes [flex indexer words id]
  (assoc flex :data
         (reduce (fn [data word]
                   (indexer data add-index word id)) (:data flex) words)))

(defn flex-add [{:keys [ids tokenizer indexer filter] :as flex} id content]
  (let [content (encode-value flex content)
        words (tokenizer content)
        words (set (if filter (filter-words words filter) words))
        indexer (get-indexer indexer)]
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
  (let [indexer (get-indexer indexer)]
    (if-let [old-words (get ids id)]
      (-> flex
          (remove-indexes indexer old-words id)
          (update-in [:ids] #(dissoc % id)))
      flex)))

(defn flex-search [{:keys [data tokenizer filter] :as flex} search]
  (when search
    (let [search (encode-value flex search)
          words (tokenizer search)
          words (set (if filter (filter-words words filter) words))]
      ;; TODO? add threshold?
      (reduce into #{} (mapv data words)))))

(comment
  (let [flex (-> (init {:indexer :full :filter #{"el"}})
                 (flex-add 1 "el Perro iba caminando por el parque")
                 (flex-add 2 "GATOO")
                 (flex-remove 1)
                 ;;(flex-add 1 "el Perro iba caminando por el parque")
                 )]
    ;;(println flex)
    [(flex-search flex "er") ;; #{}
     (flex-search flex "atoo")] ;;#{2}
    ))

;;(index-reverse {} add-index "hello" 23)
;;(index-forward {} add-index "hello" 23)
;;(index-both {} add-index "hello" 23)
;;(index-full {} add-index "hello" 23)
