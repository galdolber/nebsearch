(ns flexsearch.core
  (:require [clojure.string :as str]))

(def whitespaces #"\W+")

(def defaults
  {:encode "icase"
   :tokenize "forward"
   :cache false
   :async false
   :worker false
   :rtl false
   :doc false
   :resolution 9
   :threshold 0
   :depth 0
   :split whitespaces})

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

(defn create-page [cursor page result]
  ;; TODO str page?
  (if cursor
    {:page cursor, :next (if page (str page) nil), :result result}
    result))

(defn add-index [map dupes value id partial-score context-score threshold resolution]
  (if (get dupes value)
    (get dupes value)
    (let [score (if partial-score
                  (+ (* (- resolution (or threshold (/ resolution 1.5))) context-score)
                     (* (or threshold (/ resolution 1.5)) partial-score))
                  context-score)
          dupes (assoc dupes value score)
          arr (get map (- resolution (bit-shift-right (+ score 0.5) 0)));;NO FUNCIONA CON DOUBLES
          arr (or (get arr value) (assoc arr value []))
          arr (assoc arr (count arr) id)]
      {:score score
       :dupes dupes
       :arr (if (<= threshold score) arr nil)})))

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
                 (cond (= cc (count value)) (conj ret [key rett])
                       (= id (first vals)) (conj ret [key (into rett (rest vals))])
                       :else (recur
                              (rest vals)
                              (cond (map? (first vals)) (conj rett (remove-index (first vals) id))
                                    (= id (first vals)) rett
                                    (not= id (first vals)) (conj rett (first vals)))
                              (inc cc)))))
           (inc c))))))

(defn encode [{:keys [encoder stemmer -matcher] :as object} value];;NO FUNCIONA CON :ENCODER GLOBAL-ENCODER-ADVANCED PORQUE TIENE 2 ARGUMENTOS
  (let [global-matcher []]
    (when value
      (cond
        stemmer (replace-regexes (if encoder
                                   (encoder (if (count -matcher)
                                              (replace-regexes (if (count global-matcher)
                                                                 (replace-regexes value
                                                                                  global-matcher)
                                                                 value) -matcher)
                                              value))
                                   value) stemmer)
        encoder (encoder (if (count -matcher)
                           (replace-regexes (if (count global-matcher)
                                              (replace-regexes value
                                                               global-matcher)
                                              value) -matcher)
                           value))
        (count -matcher) (replace-regexes (if (count global-matcher)
                                            (replace-regexes value
                                                             global-matcher)
                                            value) -matcher)
        (count global-matcher) (replace-regexes value
                                                global-matcher)
        :else "error"))))

(defn filter-words [words fn-or-map];;FALTAN LOS TESTS, NO SE LOS INPUTS
  (let [length (count words)
        has-function (fn? fn-or-map)]
    (loop [i 0
           word (get words 0)
           filtered []
           count 0]
      (if (= i length) {:filtered filtered
                        :count count}
          (recur (inc i)
                 (get words (inc i))
                 (if (or (and has-function (fn-or-map word))
                         (and (not has-function) (not (get fn-or-map word))))
                   (assoc filtered count word)
                   filtered)
                 (if (or (and has-function (fn-or-map word))
                         (and (not has-function) (not (get fn-or-map word))))
                   (inc count)
                   count))))))

(defn build-dupes [{:keys [resolution threshold]}]
  (vec (repeat (- resolution (or threshold 0)) {})))

