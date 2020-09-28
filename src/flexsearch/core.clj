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

(normalize "...And Justice for All")

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

(def prepositions
  #{"about"
    "beside"
    "near"
    "to"
    "above"
    "between"
    "of"
    "towards"
    "across"
    "beyond"
    "off"
    "under"
    "after"
    "by"
    "on"
    "underneath"
    "against"
    "despite"
    "onto"
    "unlike"
    "along"
    "down"
    "opposite"
    "until"
    "among"
    "during"
    "out"
    "up"
    "around"
    "except"
    "outside"
    "upon"
    "as"
    "for"
    "over"
    "via"
    "at"
    "from"
    "past"
    "with"
    "before"
    "in"
    "round"
    "within"
    "behind"
    "inside"
    "since"
    "without"
    "below"
    "into"
    "than"
    "beneath"
    "like"
    "through"
    "Since" "Within" "Underneath" "Beside" "Under" "Towards" "Of" "After" "Up" "Off" "Among" "Beyond" "Via" "Over" "By" "Like" "Onto" "About" "Without" "Than" "For" "Past" "Along" "Outside" "Despite" "Upon" "On" "Above" "Opposite" "Until" "Out" "Down" "Between" "Against" "From" "Inside" "Across" "Beneath" "With" "Around" "Through" "Behind" "During" "Unlike" "Before" "To" "Into" "Round" "Except" "As" "At" "Below" "Near" "In" "and" "And" "or" "Or"})




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




#_(defn index-forward [data operation ^String value id]
  (reduce (fn [data n]
            (operation data (subs value 0 n) id))
          data
          (range 1 (inc (count value)))))

#_(defn index-forward [data operation ^String value id]
  (reduce (fn [data n]
            (operation data (subs value 0 n) id))
          data
          (range 2 (inc (count value)))))

(defn index-forward [data operation ^String value id]
  (reduce (fn [data n]
            (operation data (subs value 0 n) id))
          data
          (range 3 (inc (count value)))))

(defn index-reverse [data operation ^String value id]
  (reduce (fn [data n]
            (operation data (subs value n) id))
          data
          (range (count value))))

(defn index-both [data operation ^String value id]
  (index-forward (index-reverse data operation value id) operation value id))

(defn index-full [data operation ^String value id]
  (reduce
   (fn [data from]
     (let [value (subs value from)]
       (reduce (fn [data n]
                 (operation data (subs value 0 n) id))
               data
               (range 1 (inc (count value))))))
   data
   (range (count value))))

(subs "gato" 0)

(range (count "gato"))


(defn get-indexer [indexing]
  (case indexing
    :forward index-forward
    :reverse index-reverse
    :both index-both
    :full index-full
    index-forward))






