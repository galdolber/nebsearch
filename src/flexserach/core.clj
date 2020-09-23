(ns flexsearch.core
  (:require [clojure.string :as string]
            [clojure.set :as sets]))

(def char-prev-is-vowel #{\a \e \i \o \u \y})

(defn collapse-repeating-chars [string]
  (loop [collapsed-string ""
         char-prev nil
         [[i char] & cx] (map-indexed vector string)]
    (if char
      (let [char-next (first cx)]
        (recur (if (not= char char-prev)
                 (if (and (pos? i) (= \h char))
                   (if (or (and (char-prev-is-vowel char-prev)
                                (char-prev-is-vowel char-next))
                           (= char-prev \ ))
                     (str collapsed-string char)
                     collapsed-string)
                   (str collapsed-string char))
                 collapsed-string)
               char
               cx))
      collapsed-string)))

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
   [#"[^a-z0-9 ]" ""] ;; there's a space at the end
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

(defn replace-regexes [str regexp]
  (if (seq regexp)
    (reduce (fn [str [regex rep]]
              (string/replace str regex rep))
            str
            regexp)
    str))

(defn encoder-icase [value]
  (string/lower-case value))

(defn encoder-simple [value]
  (when value
    (let [s (replace-regexes (string/lower-case value) simple-regex)]
      (if (string/blank? s) "" s))))

;; This one is slow!! optimize
(defn encoder-advanced
  ([string] (encoder-advanced string false))
  ([string skip]
   (if-not string
     string
     (let [string (encoder-simple string)]
       (if (< 2 (count string))
         (if (and (not skip) (< 1 (count string)))
           (collapse-repeating-chars (replace-regexes string advanced-regex))
           (replace-regexes string advanced-regex))
         (if (not skip)
           (if (< 1 (count string))
             (collapse-repeating-chars string)
             string)
           string))))))

(defn encoder-extra [string]
  (if-not string
    string
    (let [string (encoder-advanced string true)]
      (if (< 1 (count string))
        (collapse-repeating-chars
         (string/join " "
                      (loop [string (string/split string #" ")
                             current (get string 0)
                             c 0]
                        (if (= c (count string)) string
                            (recur (if (< 1 (count current))
                                     (assoc string c
                                            (str (first current)
                                                 (replace-regexes
                                                  (subs current 1)
                                                  extra-regex)))
                                     string)
                                   (get string (inc c))
                                   (inc c))))))
        string))))

(defn encoder-balance [string]
  (when string
    (collapse-repeating-chars (replace-regexes (string/lower-case string) balance-regex))))

(defn get-encoder [encoder]
  (case encoder
    :icase encoder-icase
    :simple encoder-simple
    :advanced encoder-advanced
    :extra encoder-extra
    :balance encoder-balance
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
  (update-in data (conj (vec (seq value)) :<) #(conj (or % #{}) id)) )

(defn remove-index [data value id]
  (update-in data (conj (vec (seq value)) :<) #(disj (or % #{}) id)))

(defn index-reverse [data operation value id]
  (let [value (vec value)]
    (reduce (fn [data n]
              (operation data (drop n value) id)) data (range (count value)))))

(defn index-forward [data operation value id]
  (let [value (vec value)]
    (reduce (fn [data n]
              (operation data (take n value) id)) data (range (count value)))))

(defn index-both [data operation value id]
  (index-forward (index-reverse data operation value id) operation value id))

(defn index-full [data operation value id]
  (let [value (vec value)]
    (reduce
     (fn [data from]
       (let [value (drop from value)]
         (reduce (fn [data n]
                   (operation data (take n value) id)) data
                 (range (inc (count value))))))
     data
     (range (dec (count value))))))

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
      (reduce into #{} (mapv (fn [word] (:< (get-in data (seq word)))) words)))))

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