(defn init [options]
  (let [{:keys [threshold] :as options} (-> defaults
                                            (merge options)
                                            (merge (presets (:preset options))))
        options (update options
                        :resolution
                        #(if (<= % threshold) (inc threshold) %))
        {:keys [encoder] :as options} (update options
                                              :encoder
                                              #(or (global-encoder %) %))
        options (update options
                        :filterer
                        #(when % (set (mapv encoder %))))]
    (into
     options
     {:fmap (build-dupes options)
      :ctx {}
      :id {}
      :timer 0})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn for-remove-flex [resolution threshold -map id]
  (loop [z 0
         -map -map
         ret []]
    (if (= z (- resolution (or threshold 0))) ret
        (recur (inc z)
               (rest -map)
               (conj ret (remove-index (first -map) id))))))

(defn remove-flex [{:keys [-ids depth -map resolution threshold -ctx] :as flex}
                   id callback -recall]
  (let [index (str "@" id)]
    (if (-ids index)
      (if (and (not -recall)
               callback)
        (callback (remove-flex flex id nil true));;??? como hago con callback que se llama sin ningun argumento?. creo mque asi esta bien ya que la estaria llamando sobre el flex.. pero porque no figura como this en el js????
        (assoc (if depth
                 (assoc flex
                        :-ctx (remove-index -ctx id)
                        :-map (for-remove-flex resolution threshold -map id))
                 (assoc flex
                        :-map (for-remove-flex resolution threshold -map id)))
               :-ids (dissoc -ids index)))
      flex)));;ver si hace falta devolver algun otro objeto modificado junto a flex

(declare add)
(defn update-flex [{:keys [-ids] :as flex}
                   id content callback -recall];;callback es de support-callback?
  (let [index (str "@" id)]
    (if (and (-ids index) (string? content))
      (add (remove-flex flex id callback -recall) id content callback true nil)
      flex)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn reverse-t [length value -map dupes id rtl context-score threshold resolution]
  (loop [a length
         b (dec a)
         token (str (get value b) "")
         dupes dupes
         ret {}]
    (if (= 0 a) {:token token :score (ret :score) :dupes (ret :dupes) :arr (ret :arr)}
        (recur (dec a)
               (dec b)
               (str (get value (dec b)))
               ((add-index -map dupes token id (if rtl 1 (/ (- length b) length)) context-score threshold (dec resolution)) :dupes)
               (merge ret (add-index -map dupes token id (if rtl 1 (/ (- length b) length)) context-score threshold (dec resolution)))))))

(defn forward-t [value dupes length -map id rtl context-score threshold resolution]
  (loop [a 0
         token (get value a)
         dupes dupes
         ret {}]
    (if (= a length) (merge {:token token} ret)
        (recur (inc a)
               (str token (get value (inc a)))
               ((add-index -map dupes token id (if rtl (/ (+ (inc a) 1) length) 1) context-score threshold (dec resolution)) :dupes)
               (merge ret (add-index -map dupes token id (if rtl (/ (+ (inc a) 1) length) 1) context-score threshold (dec resolution)))))))

(defn for-full-t [x length value -map dupes id partial-score context-score threshold resolution]
  (loop [y length
         token (subs value x y)
         dupes dupes
         ret {}]
    (if (= y x) (merge {:token token} ret)
        (recur (dec y)
               (subs value x (dec y))
               ((add-index -map dupes token id partial-score context-score threshold (dec resolution)) :dupes)
               (merge ret (add-index -map dupes token id partial-score context-score threshold (dec resolution)))))))

(defn full-t [rtl length value -map dupes id context-score threshold resolution]
  (loop [x 0
         partial-score (/ (if rtl (inc x) (- length x)) length)
         ret {}]
    (if (= x length) (merge {:partial-score partial-score} ret)
        (recur (inc x)
               (/ (if rtl (inc (inc x)) (- length (inc x))) length)
               (merge ret (for-full-t x length value -map dupes id partial-score context-score threshold resolution))))))

(defn create-object-array [count]
  (vec (concat [] (repeat count {}))))


(defn default-t [flex
                 -map dupes value id context-score threshold resolution depth word-length i words]
  (let [result (add-index -map dupes value id 1 context-score threshold (dec resolution))
        score (or (result :score) result);;ver si en verdad necesito solo :score o es todo el mapa
        dupes (result :dupes)]
    (if (and depth (< 1 word-length) (<= threshold score))
      (let [ctxdupes (or (get-in dupes [:-ctx value])
                         (assoc-in dupes [:-ctx value] {}))
            ctxtmp (or (get-in flex [:-ctx value])
                       (assoc-in flex [:-ctx value] (vec (concat [] (repeat (- resolution (or threshold 0)) {})))))
            x (if (< (- i depth) 0) 0 (- i depth))
            y (if (< word-length (+ i depth 1)) word-length (+ i depth 1))
            a-i (loop [x x
                       dupes dupes
                       ret {}]
                  (if (= x y) ret
                      (recur (inc x)
                             (if (not= x i)
                               ((add-index ctxtmp ctxdupes (get words (inc x)) id 0 (- resolution (if (< (inc x) i) (- i (inc x)) (- (inc x) i))) threshold (dec resolution)) :dupes)
                               dupes)
                             (if (not= x i)
                               (merge ret (add-index ctxtmp ctxdupes (get words (inc x)) id 0 (- resolution (if (< (inc x) i) (- i (inc x)) (- (inc x) i))) threshold (dec resolution)))
                               ret))))]
        (merge flex result a-i))
      (merge flex result))))

(defn for-add [flex
               words rtl word-length tokenizer dupes -map id threshold resolution depth]
  (loop [i 0
         value (get words i)
         length (count value)
         context-score (/ (if rtl
                            (+ i 1)
                            (- word-length i))
                          word-length)
         token nil
         ret {}]
    (cond (and value
               (= i word-length)) (merge flex
                                         {:value value
                                          :length length
                                          :context-score context-score
                                          :token token};;como se si todos estos valores van en el flex o afuera?
                                         ret)
          (= i word-length) (merge flex {:value value})
          :else (recur (inc i);;i
                       (get words (inc i));;value
                       (if value;;length
                         (count (get words (inc i)))
                         length)
                       (if value;;context-score
                         (/ (if rtl
                              (inc (+ i 1))
                              (- word-length (inc i)))
                            word-length)
                         context-score)
                       (if value;;token
                         (case tokenizer
                           ("both" "reverse") ""
                           "forward" ((forward-t value dupes length -map id rtl context-score threshold resolution) :token)
                           "full" ((full-t rtl length value -map dupes id context-score threshold resolution) :token)
                           "default" token)
                         token)
                       (if value;;ret
                         (case tokenizer
                           ("both" "reverse") (merge ret (reverse-t length value -map dupes id rtl context-score threshold resolution))
                           "forward" (merge ret (forward-t value dupes length -map id rtl context-score threshold resolution))
                           "full" (merge ret (full-t rtl length value -map dupes id context-score threshold resolution))
                           "default" (merge ret (default-t flex -map dupes value id context-score threshold resolution depth word-length i words)))
                         ret)))))

(defn add [{:keys [-ids tokenize split filter threshold depth resolution -map rtl] :as flex}
           id content callback -skip-update -recall]
  (cond
    (and content
         (string? content)
         (or id (= id 0))
         (and (-ids (str "@" id)) (not -skip-update))) (update-flex flex id content nil nil)
    (and content
         (string? content)
         (or id (= id 0))
         (not -recall)
         callback) (callback (add flex id content nil -skip-update true));;ver como aplico callback, aridad y como son las funciones que ingreso a traves de el
    (and content
         (string? content)
         (or id (= id 0))) (let [content (encode flex content)]
                             (if (not (count content))
                               (assoc flex :content content)
                               (let [tokenizer tokenize
                                     words (if (fn? tokenizer)
                                             (tokenizer content)
                                             (str/split content split))
                                     words (if filter
                                             (filter-words words filter)
                                             words)
                                     dupes {:-ctx {}}
                                     word-length (count words)
                                     fff (for-add flex words rtl word-length tokenizer dupes -map id threshold resolution depth)
                                     fff (assoc-in fff [-ids (str "@" id)] 1)
                                     fff (merge fff {:tokenizer tokenizer
                                                     :words words
                                                     :word-length word-length})]
                                 fff)))
    :else flex))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn for-chico [z length arr has-and check has-not check-not is-final-loop
                 pointer-count result count limit cursor pointer found]
  (let [bool-or nil]
    (loop [i 0
           tmp (get arr i)
           index (str "@" tmp)
           check-val (if has-and (or (get check index) 0) z)
           check check
           found found
           count count
           pointer-count pointer-count
           result result]
      (cond (= i length) {:check check
                          :found found
                          :count count
                          :pointer-count pointer-count
                          :result result}
            (and (< 0 i)
                 check-val
                 (not (and has-not (get check-not index)))
                 (not (and (not has-and) (get check index)))
                 (= z check-val)
                 (or is-final-loop bool-or)
                 (or (not pointer-count) (< (dec pointer-count) count))
                 (and limit (= limit count))) {:return (create-page cursor (+ count (or pointer 0)) result)}
            :else (recur (inc i) ;;i
                         (get arr (inc i)) ;;tmp
                         (str "@" (get arr (inc i))) ;;index
                         (if has-and (or (get check (str "@" (get arr (inc i)))) 0) z) ;;check-val
                         (if (and check-val ;;check
                                  (not (and has-not (get check-not index)))
                                  (not (and (not has-and) (get check index)))
                                  (= z check-val)
                                  (not (or is-final-loop bool-or)))
                           (assoc check index (+ z 1))
                           check)
                         (if (and check-val ;;found
                                  (not (and has-not (get check-not index)))
                                  (not (and (not has-and) (get check index)))
                                  (= z check-val))
                           true
                           found)
                         (if (and check-val ;;count
                                  (not (and has-not (get check-not index)))
                                  (not (and (not has-and) (get check index)))
                                  (= z check-val)
                                  (or is-final-loop bool-or)
                                  (or (not pointer-count) (< (dec pointer-count) count)))
                           (inc count)
                           count)
                         (if (and check-val ;;pointer-count
                                  (not (and has-not (get check-not index)))
                                  (not (and (not has-and) (get check index)))
                                  (= z check-val)
                                  (or is-final-loop bool-or));;PREGUNTAR A GAL SI LA CONDICION TERMINA ACA O TENGO QUE INCLUIR EL SIGUIENTE OR EN EL QUE YA ESTA POINTER-COUNT
                           (dec pointer-count)
                           pointer-count)
                         (if (and check-val ;;result
                                  (not (and has-not (get check-not index)))
                                  (not (and (not has-and) (get check index)))
                                  (= z check-val)
                                  (or is-final-loop bool-or)
                                  (or (not pointer-count) (< (dec pointer-count) count)))
                           (assoc result count tmp)
                           result))))))

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
    {:first-result arr}))

(defn for-grande [z last-index length-z arrays cursor page init first-result has-not check-not
                  check result count pointer-count limit pointer]
  (let [bool-and true
        has-and true
        found false]
    (loop [z z
           is-final-loop (= z (- (or last-index length-z) 1))
           arr (get arrays z)
           length (count arr)
           first-result first-result
           init init
           check check
           result result
           count count
           found found
           for-c (for-chico z length arr has-and
                            (if (and init first-result)
                              ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :check)
                              check)
                            has-not check-not is-final-loop pointer-count
                            (if (and init first-result)
                              ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :result)
                              result)
                            (if (and init first-result)
                              ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :count)
                              count)
                            limit cursor pointer found)]
      (cond (or (= z length-z)
                (and (< 0 z) (and bool-and (not found)))) {:first-result first-result
                                                           :init init
                                                           :check check
                                                           :result result
                                                           :count count
                                                           :found found}
            (and (not length) bool-and) {:return (create-page cursor page arr)}
            (for-c :return) {:return (for-c :return)}
            :else (recur (inc z) ;;z
                         (= (inc z) (- (or last-index length-z) 1)) ;;is-final-loop
                         (get arrays (inc z)) ;;arr
                         (count (get arrays (inc z))) ;;length
                         (if (not length) ;;first-result
                           first-result
                           (if init
                             (if first-result
                               nil
                               arr)
                             first-result))
                         (if (not length) ;;init
                           init
                           (if (and init first-result)
                             false
                             init))
                         (if (not length) ;;check
                           check
                           (if (and init (not first-result))
                             check
                             ((for-chico z length arr has-and
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :check)
                                           check)
                                         has-not check-not is-final-loop pointer-count
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :result)
                                           result)
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :count)
                                           count)
                                         limit cursor pointer found) :check)))
                         (if (not length) ;;result
                           result
                           (if (and init (not first-result))
                             result
                             ((for-chico z length arr has-and
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :check)
                                           check)
                                         has-not check-not is-final-loop pointer-count
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :result)
                                           result)
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :count)
                                           count)
                                         limit cursor pointer found) :result)))
                         (if (not length) ;;count
                           count
                           (if (and init (not first-result))
                             count
                             ((for-chico z length arr has-and
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :check)
                                           check)
                                         has-not check-not is-final-loop pointer-count
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :result)
                                           result)
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :count)
                                           count)
                                         limit cursor pointer found) :count)))
                         (if (not length) ;;found
                           found
                           (if (and init (not first-result))
                             found
                             ((for-chico z length arr has-and
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :check)
                                           check)
                                         has-not check-not is-final-loop pointer-count
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :result)
                                           result)
                                         (if (and init first-result)
                                           ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :count)
                                           count)
                                         limit cursor pointer found) :found)))
                         (for-chico z length arr has-and
                                    (if (and init first-result)
                                      ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :check)
                                      check)
                                    has-not check-not is-final-loop pointer-count
                                    (if (and init first-result)
                                      ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :result)
                                      result)
                                    (if (and init first-result)
                                      ((for-init-true (count first-result) first-result has-not check-not check has-and result count) :count)
                                      count)
                                    limit cursor pointer found))))))

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
      {:result first-result})))

