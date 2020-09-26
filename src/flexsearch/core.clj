(ns flexsearch.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.set :as sets]
            [flexsearch.data :as fd]))

(set! *warn-on-reflection* true)

(defn ^String normalize [^String str]
  (let [^String normalized (java.text.Normalizer/normalize str java.text.Normalizer$Form/NFD)]
    (clojure.string/replace normalized #"\p{InCombiningDiacriticalMarks}+" "")))

#_(defn ^string normalize-cljs [^string s] (.replace (.normalize s "NFD") #"[\u0300-\u036f]" ""))

(def simple-regex
  [[#"[àáâãäå]" "a"]
   [#"[èéêë]" "e"]
   [#"[ìíîï]" "i"]
   [#"[òóôõöő]" "o"]
   [#"[ùúûüű]" "u"]
   [#"[ýŷÿ]" "y"]
   [#"ñ" "n"]
   [#"[çc]" "k"]
   [#"ß" "s"]
   [#" & " " and "]
   [#"[-/]" " "]
   [#"[^a-z0-9 ]" ""];;ojo con el espacio al final de [^a-z0-9 ]
   [#"\s+" " "]])

(def advanced-regex
  [[#"ae" "a"]
   [#"ai" "ei"]
   [#"ay" "ei"]
   [#"ey" "ei"]
   [#"oe" "o"]
   [#"ue" "u"]
   [#"ie" "i"]
   [#"sz" "s"]
   [#"zs" "s"]
   [#"sh" "s"]
   [#"ck" "k"]
   [#"cc" "k"]
   [#"th" "t"]
   [#"dt" "t"]
   [#"ph" "f"]
   [#"pf" "f"]
   [#"ou" "o"]
   [#"uo" "u"]])

(def extra-regex
  [[#"p" "b"]
   [#"z" "s"]
   [#"[cgq]" "k"]
   [#"n" "m"]
   [#"d" "t"]
   [#"[vw]" "f"]
   [#"[aeiouy]" ""]])

(def balance-regex
  [[#"[-/]" " "]
   [#"[^a-z0-9 ]" ""]
   [#"\s+" " "]])


(defn replace-regexes [^String str regexp]
(reduce (fn [^String str [regex rep]]
          (if (keyword? regex)
            (.replaceAll str (name regex) rep)
            (string/replace str regex rep)))
        str
        regexp))




(defn collapse-repeating-chars [string]
  (apply str (dedupe string)))

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





(defn encode-value [value {:keys [encoder stemmer]}]
  (when value
    (-> value
        encoder
        (replace-regexes stemmer))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))





(defn index-reverse [data operation ^String value id]
  (reduce (fn [data n]
            (operation data (subs value n) id))
          data
          (range (count value))))

(defn index-forward [data operation ^String value id]
  (reduce (fn [data n]
            (operation data (subs value 0 n) id))
          data
          (range 1 (inc (count value)))))

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
   (range (count value))))

(subs "a" 0)
(range 0)
(index-full {} add-index "a" 1)


(defn get-indexer [indexing]
  (case indexing
    :forward index-forward
    :reverse index-reverse
    :both index-both
    :full index-full
    index-forward))






(defn init [{:keys [tokenizer split indexer filter encoder] :as options}]
  (let [encoder (get-encoder encoder)]
    (assoc (merge {:ids {} :data {}} options)
           :indexer (get-indexer indexer)
           :encoder (get-encoder encoder)
           :tokenizer (if (fn? tokenizer) tokenizer #(string/split % (or split #"\W+")))
           :filter (set (mapv encoder filter)))))
(def flex-f (init {:tokenizer false :split #"\W+" :indexer :forward :filter #{"and" "or"} :encoder :advanced}))
(def flex-r (init {:tokenizer false :split #"\W+" :indexer :reverse :filter #{"and" "or"} :encoder :advanced}))
(def flex-b (init {:tokenizer false :split #"\W+" :indexer :both :filter #{"and" "or"} :encoder :advanced}))
(def flex-l (init {:tokenizer false :split #"\W+" :indexer :full :filter #{"and" "or"} :encoder :advanced}))


(defn add-index [data ^String value id]
  #_(assoc data value (conj (or (get data value) #{}) id))
  (update data value #(conj (or % #{}) id)))

(defn remove-index [data ^String value id]
#_(assoc data value (disj (or (get data value) #{}) id))
(update data value #(disj (or % #{}) id)))

(defn add-indexes [flex indexer words id]
  (assoc flex :data
         (reduce (fn [data word]
                   (indexer data add-index word id))
                 (:data flex)
                 words)))
(def f-johnny (add-indexes flex-f index-forward ["johnny" "alias" "deep"] 1))

(defn remove-indexes [flex indexer words id]
  (assoc flex :data
         (reduce (fn [data word]
                   (indexer data remove-index word id))
                 (:data flex)
                 words)))





(def words (set (#(string/split %  #"\W+") (encode-value "$1,000 a Touchdown" flex-l))))
#{"" "touchdown" "a" "1" "0"}
(add-indexes flex-l index-full words 1)




(defn flex-add
  [{:keys [ids tokenizer indexer filter] :as flex} id content]
  (let [content (encode-value content flex)
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
(def pedro-gonzales (flex-add flex-f 1 "Pedro Gonzales"))
(def gonzales+garcia (flex-add pedro-gonzales 2 "Pedro Garcia"))


(defn flex-remove [{:keys [ids indexer] :as flex} id]
  (if-let [old-words (get ids id)]
    (-> flex
        (remove-indexes indexer old-words id)
        (update :ids #(dissoc % id)))
    flex))

(defn flex-search [{:keys [data tokenizer filter] :as flex} search]
  (when (and search data)
    (let [search (encode-value search flex)
          words (tokenizer search)
          words (set (if filter (filter-words words filter) words))]
      ;; TODO? add threshold?
      #_(reduce into #{} (mapv data words))
      (apply sets/intersection (mapv data words)))))


(comment
  (time (let [flex (init {:indexer :full :encoder :advanced})
              flex (reduce (fn [flex [k v]] (flex-add flex k v)) flex (map vector (range) #_data
                                                                           ["TAL" "DOLBER"] ))]
          (get (:data flex) "abs")
          #_(flex-search flex "and jus"))))







(def data2 ["$1,000 a Touchdown"])
;;ojo que daba ["1" #{2}]

(def pares (map vector (range (count fd/data)) fd/data))
(def corto (map vector (range (count data2)) data2))

(defn ffaa [flex [nro film]]
  (flex-add flex nro film))

(prn(sort (:data (time (reduce ffaa flex-l corto)))))


(["0" #{0}] ["00" #{0}] ["000" #{0}] ["c" #{0}] ["ch" #{0}] ["chd" #{0}] ["chdo" #{0}] ["chdow" #{0}] ["chdown" #{0}] ["d" #{0}] ["do" #{0}] ["dow" #{0}] ["down" #{0}] ["h" #{0}] ["hd" #{0}] ["hdo" #{0}] ["hdow" #{0}] ["hdown" #{0}] ["o" #{0}] ["ou" #{0}] ["ouc" #{0}] ["ouch" #{0}] ["ouchd" #{0}] ["ouchdo" #{0}] ["ouchdow" #{0}] ["ouchdown" #{0}] ["ow" #{0}] ["own" #{0}] ["t" #{0}] ["to" #{0}] ["tou" #{0}] ["touc" #{0}] ["touch" #{0}] ["touchd" #{0}] ["touchdo" #{0}] ["touchdow" #{0}] ["touchdown" #{0}] ["u" #{0}] ["uc" #{0}] ["uch" #{0}] ["uchd" #{0}] ["uchdo" #{0}] ["uchdow" #{0}] ["uchdown" #{0}] ["w" #{0}] ["wn" #{0}])