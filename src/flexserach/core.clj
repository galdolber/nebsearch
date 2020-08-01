(ns flexserach.core
  (:require [clojure.string :as string]))

(def defaults
  {:encode "icase"
   :tokenize "forward"
   :split #"\W+"
   :cache false
   :async false
   :worker false
   :rtl false
   :resolution 9
   :threshold 0
   :depth 0})

(def presets
  {:memory {:encode "extra" :tokenize "strict" :threshold 0 :resolution 1}
   :speed {:encode "icase" :tokenize "strict" :threshold 1 :resolution 3 :depth 2}
   :match  {:encode "extra" :tokenize "full" :threshold 1 :resolution 3}
   :score {:encode "extra" :tokenize "strict" :threshold 1 :resolution 9 :depth 4}
   :balance {:encode "balance" :tokenize "strict" :threshold 0 :resolution 3 :depth 3}
   :fast {:encode "icase" :tokenize "strict" :threshold 8 :resolution 9 :depth 1}})

(defn sort-by-length-down [a b]
  (let [diff (- (count a) (count b))]
    (if (< diff 0) 1 (if diff (- 1) 0))))

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


(defn replace- [str regexp]
  (reduce (fn [str [regex rep]] (string/replace str regex rep))
          str
          regexp))

(def simple-regex
  {#"\\s+" " "
   #"[^a-z0-9 ]" ""
   #"[-/]" " "
   #"[àáâãäå]" "a"
   #"[èéêë]" "e"
   #"[ìíîï]" "i"
   #"[òóôõöő]" "o"
   #"[ùúûüű]" "u"
   #"[ýŷÿ]" "y"
   #"ñ" "n"
   #"[çc]" "c"
   #"ß" "s"
   #" & " " and "})

(def advanced-regex
  {#"ae" "a"
   #"ai" "ei"
   #"ay" "ei"
   #"ey" "ei"
   #"oe" "o"
   #"ue" "u"
   #"ie" "i"
   #"sz" "s"
   #"zs" "s"
   #"ck" "k"
   #"cc" "k"
   #"sh" "s"
   #"th" "t"
   #"dt" "t"
   #"ph" "f"
   #"pf" "f"
   #"ou" "o"
   #"uo" "u"})

(def extra-regex
  {#"p" "b"
   #"z" "s"
   #"[cgq]" "k"
   #"n" "m"
   #"d" "t"
   #"[vw]" "f"
   #"[aeiouy]" ""})


(defn global-encoder-icase [value]
  (string/lower-case value))

(defn global-encoder-simple [value]
  (when value
    (let [s (replace- (string/lower-case value) simple-regex)]
      (if (string/blank? s) "" s))))

(defn global-encoder-advanced-raw [value]
  (when value
    (let [value (global-encoder-simple value)]
      (if (> (count value) 2)
        (let [s (replace- (string/lower-case value) advanced-regex)]
          (if (string/blank? s) "" s))
        value))))

(defn global-encoder-advanced [value]
  (when value
    (let [value (global-encoder-advanced-raw value)]
      (if (> (count value) 1)
        (collapse-repeating-chars value)
        value))))
