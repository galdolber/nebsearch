(ns flexsearch.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.set :as sets]
            [me.tonsky.persistent-sorted-set :as pss]))

(set! *warn-on-reflection* true)

(defn collapse-repeating-chars [string]
  (apply str (dedupe string)))

(defn ^String normalize [^String str]
  (let [^String normalized (java.text.Normalizer/normalize str java.text.Normalizer$Form/NFD)]
    (clojure.string/replace normalized #"\p{InCombiningDiacriticalMarks}+" "")))
#_(defn ^string normalize-cljs [^string s] (.replace (.normalize s "NFD") #"[\u0300-\u036f]" ""))

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

(def prepositions
  #{"about" "beside" "near" "to" "above" "between" "of" "towards" "across" "beyond" "off" "under" "after" "by" "on" "underneath" "against" "despite" "onto" "unlike" "along" "down" "opposite" "until" "among" "during" "out" "up" "around" "except" "outside" "upon" "as" "for" "over" "via" "at" "from" "past" "with" "before" "in" "round" "within" "behind" "inside" "since" "without" "below" "into" "than" "beneath" "like" "through" "Since" "Within" "Underneath" "Beside" "Under" "Towards" "Of" "After" "Up" "Off" "Among" "Beyond" "Via" "Over" "By" "Like" "Onto" "About" "Without" "Than" "For" "Past" "Along" "Outside" "Despite" "Upon" "On" "Above" "Opposite" "Until" "Out" "Down" "Between" "Against" "From" "Inside" "Across" "Beneath" "With" "Around" "Through" "Behind" "During" "Unlike" "Before" "To" "Into" "Round" "Except" "As" "At" "Below" "Near" "In" "and" "And" "or" "Or"})

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

(defn replace-regexes [^String str regexp]
  (reduce (fn [^String str [regex rep]]
            (if (keyword? regex)
              (.replaceAll str (name regex) rep)
              (string/replace str regex rep)))
          str
          regexp))

;; TODO new implementation?
(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn default-splitter [^String s]
  (string/split s #"[\W+]|[^A-Za-z0-9]"))


(defn init [{:keys [tokenizer filter] :as options}]
  (let [encoder (get-encoder (:encoder options))]
    (assoc (merge {:data (pss/sorted-set)} options)
           :encoder encoder
           :tokenizer (if (fn? tokenizer) tokenizer default-splitter)
           :filter (set (mapv encoder filter)))))

;; TODO support updates
;; ORIGINAL
(defn flex-add [{:keys [index data encoder] :as flex} pairs];;([0 "Rambo"][1 "Mision Imposible"]...)
  (loop [[[id w] & ws] pairs
         pos (count index)
         data data
         r []]
    (if w
      (let [w (encoder w)]
        (recur ws
               (+ pos (count w) 1)
               (conj data [pos id])
               (conj r w)))
      (assoc flex
             :index (str index (when-not (string/blank? index) "$") (string/join "$" r))
             :data data))))
;; REGEXES Y FILTER AGREGADOS:
(defn flex-add [{:keys [index data encoder filter] :as flex} pairs]
  (loop [[[id w] & ws] pairs
         pos (count index)
         data data
         r []]
    (if w
      (let [w (encoder w)]
        (recur ws
               (+ pos (count w) 1)
               (conj data [pos id])
               (conj r w)))
      (assoc flex
             :index (let [r (map (fn [w] (replace-regexes w advanced-regex)) r)
                          r (if filter (filter-words r filter) r)]
                         (str index
                          (when-not (string/blank? index) "$")
                          (string/join "$" r)))
             :data data))))

(defn indexes-of [v]
  (->> v
       (map-indexed vector)
       (clojure.set/map-invert)))

;;a lo mejor conviene deja el vector del par en data cuando se remueve como [nil nil].

(defn flex-remove [{:keys [data index] :as flex} id]
  (let [pair (flatten (filter (fn [[pos n]] (= n id)) data))
        name (get (vec data)
                  (inc (get (indexes-of data) pair)))]
    (assoc flex
           :data (map (fn [[pos n]] (if (< id n)
                                      [(- pos (inc (count name))) n]
                                      [pos n]))
                      (remove (fn [[pos x]] (= id x)) data))
           :index (str (subs index 0 (dec (first pair)))
                       (subs index (+ (first pair) (count name)))))))


;;pense 2 estrategias...o hacemos remove-add con lo cual cambiamos la posicion del id de su lugar al final
;;o modificamos el str agregando o sacando caracteres segun corresponda y sumando la diferencia a las posiciones
(defn flex-update [flex id value]
  (let [flex (flex-remove flex id)]
    (flex-add flex [[id value]])))

(defn find-positions [text search]
  (let [search-len (count search)]
    (loop [from 0
           r []]
      (if-let [i (string/index-of text search from)]
        (recur (+ (int i) search-len)
               (conj r i))
        r))))

(defn flex-search [{:keys [index data tokenizer filter encoder]} search]
  (when (and search data)
    (let [search (encoder search)
          words (tokenizer search)
          words (set (if filter (filter-words words filter) words))]
      (apply sets/intersection
             (mapv #(set (mapv (fn [i]
                                 (last (first (pss/rslice data [(inc i) nil] [-1 nil]))))
                               (find-positions index %)))
                   words)))))


(defn -main [& args]
  (let [sample-data (read-string (slurp "data.edn"))
        data (into {} (map vector (range) sample-data))
        _ (read-line)
        flex (init {:encoder :simple})
        flex (time (flex-add flex data))]
    (read-line)
    (mapv sample-data (time (flex-search flex "gal lade")))
    (read-line)))
