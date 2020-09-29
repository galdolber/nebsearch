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
(def flex-s (init {:encoder :simple}))


;; TODO support updates
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

(def pelis ["$ aka Dollars"
            "$1,000 a Touchdown"
            "$10 Raise"
            "$10,000 Under a Pillow"
            "$50,000 Reward"])

(def pares (map vector (range) pelis))

(def entero (flex-add flex-s pares))



#_{:data #{[0 0] [14 1] [33 2] [43 3] [66 4]}
 :encoder encoder-simple
 :tokenizer default-splitter
 :filter #{}
 :index "$ aka dollars$$1,000 a touchdown$$10 raise$$10,000 under a pillow$$50,000 reward"}


(subs "$ aka dollars$$1,000 a touchdown$$10 raise$$10,000 under a pillow" (+ 14 18))


;; TODO
(defn flex-remove [{:keys [data index] :as flex} id]
  (assoc flex
         :data (apply pss/sorted-set (remove (fn [[pos x]] (= id x)) data))
         :index (let [pair (flatten (filter (fn [[pos n]] (= n id)) data))]
                  (str (subs index 0 (dec (first pair)))
                       (subs index
                             (+ (first pair)
                                (count
                                 (apply second
                                        (filter (fn [[x name]] (= x id))
                                                (map vector
                                                     (range)
                                                     (read-string (slurp "data.edn"))))))))))))




(defn find-positions [text search]
  (let [search-len (count search)]
    (loop [from 0
           r []]
      (if-let [i (string/index-of text search from)]
        (recur (+ (int i) search-len) (conj r i))
        r))))

(defn flex-search [{:keys [index data tokenizer filter encoder]} search]
  (when (and search data)
    (let [search (encoder search)
          words (tokenizer search)
          words (set (if filter (filter-words words filter) words))]
      (apply sets/intersection
             (mapv #(set (mapv (fn [i]
                                 (last (first (pss/rslice data [(inc i) nil] [-1 nil]))))
                               (find-positions index %))) words)))))

(defn -main [& args]
  (let [sample-data (read-string (slurp "data.edn"))
        data (into {} (map vector (range) sample-data))
        _ (read-line)
        flex (init {:encoder :simple})
        flex (time (flex-add flex data))]
    (read-line)
    (mapv sample-data (time (flex-search flex "gal lade")))
    (read-line)))


(def pares (map vector (range) (read-string (slurp "data.edn"))))

(time (flex-add (init {:encoder :simple})
                (map vector (range) (read-string (slurp "data.edn")))))


(map vector (range) ["$ aka Dollars"
                     "$1,000 a Touchdown"
                     "$10 Raise"
                     "$10,000 Under a Pillow"
                     "$50,000 Reward"]))

'([0 "$ aka Dollars"]
  [1 "$1,000 a Touchdown"]
  [2 "$10 Raise"]
  [3 "$10,000 Under a Pillow"]
  [4 "$50,000 Reward"]
  [5 "(500) Days of Summer"])

{:encoder
 :tokenizer
 :filter
 :data #{[0 0] [14 1] [33 2] [43 3] [66 4]}
 :index "$ aka dollars$$1,000 a touchdown$$10 raise$"}