;;aca ya indexer y encoder quedan como funciones
(defn init [{:keys [tokenizer split indexer filter encoder] :as options}]
  (let [encoder (get-encoder encoder)]
    (assoc (merge {:ids {} :data {}} options)
           :indexer (get-indexer indexer)
           :encoder encoder
           :tokenizer (if (fn? tokenizer) tokenizer #(string/split % (or split #"\W+")));;con este splitter/tokenizador dividimos en todas las separaciones como puntos, espacios, guiones o barras
           :filter (set (mapv encoder filter)))));;esto es para procesar el input que ponemos en filter, al igual que con el input search

(def flex-f (init {:tokenizer false :split #"\W+" :indexer :forward :filter #{"and" "or"} :encoder :advanced}))
(def flex-fp (init {:tokenizer false :split #"\W+" :indexer :forward :filter prepositions :encoder :advanced}))
(def flex-r (init {:tokenizer false :split #"\W+" :indexer :reverse :filter #{"and" "or"} :encoder :advanced}))
(def flex-b (init {:tokenizer false :split #"\W+" :indexer :both :filter #{"and" "or"} :encoder :advanced}))
(def flex-u (init {:tokenizer false :split #"\W+" :indexer :full :filter #{"and" "or"} :encoder :advanced}))
(def flex-us (init {:tokenizer false :split #"\W+" :indexer :full :filter #{"and" "or"} :encoder :advanced :stemmer advanced-regex}))
(def flex-up (init {:tokenizer false :split #"\W+" :indexer :full :filter prepositions :encoder :advanced}))
(def flex-usp (init {:tokenizer false :split #"\W+" :indexer :full :filter prepositions :encoder :advanced :stemmer advanced-regex}))



(update {} "valor" (fn [a] 4))



(defn add-index [data ^String value id]
  #_(assoc data value (conj (or (get data value) #{}) id))
  (update data value #(conj (or % #{}) id)))

(defn remove-index [data ^String value id]
#_(assoc data value (disj (or (get data value) #{}) id))
(update data value #(disj (or % #{}) id)))


;;ACA ES DONDE SE APLICA REPETIDAMENTE SI EL INGRESO TENIA MAS DE UNA PALABRA
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
#_(sort (:data (time (reduce ffaa flex-u pares))))
#_(sort (:data (time (reduce ffaa flex-up pares))))
#_(sort (:data (time (reduce ffaa flex-usp pares))));;4100
#_(sort (:data (time (reduce ffaa flex-f pares))));;1100
#_(sort (:data (time (reduce ffaa flex-fp pares))));;1100

#_(defn run-myfunc []
  (let [starttime (System/nanoTime)]
    (add-indexes flex-usp index-full ["" "justice" "al"] 1)
    (/ (- (System/nanoTime) starttime) 1e9)))


#_(/ (reduce + (vec (repeatedly 1000000 run-myfunc))) 1000000)


#_(#(string/split % #"\W+") ".and justice for al")
#_(filter-words ["" "and" "justice" "for" "al"] prepositions)



#_(add-indexes flex-usp index-full ["" "justice" "al"] 1)

#_(defn flex-add
  [{:keys [ids tokenizer indexer] :as flex} id content]
  (let [l-c (string/lower-case content)
        normalized (normalize l-c)
        splitted (tokenizer normalized)
        filtered (filter-words splitted prepositions)
        regexed (pmap #(replace-regexes % advanced-regex) filtered)
        words (set (pmap #(collapse-repeating-chars %) regexed))]
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
;;agregamos el replace-regexes para acortar cada una de las palabras y cambio de orden.
#_(sort (:data (time (reduce ffaa flex-u pares))));;5000
#_(sort (:data (time (reduce ffaa flex-up pares))));;4900
#_(sort (:data (time (reduce ffaa flex-usp pares))));;4700
#_(sort (:data (time (reduce ffaa flex-f pares))));;2000
#_(sort (:data (time (reduce ffaa flex-fp pares))));;2000

#_(defn flex-add
  [{:keys [ids tokenizer indexer] :as flex} id content]
  (let [words (pmap #(collapse-repeating-chars (replace-regexes % advanced-regex))
                    (filter-words (tokenizer (normalize (string/lower-case content)))
                                  prepositions))]
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
;;version "mejorada" de la anterior con un solo mapeo en vez de dos.
#_(sort (:data (time (reduce ffaa flex-u pares))));;4400
#_(sort (:data (time (reduce ffaa flex-up pares))));;4400
#_(sort (:data (time (reduce ffaa flex-usp pares))));;4500
#_(sort (:data (time (reduce ffaa flex-f pares))));;1800
#_(sort (:data (time (reduce ffaa flex-fp pares))));;1800

#_(defn flex-add
  [{:keys [ids tokenizer indexer filter] :as flex} id content]
  (let [content (encode-value content flex)
        words (tokenizer content)]
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
;;sacamos filter-words
#_(sort (:data (time (reduce ffaa flex-u pares))));;3800
#_(sort (:data (time (reduce ffaa flex-up pares))));;3900
#_(sort (:data (time (reduce ffaa flex-usp pares))));;3800
#_(sort (:data (time (reduce ffaa flex-f pares))));;900
#_(sort (:data (time (reduce ffaa flex-fp pares))));;1100
#_(System/gc)

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
;;cuando hay mas de una palabra en words se van a generar una coleccion (set de ids en los que se encuentra esa palabra)
;;por cada palabra que haya,
;;dichas colecciones son set con los id de donde aparece ese texto, 
;;cuando tenemos mas de 2 palabras por lo tanto, tenemos que buscar los id en los que aparezcan las 2 simultaneamente,
;;es por eso que pedimos la interseccion de dichos sets que son el resultado de la funcion



(def data2 ["$1,000 a Touchdown"
            "...And Justice for All"])
(def data3 ["$ aka Dollars"
            "$1,000 a Touchdown"
            "$10 Raise"])
(def corto (map vector (range (count data2)) data2))
(def triple (map vector (range (count data3)) data3))
(def pares (map vector (range (count fd/data)) fd/data))

(defn ffaa [flex [nro film]]
  (flex-add flex nro film))

#_(sort (:data (time (reduce ffaa flex-u pares))))
#_(sort (:data (time (reduce ffaa flex-up pares))))
#_(sort (:data (time (reduce ffaa flex-usp pares))))
#_(sort (:data (time (reduce ffaa flex-f pares))))
#_(sort (:data (time (reduce ffaa flex-fp pares))))


#_(sort (:data (time (reduce ffaa flex-usp triple))))

(def indice-corto (reduce ffaa flex-fp corto))
(def indice-triple (reduce ffaa flex-fp triple))
(def indice (reduce ffaa flex-fp pares))

#_(flex-search indice-corto "to");;"falla" porque es una de la prepositions
#_(flex-search indice-triple "ai");;da nil cuando no encuentra
#_(flex-search indice "justice a")





;;con o sin advanced-regex
(def sin (set '(["0" #{0}] ["1" #{0}] ["a" #{0}] ["c" #{0}] ["ch" #{0}] ["chd" #{0}] ["chdo" #{0}] ["chdow" #{0}] ["chdown" #{0}] ["d" #{0}] ["do" #{0}] ["dow" #{0}] ["down" #{0}] ["h" #{0}] ["hd" #{0}] ["hdo" #{0}] ["hdow" #{0}] ["hdown" #{0}] ["n" #{0}] ["o" #{0}] ["ou" #{0}] ["ouc" #{0}] ["ouch" #{0}] ["ouchd" #{0}] ["ouchdo" #{0}] ["ouchdow" #{0}] ["ouchdown" #{0}] ["ow" #{0}] ["own" #{0}] ["t" #{0}] ["to" #{0}] ["tou" #{0}] ["touc" #{0}] ["touch" #{0}] ["touchd" #{0}] ["touchdo" #{0}] ["touchdow" #{0}] ["touchdown" #{0}] ["u" #{0}] ["uc" #{0}] ["uch" #{0}] ["uchd" #{0}] ["uchdo" #{0}] ["uchdow" #{0}] ["uchdown" #{0}] ["w" #{0}] ["wn" #{0}])))
(def con (set '(["0" #{0}] ["1" #{0}] ["a" #{0}] ["c" #{0}] ["ch" #{0}] ["chd" #{0}] ["chdo" #{0}] ["chdow" #{0}] ["chdown" #{0}] ["d" #{0}] ["do" #{0}] ["dow" #{0}] ["down" #{0}] ["h" #{0}] ["hd" #{0}] ["hdo" #{0}] ["hdow" #{0}] ["hdown" #{0}] ["n" #{0}] ["o" #{0}] ["oc" #{0}] ["och" #{0}] ["ochd" #{0}] ["ochdo" #{0}] ["ochdow" #{0}] ["ochdown" #{0}] ["ow" #{0}] ["own" #{0}] ["t" #{0}] ["to" #{0}] ["toc" #{0}] ["toch" #{0}] ["tochd" #{0}] ["tochdo" #{0}] ["tochdow" #{0}] ["tochdown" #{0}] ["w" #{0}] ["wn" #{0}])))
(sets/difference sin con)

;;aplico advanced-regex antes de indexar
;;achico las palabras pero sigo indexando la totalidad de los casos
(defn rr [[nro film]]
  [nro (replace-regexes film advanced-regex)])
(def pares-regex (mapv rr pares))
#_(prn (sort (:data (time (reduce ffaa flex-u pares-regex)))));;4300
































(comment
  (time (let [flex (init {:indexer :full :encoder :advanced})
              flex (reduce (fn [flex [k v]] (flex-add flex k v)) flex (map vector (range) #_data
                                                                           ["TAL" "DOLBER"] ))]
          (get (:data flex) "abs")
          #_(flex-search flex "and jus"))))