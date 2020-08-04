(ns flexserach.core
  (:require [clojure.string :as str]))

;;AGREGAR LOS WHEN CUANDO HAGAN FALTA Y SUS TESTS

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


(defn sort-by-length-down [a b] (cond
                                  (> (count a) (count b)) -1
                                  (< (count a) (count b)) 1
                                  :else 0));;corregida, funcionaba mal

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

;;regexs pasadas de mapa a vector
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

(defn replace-regexes [str regexp]
  (reduce (fn [str [regex rep]]
            (str/replace str regex rep))
          str
          regexp))

(defn global-encoder-icase [value]
  (str/lower-case value))

(defn global-encoder-simple [value]
  (when value
    (let [s (replace-regexes (str/lower-case value) simple-regex)]
      (if (str/blank? s) "" s))))

(defn global-encoder-advanced [string skip]
  (if-not string
    string
    (let [string (global-encoder-simple string)]
      (cond (< 2 (count string)) (if (and (not skip) (< 1 (count string)))
                                   (collapse-repeating-chars (replace-regexes string advanced-regex))
                                   (replace-regexes string advanced-regex))
            (not skip) (if (< 1 (count string))
                         (collapse-repeating-chars string)
                         string)
            :else string))))

(defn global-encoder-extra [string]
  (if-not string
    string
    (let [string (global-encoder-advanced string true)]
      (cond (< 1 (count string))
            (collapse-repeating-chars
             (str/join " "
                       (loop [string (str/split string #" ")
                              current (get string 0)
                              c 0]
                         (println string current c)
                         (cond (= c (count string)) string
                               :else (recur (if (< 1 (count current))
                                              (assoc string c
                                                     (str (first current)
                                                          (replace-regexes
                                                           (subs current 1)
                                                           extra-regex)))
                                              string)
                                            (get string (inc c))
                                            (inc c))))))
            :else string))))

(defn global-encoder-balance [string]
  (when string
    (collapse-repeating-chars (replace-regexes (str/lower-case string) balance-regex))))

(def global-encoder
  {:icase global-encoder-icase
   :simple global-encoder-simple
   :advanced global-encoder-advanced
   :extra global-encoder-extra
   :balance global-encoder-balance})

(defn create-page [cursor page result];;FALTAN LOS TESTS, NO SE LOS INPUTS
  ;; TODO str page?
  (if cursor
    {:page cursor, :next (if page (str page) nil), :result result}
    result))

(defn add-index [map dupes value id partial-score context-score threshold resolution];;FALTAN LOS TESTS, NO SE LOS INPUTS
  (when (aget dupes value) (aget dupes value))
  (let [score (if partial-score
                (+
                 (* (- resolution (or threshold (/ resolution 1.5))) context-score)
                 (* (or threshold (/ resolution 1.5)) partial-score))
                context-score)]
    (aset dupes value score)
    (when (>= score threshold)
      ;; TODO?
      (let [arr (aget map (- resolution (bit-shift-right #_>> (+ score 0.5) 0)))];;LE DEJAS EL SHIFT COMENTADO POR ALGO?
        (set! arr (or (aget arr value) (aset arr value #js [])))
        (aset arr (.-length arr) id)))
    score))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


