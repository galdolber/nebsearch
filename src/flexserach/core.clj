(ns flexserach.core
  (:require [clojure.string :as str]))

(def defaults
  {:encode "icase"
   :tokenize "forward"
   :split #"\W+"
   :cache false
   :async false
   :worker false
   :rtl false
   :doc false
   :resolution 9
   :threshold 0
   :depth 0})

(def presets
  {:memory {:encode "extra" :tokenize "strict" :threshold 0 :resolution 1}
   :speed {:encode "icase" :tokenize "strict" :threshold 1 :resolution 3 :depth 2}
   :match {:encode "extra" :tokenize "full" :threshold 1 :resolution 3}
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
      (if (< 1 (count string))
        (collapse-repeating-chars
         (str/join " "
                   (loop [string (str/split string #" ")
                          current (get string 0)
                          c 0]
                     (println string current c)
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
  (if (get dupes value)
    (get dupes value)
    (let [score (if partial-score
                  (+ (* (resolution (or threshold (/ resolution 1.5))) context-score)
                     (* (or (threshold (/ resolution 1.5))) partial-score))
                  context-score)
          dupes (assoc dupes value score)
          arr (get map (- resolution (bit-shift-right (+ score 0.5) 0)))
          arr (or (get arr value) (assoc arr value []))
          arr (assoc arr (count arr) id)]
      {:score score :dupes dupes :arr arr})))

(defn encode [name value];;FALTAN LOS TESTS, NO SE LOS INPUTS
  ((name global-encoder) value))

(defn filter-words [words fn-or-map];;FALTAN LOS TESTS, NO SE LOS INPUTS
  (let [lenght (count words)
        has-function (fn? fn-or-map)]
    (loop [word (get words 0)
           filtered []
           c 0]
      (if (= c lenght) filtered
          (recur (get words (inc c))
                 (if (or (and has-function (fn-or-map word))
                         (and (not has-function) (not (get fn-or-map word))))
                   (conj filtered word)
                   filtered)
                 (inc c))))))

(defn build-dupes [{:keys [resolution threshold]}]
  (vec (repeat (- resolution (or threshold 0)) {})))

(defn init [options]
  (let [{:keys [threshold resolution] :as options}
        (-> defaults (merge options) (merge (presets (:preset options))))
        options (update options :resolution #(if (<= % threshold) (inc threshold) %))
        {:keys [encoder resolution] :as options}
        (update options :encoder #(or (global-encoder %) %))
        options (update options :filterer #(when % (set (mapv encoder %))))]
    (into
     options
     {:fmap (build-dupes options)
      :ctx {}
      :id {}
      :timer 0})))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn remove-index [map id]
  (loop [coll (vec map)
         ret []
         c 0]
    (let [key (first (first coll))
          value (second (first coll))]
      (if (= c (count map)) (if (empty? ret) nil
                                (apply assoc {} (apply concat ret)))
          (recur
           (vec (rest coll))
           (if (and (= 1 (count value)) (= id (first value))) ret
               (loop [vals value
                      rett []
                      cc 0]
                 (if (= cc (count value)) (conj ret [key rett])
                     (recur
                      (rest vals)
                      (cond (map? (first vals)) (conj rett (remove-index (first vals) id))
                            (= id (first vals)) rett
                            (not= id (first vals)) (conj rett (first vals)))
                      (inc cc)))))
           (inc c))))))

;;ACA VIENEN:
;;REMOVE-FLEX
;;UPDATE-FELX
;;FORWARD-TOKENIZER
;;REVERSE-TOKENIZER
;;FULL-TOKENIZER
;;DEFAULT-TOKENIZER
;;ADD-FLEX

;;ESTAS PERTENECEN A INTERSECT:

(defn limit-true [result pointer limit cursor]
  (let [length (count result)]
    (if (and pointer (< length pointer))
      (let [pointer 0
            start (or pointer 0)
            page (+ start limit)]
        (if (< page length)
          (let [result (vec (drop (dec start) (take page result)))]
            (create-page cursor page result))
          (create-page cursor page result)))
      (let [start (or pointer 0)
            page (+ start limit)]
        (if (< page length)
          (let [result (vec (drop (dec start) (take page result)))]
            (create-page cursor page result))
          (let [page 0]
            (when start
              (let [result (vec (drop (dec start) result))]
                (create-page cursor page result)))))))))

(defn length-z-true [bool arrays pointer limit cursor page]
  (when (not bool)
    (let [result (first arrays)]
      (when pointer
        (let [pointer (.parseInt (first pointer))]
          (if limit
            (limit-true result pointer limit cursor)
            (create-page cursor page result)))))))

(defn for-init-true [result-length first-result has-not check-not check has-and result count]
  (loop [i 0
         id (get first-result i)
         index (str "@" id)
         check check
         result result
         count count]
    (if (= i result-length) {:check check
                             :result result
                             :count count}
        (recur (inc i) ;;i
               (get first-result (inc i)) ;;id
               (str "@" (get first-result (inc i))) ;;index
               (if (or (not has-not) (not (get check-not index))) ;;check
                 (assoc check index 1)
                 check)
               (if (and (or (not has-not) (not (get check-not index))) ;;result
                        (not has-and))
                 (assoc result count id)
                 result)
               (if (and (or (not has-not) (not (get check-not index))) ;;count
                        (not has-and))
                 (inc count)
                 count)))))

(defn init-true [first-result has-not check-not check has-and result arr count]
  (if first-result
    (let [result-length (count first-result)]
      (merge {:first-result nil :init false}
             (for-init-true result-length first-result has-not check-not check has-and result count)))
    arr))

(defn for-first-result-true [i first-result result-length result check-not count]
  (loop [i i
         id (get first-result i)
         count count
         result result]
    (if (= i result-length) {:count count
                             :result result}
        (recur (inc i)
               (get first-result (inc i))
               (if (not (get check-not (str "@" id)))
                 (inc count)
                 count)
               (if (not (get check-not (str "@" id)))
                 (assoc result count id)
                 result)))))

(defn first-result-true [first-result has-not pointer result check-not] 
  (let [result-length (count first-result)]
    (if has-not
      (if pointer
        (for-first-result-true (.parseInt pointer) first-result result-length result check-not pointer)
        (for-first-result-true 0 first-result result-length result check-not pointer))
      first-result)))

(defn length-z>1 [];;SIN TERMINAR
  (let [check {}
        suggestions []
        z 0
        i 0
        init true
        count 0]
    (when pointer
      (when (= 2 (count pointer))
        (let [pointer-suggest pointer
              pointer false]
          (if has-not
            (let [check-not (loop [z z
                                   ret {}]
                              (if (= (get bool z) "not")
                                (if (= z (count length-z)) ret
                                    (recur (inc z)
                                           (loop [i 0
                                                  ret {}]
                                             (if (= i (count (get arrays z))) ret
                                                 (recur (inc i)
                                                        (assoc ret (str "@" (get (get arrays z) i)) 1))))))
                                nil))
                  last-index (loop [z z
                                    ret nil]
                               (if (not (= (get bool z) "not"))
                                 (if (= z (count length-z)) ret
                                     (recur (inc z)
                                            (inc z)))
                                 nil))]
              (when (= last-index nil)
                (do (create-page cursor page result)
                    (let [z 0]))))
            (let [bool-main (and (string? bool) bool)]
              ())))
        (let [pointer (.parseInt (first pointer))
              pointer-count pointer]
          (if has-not
            (let [check-not {}])
            (let [bool-main (and (string? bool) bool)]
              (when)))))
      ())))

(defn intersect [arrays limit cursor suggest bool has-and has-not];;SIN TERMINAR
  (let [result []]
    (if (= true cursor)
      (let [cursor 0
            pointer ""
            length-z (count arrays)]
        (if (< 1 length-z)
          ()
          ()))
      (let [pointer (and cursor (str/split cursor #":"))
            length-z (count arrays)]
        (if (< 1 length-z)
          ()
          ())))))


#_(INTERSECT
   (LENGTH-Z > 1
             (FOR
              INIT
              INNER-FOR)
             (FIRST-RESULT))
   (LENGTH-Z)
   (LIMIT))