(defn length-z>1 [pointer last-index length-z arrays cursor page has-not check-not result
                  pointer-count limit]
  (let [check {}
        z 0
        init true
        count 0
        first-result nil]
    (if pointer
      (if (= 2 (count pointer))
        (let [pointer false
              for-g (for-grande z last-index length-z arrays cursor page init first-result
                                has-not check-not check result count pointer-count limit pointer)]
          (if (for-g :return)
            for-g
            (if first-result
              (merge for-g (first-result-true first-result has-not pointer result check-not) {:pointer pointer})
              for-g)))
        (let [pointer (.parseInt (first pointer))
              pointer-count pointer
              for-g (for-grande z last-index length-z arrays cursor page init first-result
                                has-not check-not check result count pointer-count limit pointer)]
          (if (for-g :return)
            for-g
            (if first-result
              (merge for-g (first-result-true first-result has-not pointer result check-not) {:pointer pointer})
              for-g))))
      (let [for-g (for-grande z last-index length-z arrays cursor page init first-result
                              has-not check-not check result count pointer-count limit pointer)]
        (if (for-g :return)
          for-g
          (if first-result
            (merge for-g (first-result-true first-result has-not pointer result check-not))
            for-g))))))

(defn intersect [arrays limit cursor bool has-not]
  (let [result []]
    (if (= true cursor)
      (let [cursor 0
            pointer ""
            length-z (count arrays)]
        (if (< 1 length-z)
          (let [lz (length-z>1 pointer nil length-z arrays cursor nil has-not nil result nil limit)]
            (if (lz :return)
              (lz :return)
              (if length-z
                (length-z-true bool arrays (lz :pointer) limit cursor nil)
                (if limit
                  (limit-true (lz :result) (lz :pointer) limit cursor)
                  (create-page cursor nil (lz :result))))))
          (if length-z
            (length-z-true bool arrays pointer limit cursor nil)
            (if limit
              (limit-true result pointer limit cursor)
              (create-page cursor nil result)))))
      (let [pointer (and cursor (str/split cursor #":"))
            length-z (count arrays)]
        (if (< 1 length-z)
          (let [lz (length-z>1 pointer nil length-z arrays cursor nil has-not nil result nil limit)]
            (if (lz :return)
              (lz :return)
              (if length-z
                (length-z-true bool arrays (lz :pointer) limit cursor nil)
                (if limit
                  (limit-true (lz :result) (lz :pointer) limit cursor)
                  (create-page cursor nil (lz :result))))))
          (if length-z
            (length-z-true bool arrays pointer limit cursor nil)
            (if limit
              (limit-true result pointer limit cursor)
              (create-page cursor nil result))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;SEARCH;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn for-search-inner [resolution threshold map-value map value]
  (loop [z 0
         map-check []
         count 0
         map-found false]
    (if (= z (- resolution threshold)) {:map-check map-check
                                        :count count
                                        :map-found map-found}
        (recur (inc z)
               (if (= map-value (and (get map z) (get (get map z) value)))
                 (assoc map-check count map-value)
                 map-check)
               (if (= map-value (and (get map z) (get (get map z) value)))
                 (inc count)
                 count)
               (if (= map-value (and (get map z) (get (get map z) value)))
                 true
                 map-found)))))

(defn for-search [{:keys [resolution threshold -map] :as flex}
                  words use-contextual ctx-map length]
  (loop [a 0
         value (get words a)
         ctx-root nil
         check-words {}
         map-check []
         map-found false
         countt 0
         map (if use-contextual (get ctx-map ctx-root) -map)
         check []]
    (cond (= a length) {:value value
                        :ctx-root ctx-root
                        :check-words check-words
                        :map-check map-check
                        :map-found map-found
                        :count countt
                        :map map
                        :check check}
          (and value
               use-contextual
               (not ctx-root)
               (not (get ctx-map value))) {:value value
                                           :return []}
          :else (recur
                 ;;a
                 (inc a)
                 ;;value
                 (get words (inc a))
                 ;;ctx-root
                 (if (and value
                          use-contextual
                          (not ctx-root)
                          (get ctx-map value))
                   value
                   (if (and value
                            use-contextual
                            (not ctx-root))
                     ctx-root
                     (if (and value
                              (not (get check-words value))
                              map-found)
                       value
                       ctx-root)))
                 ;;check-words
                 (if (and value
                          use-contextual
                          (not ctx-root)
                          (get ctx-map value))
                   (assoc check-words value 1)
                   (if (and value
                            use-contextual
                            (not ctx-root))
                     check-words
                     (if (and value
                              (not (get check-words value)))
                       (assoc check-words value 1)
                       check-words)))
                 ;;map-check
                 (if (and value
                          (not (get check-words value))
                          map)
                   (let [for-in (for-search-inner resolution threshold nil map value)]
                     (for-in :map-check))
                   map-check)
                 ;;map-found
                 (if (and value
                          (not (get check-words value))
                          map)
                   (let [for-in (for-search-inner resolution threshold nil map value)]
                     (for-in :map-found))
                   map-found)
                 ;;countt
                 (if (and value
                          (not (get check-words value)))
                   (let [for-in (for-search-inner resolution threshold nil map value)]
                     (for-in :countt))
                   countt)
                 ;;map
                 (if (and value
                          (not (get check-words value)))
                   (if use-contextual (get ctx-map ctx-root) (get this -map))
                   map)
                 ;;check
                 (if (and value
                          (not (get check-words value))
                          map-found)
                   (assoc check (countt check) (if (< 1 countt)
                                                 (concat map-check (concat [] map-check));;corroborar si esta bien
                                                 (get map-check 0)))
                   check)))))

(defn search [{:keys [threshold tokenize split filter depth -ctx] :as flex}
              query limit callback -recall]
  (let [callback (if (and limit (fn? limit))
                   limit
                   callback)
        limit (if (and limit (fn? limit))
                1000
                (or limit (= limit 0) (= limit 1000)))
        result []
        -query query
        threshold (or threshold 0)
        flex (merge flex {:limit limit
                          :threshold threshold})];;corroborar si esto y limit estan bien
    (if (and (not -recall)
             callback)
      (callback (search flex -query limit nil true));;callback deberia devolver flex....verificarlo
      (if (or (not query) (not (string? query)))
        (merge flex {:result result})
        (let [-query query;;esto me parece que hay que sacarlo... no tiene ningun sentido
              -query (encode flex -query);;ver si el -query va en value o en flex
              tokenizer tokenize
              words (if (fn? tokenizer)
                      (tokenizer -query)
                      (str/split -query split))
              words (if filter
                      (filter-words words filter)
                      words)
              length (count words)
              found true
              use-contextual (if (and (< 1 length) (and depth (= tokenize "strict")))
                               true
                               nil)
              words (if (and (< 1 length) (not (and depth (= tokenize "strict"))))
                      (reverse (sort words))
                      words)
              ctx-map nil]
          (if (not (count -query))
            (merge flex {:result result})
            (if (or (not use-contextual) (= ctx-map -ctx))
              (let [fs (for-search flex words use-contextual ctx-map length)]
                (if (fs :result)
                  (merge flex fs)
                  (if found
                    (merge flex fs {:result (intersect (fs :check) limit nil nil nil)})
                    (merge flex {:result result}))))
              (merge flex {:result result}))))))))

#_(INTERSECT
   (LENGTH-Z > 1
             (FOR
              INIT
              INNER-FOR)
             (FIRST-RESULT))
   (LENGTH-Z)
   (LIMIT))