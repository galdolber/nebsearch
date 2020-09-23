(ns flexsearch.core
  (:require [clojure.string :as str]))

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
              (str/replace str regex rep))
            str
            regexp)
    str))

(defn encoder-icase [value]
  (str/lower-case value))

(defn encoder-simple [value]
  (when value
    (let [s (replace-regexes (str/lower-case value) simple-regex)]
      (if (str/blank? s) "" s))))

(defn encoder-advanced
  ([string] (encoder-advanced string false))
  ([string skip]
   (if-not string
     string
     (let [string (encoder-simple string)]
       (cond (< 2 (count string)) (if (and (not skip) (< 1 (count string)))
                                    (collapse-repeating-chars (replace-regexes string advanced-regex))
                                    (replace-regexes string advanced-regex))
             (not skip) (if (< 1 (count string))
                          (collapse-repeating-chars string)
                          string)
             :else string)))))

(defn encoder-extra [string]
  (if-not string
    string
    (let [string (encoder-advanced string true)]
      (if (< 1 (count string))
        (collapse-repeating-chars
         (str/join " "
                   (loop [string (str/split string #" ")
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
    (collapse-repeating-chars (replace-regexes (str/lower-case string) balance-regex))))

(defn get-encoder [encoder]
  (case encoder
    :icase encoder-icase
    :simple encoder-simple
    :advanced encoder-advanced
    :extra encoder-extra
    :balance encoder-balance
    identity))

(defn encode-value [{:keys [encoder stemmer matcher]} value]
  (when value
    (-> value
        (replace-regexes matcher)
        encoder
        (replace-regexes stemmer))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn init [options]
  (let [encoder (:encoder options)
        encoder (or (get-encoder encoder) encoder)]
    (-> {:encoder "icase"
         :indexer "forward"
         :split #"\W+"}
        (merge options)
        (assoc :encoder encoder)
        (update :filter #(when % (set (mapv encoder %)))))))

(defn add-index [flex value id]
  (update-in flex (cons :data (conj (vec (seq value)) :<)) #(conj (or % #{}) id)))

(defn remove-index [flex value id]
  (update-in flex (cons :data (conj (vec (seq value)) :<)) #(disj (or % #{}) id)))

(defn index-reverse [flex operation value id]
  (let [value (vec value)]
    (reduce (fn [flex n]
              (operation flex (drop n value) id)) flex (range (count value)))))

(defn index-forward [flex operation value id]
  (let [value (vec value)]
    (reduce (fn [flex n]
              (operation flex (take n value) id)) flex (range (count value)))))

(defn index-both [flex operation value id]
  (index-forward (index-reverse flex operation value id) operation value id))

(defn index-full [flex operation value id]
  (let [value (vec value)]
    (reduce
     (fn [flex from]
       (let [value (drop from value)]
         (reduce (fn [flex n]
                   (operation flex (take n value) id)) flex
                 (range (inc (count value))))))
     flex
     (range (dec (count value))))))

(defn get-indexer [indexing]
  (case indexing
    :forward index-forward
    :reverse index-reverse
    :both index-both
    :full index-full
    index-forward))

(defn remove-indexes [flex indexer words id]
  (reduce (fn [flex word]
            (indexer flex remove-index word id)) flex words))

(defn add-indexes [flex indexer words id]
  (reduce (fn [flex word]
            (indexer flex add-index word id)) flex words))

(defn flex-add [{:keys [ids tokenizer indexer split filter] :as flex} id content]
  (let [words (if (fn? tokenizer) (tokenizer content) (str/split content split))
        words (if filter (filter-words words filter) words)
        words (set (map #(encode-value flex %) words))
        indexer (get-indexer indexer)]
    (if-let [old-words (get ids id)]
      (-> flex
          (remove-indexes indexer old-words id)
          (add-indexes indexer words id)
          (assoc-in [:ids id] words))
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

(defn flex-search [{:keys [data tokenizer split filter] :as flex} search]
  (when search
    (let [words (if (fn? tokenizer) (tokenizer search) (str/split search split))
          words (if filter (filter-words words filter) words)
          words (set (map #(encode-value flex %) words))]
      ;; TODO? add threshold?
      (reduce merge (mapv (fn [word] (:< (get-in data (seq word)))) words)))))

(comment
  (let [flex (-> (init {:encoder :icase})
                 (flex-add 1 "el Perro iba caminando por el parque")
                 (flex-add 2 "GATOO")
                 (flex-remove 1)
                 )]
    (println (:data flex))
    (println (flex-search flex "PErr")) ;; #{}
    (println (flex-search flex "GATO")) ;; #{2}
    ))

;;(index-reverse {} add-index "hello" 23)
;;(index-forward {} add-index "hello" 23)
;;(index-both {} add-index "hello" 23)
;;(index-full {} add-index "hello" 23)
