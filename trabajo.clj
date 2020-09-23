(ns flexsearch.trabajo
  (:require [clojure.string :as str]))

;;AGREGAR LOS WHEN CUANDO HAGAN FALTA Y SUS TESTS

(def support-encoder true)
(def support-info true)
(def support-preset true)

;;TODAS ESTAS SON OPCIONES DE CONFIGURACION.
;;LOS DEFAULTS SON LOS QUE VIENEN POR DEFECTOS
;;LOS PRESETS SON "PERFILES" DETERMINADOS DE CONFIGURACION
(def defaults
  {:encode "icase"
   :tokenize "forward"
   :cache false
   :async false
   :worker false
   :rtl false ;;Right-to-left
   :doc false
   :resolution 9
   :threshold 0
   :depth 0
   :split #"\W+"})

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;INIT-ADD;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn encode [{:keys [encoder stemmer -matcher] :as object}
              value]
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
#_(encode {:encoder global-encoder-balance :stemmer {"ational" "ate"} :-matcher simple-regex} "dfsdd    dfational")

(defn filter-words [words fn-or-map];;vector con palabras y set con palabras a descartar
  (let [length (count words)
        has-function (fn? fn-or-map)]
    (loop [i 0
           word (get words 0)
           filtered []
          countt 0]
      (if (= i length) {:filtered filtered
                        :countt countt}
          (recur (inc i)
                 (get words (inc i))
                 (if (or (and has-function (fn-or-map word))
                         (and (not has-function) (not (get fn-or-map word))))
                   (assoc filtered countt word)
                   filtered)
                 (if (or (and has-function (fn-or-map word))
                         (and (not has-function) (not (get fn-or-map word))))
                   (inc countt)
                  countt))))))

(defn build-dupes [{:keys [resolution threshold]}];;complexity
  (vec (repeat (- resolution (or threshold 0)) {})))
#_(build-dupes {:resolution 9 :threshold nil})

;;INICIALIZA O RESETEA UN INDEX CON LAS CORRESPONDIENTES OPCIONES/CONFIGURACIONES
;;TOCA: threshold, resolution, todo defaults, presets, encoder, filterer, fmap, ctx, id, timer
(defn init [options]
  (let [{:keys [threshold] :as options} (-> defaults
                                            (merge options)
                                            (merge (presets (:preset options))))
        options (update options
                        :resolution
                        #(if (<= % threshold) (inc threshold) %))
        {:keys [encoder] :as options} (update options
                                              :encoder
                                              #(or (global-encoder %) %));;este or me devuelve una funcion!!!
        options (update options
                        :filterer
                        #(when % (set (mapv encoder %))))];;que coleccion va en :filterer???
    (into
     options
     {:fmap (build-dupes options);;es flexmap ?
      :ctx {}
      :id {}
      :timer 0})))
#_(init {:resolution 9 :threshold 9 :preset :memory :encoder :advanced})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-index [map dupes value id partial-score context-score threshold resolution]
  (or (dupes value)
      (let [score (if (pos? partial-score)
                    (+ (* (- resolution (or threshold (/ resolution 1.5))) context-score)
                       (* (or threshold (/ resolution 1.5)) partial-score))
                    context-score)
            dupes (assoc dupes value score)
            arr (get map (- resolution (bit-shift-right (int (+ score 0.5)) 0)))
            arr (or (get arr value) (assoc arr value []))
            arr (assoc arr (count arr) id)]
        {:score score
         :dupes dupes
         :arr (if (<= threshold score) arr nil)})));;sacar score que es interna.... arr donde se usa?

;;me parece que tengo que hacer 2 loops porque estoy alternando entre 2 recorridos distintos.... uno cuando entro a un vector y otro cuando entro a un mapa
(defn remove-index [map id]
  (loop [coll (vec map)
         ret []
         c 0]
    (let [key (first (first coll))
          value (second (first coll))];;tmp(temporary?, token map?)
      (if (= c (count map)) (if (empty? ret) nil
                                (apply assoc {} (apply concat ret)))
          (recur (vec (rest coll))
                 (if (and (= 1 (count value)) (= id (first value))) ret
                     (loop [vals value
                            rett []
                            cc 0]
                       (cond (= cc (count value)) (conj ret [key rett])
                             (= id (first vals)) (conj ret [key (into rett (rest vals))])
                             :else (recur (rest vals)
                                          (cond (map? (first vals)) (conj rett (remove-index (first vals) id))
                                                (= id (first vals)) rett
                                                (not= id (first vals)) (conj rett (first vals)))
                                          (inc cc)))))
                 (inc c))))))
#_(remove-index {:a [{:a [1 2 3 2]} 2 3 2 {:a []}] :b []} 2);;la de js no veo que haga nada.... deja todo como está
#_(apply assoc {} (apply concat [[:a [1 2 3]]]));;mete adentro del vector tanto assoc como {}. recordar que transforma la coleccion en lista para que pueda ser ejecutada con la funcion al principio 

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;REMUEVE ITEM DE UN INDEX
(defn for-remove-flex [resolution threshold -map id]
  (loop [z 0
         -map -map;;-map es un vector de mapas
         ret []]
    (if (= z (- resolution (or threshold 0))) ret
        (recur (inc z)
               (rest -map)
               (conj ret (remove-index (first -map) id))))))

(defn remove-flex [{:keys [-ids depth -map resolution threshold -ctx] :as flex};;-ids es un objeto
                   id callback -recall];;recall es un booleano
  (let [index (str "@" id)]
    (if (-ids index);;el valor de ids es a su vez un mapa con llaves string
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

;;MODIFICA ITEM DE UN INDEX
(declare add)
(defn update-flex [{:keys [-ids] :as flex}
                   id content callback -recall];;callback es de support-callback?
  (let [index (str "@" id)]
    (if (and (-ids index) (string? content))
      (add (remove-flex flex id callback -recall) id content callback nil true);;ver bien si el true de js corresponde a -skip-update o a -recall
      flex)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn reverse-t [length value -map dupes id rtl context-score threshold resolution];;ojo que este -map en realidad figura como map solo. posiblemente creada como variable interna que obtiene el valor de this.-map. ojo tambien que el input rtl es raro, tiene un if adentro ademas del que se ve aca
  (loop [a length
         b (dec a)
         token (str (get value a) "")
         dupes dupes
         ret {}]
    (if (= 0 b) (merge ret {:token token})
        (recur (dec a)
               (dec b)
               (str (get value (dec a)))
               ((add-index -map dupes token id (if rtl 1 (/ (- length b) length)) context-score threshold (dec resolution)) :dupes)
               (merge ret (add-index -map dupes token id (if rtl 1 (/ (- length b) length)) context-score threshold (dec resolution)))))))

(defn forward-t [value dupes length -map id rtl context-score threshold resolution]
  (loop [a 0
         token (str (get value a));;token es un string
         dupes dupes
         ret {}]
    (if (= a length) (merge ret {:token token})
        (recur (inc a)
               (str token (get value (inc a)))
               (if (map? (add-index -map dupes token id (if rtl (/ (+ a 1) length) 1) context-score threshold (dec resolution)))
                 ((add-index -map dupes token id (if rtl (/ (+ a 1) length) 1) context-score threshold (dec resolution)) :dupes)
                 dupes)
               (if (map? (add-index -map dupes token id (if rtl (/ (+ a 1) length) 1) context-score threshold (dec resolution)))
                 (merge ret (add-index -map dupes token id (if rtl (/ (+ a 1) length) 1) context-score threshold (dec resolution)))
                 ret)))))

(defn for-full-t [x length value -map dupes id partial-score context-score threshold resolution]
  (loop [y length
         token (subs value x y)
         dupes dupes
         ret {}]
    (if (= y x) (merge ret {:token token})
        (recur (dec y)
               (subs value x (dec y))
               ((add-index -map dupes token id partial-score context-score threshold (dec resolution)) :dupes)
               (merge ret (add-index -map dupes token id partial-score context-score threshold (dec resolution)))))))

(defn full-t [rtl length value -map dupes id context-score threshold resolution]
  (loop [x 0
         partial-score (/ (if rtl (inc x) (- length x)) length)
         ret {}]
    (if (= x length) (merge ret {:partial-score partial-score})
        (recur (inc x)
               (/ (if rtl (inc (inc x)) (- length (inc x))) length)
               (merge ret (for-full-t x length value -map dupes id partial-score context-score threshold resolution))))))

(defn create-object-array [countt]
  (vec (concat [] (repeat countt {}))))

(defn default-t [flex
                 -map dupes value id context-score threshold resolution depth word-length i words]
  (let [result (add-index -map dupes value id 1 context-score threshold (dec resolution))
        score (or (result :score) result);;ver si en verdad necesito solo :score o es todo el mapa
        dupes (result :dupes)
        arr (result :arr)]
    (if (and depth (< 1 word-length) (<= threshold score))
      (let [ctxdupes (or (get-in dupes [:-ctx value])
                         (assoc-in dupes [:-ctx value] {}))
            ctxtmp (or (get-in flex [:-ctx value])
                       (assoc-in flex [:-ctx value] (vec (concat [] (repeat (- resolution (or threshold 0)) {})))))
            x (if (< (- i depth) 0) 0 (- i depth))
            y (if (< word-length (+ i depth 1)) word-length (+ i depth 1));;ctxdupes, ctxtmp x e y me parece que no se usan mas adelante por eso no las devolvi en ningun lado
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
;;falta el not x=i en el for

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
    (cond (= i word-length) (merge flex
                                   {:value value
                                    :length length
                                    :context-score context-score
                                    :token token};;como se si todos estos valores van en el flex o no?
                                   ret)
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
         (or id (= id 0))) (let [content (encode flex content)];;ver porque (content) esta entre parentesis en el js
                             (if (not (count content))
                               (assoc flex :content content);;ver si tengo que meterlo adentro o armo un vector o un mapa con flex mas content
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
;;estaria(verificar si esta bien la cadena) devolviendo solo el flex.... que pasa con todos los otros const o let que hay dando vueltas? se usan mas adelante por alguna otra o solo es para el uso interno y no tiene sentido devolverlas?
;;modificamos
;;tokenizer
;;words
;;dupes (no lo mergeo al final porque ya lo obtengo de las llamadas a add-index)
;;word-length


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;INTERSECT;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-page [cursor page result]
  ;; TODO str page?
  (if cursor
    {:page cursor
     :next (if page (str page) nil)
     :result result}
    result))

(defn limit-true
  "pointer es nro"
  [result pointer limit cursor]
  (let [length (count result)]
    (if (and (pos? pointer) (< length pointer))
      (let [pointer 0
            start (or pointer 0)
            page (+ start limit)]
        (if (< page length)
          (create-page cursor page (vec (drop start (take page result))))
          (create-page cursor 0 (if start (vec (drop start result)) result))))
      (let [start (or pointer 0)
            page (+ start limit)]
        (if (< page length)
          (create-page cursor page (vec (drop start (take page result))))
          (create-page cursor 0 (if start (vec (drop start result)) result)))))))

(defn length-z-true
  "pointer es un string numerico. ver como puedo hacer para que admita numeros sueltos tb"
  [result bool arrays pointer]
  (if (not bool)
    (let [result (first arrays)]
      (if pointer
        {:pointer (Character/digit (first pointer) 10) :result result}
        {:pointer pointer :result result}))
    {:pointer pointer :result result}))
;;antes usaba (.parseInt (first pointer))
;;probar sino con (Integer/parseInt "2")

(defn for-first-result-true [i first-result result-length result check-not countt]
  (loop [i i
         id (get first-result i)
         countt countt
         result result]
    (if (= i result-length) {:countt countt
                             :result result}
        (recur (inc i)
               (get first-result (inc i))
               (if (not (get check-not (str "@" id)));;el id no hay que expresarlo como (inc i) porque esta como condicion, no lo que devuelvo
                 (inc countt)
                 countt)
               (if (not (get check-not (str "@" id)))
                 (assoc result countt id)
                 result)))))
#_(for-first-result-true 0 ["a"] 3 ["x" "y" "z"] {} 2);da bien 

;;como la i termina de usarse aca no tiene sentido devolverla en algun lado como valor
(defn first-result-true
  "pointer es un string numerico o un numero. ver si realmente hace falta que sea numero o con string ya alcanza"
  [first-result has-not pointer result check-not countt]
  (let [result-length (count first-result)]
    (if has-not
      (if pointer
        (for-first-result-true (if (number? pointer) pointer (Integer/parseInt pointer)) first-result result-length result check-not countt)
        (for-first-result-true 0 first-result result-length result check-not countt))
      {:result first-result})))

;;cuando tengo como aca un return y dos mutaciones a objetos (check y found)
;;(en este caso el return es una funcion(que luego devuelve un objeto), pero puede ser directamente el objeto en si mismo)
;;me parece que tengo que devolvr un mapa como objeto en donde incluyo todos los valores finales de lo que fue modificando la funcion js
;;en este caso seria algo asi {:page (create-page args) :check value :found value}
(defn for-chico
  "los pointer andan los 5 al parecer"
  [z length arr has-and check has-not check-not is-final-loop
   pointer-count result countt limit cursor pointer found]
  (let [bool-or nil]
    (loop [i 0
           tmp (get arr i)
           index (str "@" tmp)
           check-val (if has-and (or (get check index) 0) z)
           check check
           found found
           countt countt
           pointer-count pointer-count
           result result]
      (cond (= i length) {:found found
                          :countt countt
                          :result result}
            (and (< 0 i)
                 check-val
                 (not (and has-not (get check-not index)))
                 (not (and (not has-and) (get check index)))
                 (= z check-val)
                 (or is-final-loop bool-or)
                 (or (not (pos? pointer-count)) (< (dec pointer-count) countt))
                 (and limit (= limit countt))) {:found found
                                                :countt countt
                                                :result result
                                                :return (create-page cursor (+ countt (or pointer 0)) result)}
            :else (recur (inc i) ;;i
                         (get arr (inc i)) ;;tmp
                         (str "@" (get arr (inc i))) ;;index
                         (if has-and (or (get check (str "@" (get arr (inc i)))) 0) z) ;;check-val
                         (if (and check-val ;;check
                                  (not (and has-not (get check-not index)))
                                  (not (and (not has-and) (get check index)))
                                  (= z check-val)
                                  (not (or is-final-loop bool-or)))
                           (assoc check index (inc z))
                           check)
                         (if (and (pos? check-val) ;;found
                                  (not (or (and has-not (get check-not index))
                                           (and (not has-and) (get check index))
                                           (and (= check-val z)
                                                (or is-final-loop bool-or)
                                                (or (not pointer-count) (< (dec pointer-count) count))
                                                (and limit (= count limit))))))
                           true
                           found)
                         (if (and check-val ;;count
                                  (not (and has-not (get check-not index)))
                                  (not (and (not has-and) (get check index)))
                                  (= z check-val)
                                  (or is-final-loop bool-or)
                                  (or (not pointer-count) (< (dec pointer-count) countt)))
                           (inc countt)
                           countt)
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
                                  (or (not pointer-count) (< (dec pointer-count) countt)))
                           (assoc result countt tmp)
                           result))))))
;;;;VER SI PUEDO HACER QUE LAS MODIFICACIONES DE LA PRIMER VUELTA LAS PUEDO HACER DENTRO DEL [] DEL LOOP
;;PARA ASI EVITAR QUE LA CONDICION DE TERMINACION SE DE CUANDO (< 0 i). ANALIZARLO!

(defn for-init-true [result-length first-result has-not check-not check has-and result countt]
  (loop [i 0
         id (get first-result i)
         index (str "@" id)
         check check
         result result
         countt countt]
    (if (= i result-length) {:check check
                             :result result
                             :countt countt}
        (recur (inc i) ;;i
               (get first-result (inc i)) ;;id
               (str "@" (get first-result (inc i))) ;;index
               (if (or (not has-not) (not (get check-not index))) ;;check
                 (assoc check index 1)
                 check)
               (if (and (or (not has-not) (not (get check-not index))) ;;result
                        (not has-and))
                 (assoc result countt id)
                 result)
               (if (and (or (not has-not) (not (get check-not index))) ;;countt
                        (not has-and))
                 (inc countt)
                 countt)))))

(defn init-true [first-result has-not check-not check has-and result arr countt]
  (if first-result
    (let [result-length (count first-result)]
      (merge {:first-result nil :init false}
             (for-init-true result-length first-result has-not check-not check has-and result countt)))
    {:first-result arr}))
;;ojo con la variable countt que coincide el nombre con la funcion
;;no la termine usando porque quedaba mas corto si llamaba directamente a for-init-true me parece
0 2 1 ["a" "asdd" "rrr" "z"] false 2 true nil false {} {} ["a" "b" "c" "sdfsdfs"] 0 3 8 "3"

(defn for-grande [z last-index length-z arrays cursor page init first-result has-not check-not
                  check result countt pointer-count limit pointer]
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
           countt countt
           found found
           i-t (init-true first-result has-not check-not check has-and result arr countt);;ver si conviene sacar estos 2 afuera del loop con un let
           for-c (for-chico z length arr has-and
                            (if (and init first-result) (i-t :check) check)
                            has-not check-not is-final-loop pointer-count
                            (if (and init first-result) (i-t :result) result)
                            (if (and init first-result) (i-t :countt) countt)
                            limit cursor pointer found)]
      (cond (or (= z length-z)
                (and (< 0 z) (and bool-and (not found)))) {:first-result first-result
                                                           :result result
                                                           :countt countt};;FIN DEL CONTEO
            (and (not length) bool-and) {:first-result first-result
                                         :result result
                                         :countt countt
                                         :return (create-page cursor page arr)};;RETURN PROPIO
            (for-c :return) {:first-result first-result
                             :result result
                             :countt countt
                             :return (for-c :return)};;RETURN DEL FOR CHICO
            (and (< 0 z)
                 bool-and
                 (not found)) {:first-result first-result
                               :result result
                               :countt countt};;BREAK
            :else (recur (inc z) ;;z
                         (= (inc z) (- (or last-index length-z) 1)) ;;is-final-loop
                         (arrays (inc z)) ;;arr
                         (count (arrays (inc z))) ;;length
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
                             (for-c :check)))
                         (if (not length) ;;result
                           result
                           (if (and init (not first-result))
                             result
                             (for-c :result)))
                         (if (not length) ;;countt
                           countt
                           (if (and init (not first-result))
                             countt
                             (for-c :countt)))
                         (if (not length) ;;found
                           found
                           (if (and init (not first-result))
                             found
                             (for-c :found)))
                         (init-true first-result has-not check-not check has-and result arr countt) ;;i-t
                         (for-chico (inc z) length arr has-and ;;for-c
                                    (if (and init first-result) (i-t :check) check)
                                    has-not check-not is-final-loop pointer-count
                                    (if (and init first-result) (i-t :result) result)
                                    (if (and init first-result) (i-t :countt) countt)
                                    limit cursor pointer found))))))


;;CUANDO TENGO PEDACITOS Y EN UNO HAY UN RETURN TENGO QUE FINALIZAR TODA LA FUNCION (TANTO EL CLOJURE COMO EN JS) Y DEVOLVER ESE VALOR
;;PERO EN LA FUNCION DE CLOJURE TAMBIEN TENGO QUE ALMACENAR/DEVOLVER TODOS LOS OTROS OBJETOS QUE SE MODIFICARON? CREO QUE SI
;;LO QUE SI ES SEGURO ES QUE HAY QUE TENER CUIDADO DE LOS RETURNS EN LOS FRAGMENTOS DE FUNCIONES PORQUE NO SOLO TERMINAN ESA SINO TODAS LAS QUE TIENE ARRIBA QUE SEAN DE SU MISMA FUNCION
;;ENTONCES TENGO QUE MODIFICAR TODO PORQUE NO SE SI VOY A NECESITAR O NO LOS VALORES DE LOS OBJETOS MODIFICADOS APARTE DE LOS VALORES DE LOS RETURN
;;ESTE PROBLEMA ME PARECE PUEDO SOLUCIONARLO SIMPLEMENTE DEVOLVIENDO, EN EL CASO QUE SE ACTIVE UN RETURN, UN MAPA QUE CONTENGA TANTO A LOS OBJETOS MODIFICADOS COMO AL RETURN MISMO

(defn length-z>1 [pointer last-index length-z arrays cursor page has-not result limit]
  (let [check-not {}
        check {}
        z 0
        init true
        countt 0
        first-result nil]
    (if pointer
      (if (= 2 (count pointer))
        (let [pointer false
              pointer-count nil
              for-g (for-grande z last-index length-z arrays cursor page init first-result
                                has-not check-not check result countt pointer-count limit pointer)]
          (if (for-g :return)
            for-g
            (if (for-g :first-result)
              (merge for-g (first-result-true (for-g :first-result) has-not pointer (for-g :result)  check-not (for-g :countt)) {:pointer pointer})
              (merge for-g {:pointer pointer}))))
        (let [pointer (if (= "" pointer)
                        nil;;ver si esto esta bien o que consecuencias trae
                        (Character/digit (first pointer) 10))
              pointer-count pointer
              for-g (for-grande z last-index length-z arrays cursor page init first-result
                                has-not check-not check result countt pointer-count limit pointer)]
          (if (for-g :return)
            for-g
            (if (for-g :first-result)
              (merge for-g (first-result-true (for-g :first-result) has-not pointer (for-g :result) check-not (for-g :countt)) {:pointer pointer})
              (merge for-g {:pointer pointer})))))
      (let [pointer-count nil
            for-g (for-grande z last-index length-z arrays cursor page init first-result
                              has-not check-not check result countt pointer-count limit pointer)]
        (if (for-g :return)
          for-g
          (if (for-g :first-result)
            (merge for-g (first-result-true (for-g :first-result) has-not pointer (for-g :result) check-not (for-g :countt)))
            (merge for-g {:pointer pointer})))))))
;;hay que sacarle de la devolucion :first-result y :count que son internas
#_(length-z>1 3 nil 1 [1 "asdd" "rrr"] true 2 true ["a" "b" "c"] 2);falla por pointer=3 en 794
#_(length-z>1 "3" nil 1 [1 "asdd" "rrr"] true 2 true ["a" "b" "c"] 2);falla por 1 en arrays que despues for-grande no lo puede contar
#_(length-z>1 true 2 1 ["a" "asdd" "rrr"] true 2 true ["a" "b" "c"] 8);;falla por pointer=true que no se puede contar en 794
#_(length-z>1 false nil 1 [["a" "fxf"] ["asdd"] ["rrr"]] true nil true ["a" "b" "c"] 8);;falla por dec pointer-count en 651
#_(length-z>1 false nil 1 ["a" "asdd" "rrr"] true 2 true ["a" "b" "c"] 8);;falla por pointer-count=nil no se puede restar en 651

#_(length-z>1 false nil 1 [["a" "fxf"] ["asdd"] ["rrr"]] true nil true ["a" "b" "c"] 8);;devuelve con pointer-count=0 pero js => a,fxf,a,fxf,c,2,false
#_(length-z>1 false nil 1 ["a" "asdd" "rrr"] true 2 true ["a" "b" "c"] 8);;devuelve con pointer-count=0 pero js =>a,a,b,c,1,false

;;VER LA POSIBILDAD DE EMPEZAR LLAMANDO DESDE LA ULTIMA FUNCION Y PONER LOS CONDICIONALES ADENTRO SUYO, EN LOS ARGUMENTOS DIGAMOS
;;sin suggest
(defn intersect [arrays limit cursor bool has-not]
  (let [result []
        length-z (count arrays)]
    (if (true? cursor)
      (let [cursor 0
            pointer ""]
        (if (< 1 length-z)
          (let [lz (length-z>1 pointer nil length-z arrays cursor nil has-not result limit)]
            (if (lz :return)
              (lz :return)
              (if length-z
                (length-z-true result bool arrays (lz :pointer))
                (if limit
                  (limit-true (lz :result) (lz :pointer) limit cursor)
                  (create-page cursor nil (lz :result))))))
          (if length-z
            (length-z-true result bool arrays pointer)
            (if limit
              (limit-true result pointer limit cursor)
              (create-page cursor nil result)))))
      (let [pointer (and cursor (str/split cursor #":"))]
        (if (< 1 length-z)
          (let [lz (length-z>1 pointer nil length-z arrays cursor nil has-not result limit)]
            (if (lz :return)
              (lz :return)
              (if length-z
                (length-z-true result bool arrays (lz :pointer))
                (if limit
                  (limit-true (lz :result) (lz :pointer) limit cursor)
                  (create-page cursor nil (lz :result))))))
          (if length-z
            (length-z-true result bool arrays pointer)
            (if limit
              (limit-true result pointer limit cursor)
              (create-page cursor nil result))))))))
;;VER DE PLANTEARLO CON LO ULTIMO DE JS COMO PRIMER FUNCION APLICADA E IR PONIENDO LOS CONDICIONALES ADENTRO A MEDIDA QUE VOY SUBIENDO EN JS

#_(intersect ["a" "asdd" "rrr"] 8 true ["not"] true);;funciona pero no se que da el js
;;el js falla porque check-not[index] en la parte init no tiene nada y no puedo obtener la propiedad. dicha propiedad debiera haber sido agregada por SUPPORT-DOCUMENT anteriormente

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;SEARCH;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn for-search-inner [resolution threshold map value]
  (loop [z 0
         map-value (and (get map z) (get-in map [z value]))
         map-check []
         countt 0
         map-found false]
    (if (= z (- resolution threshold)) {:map-check map-check
                                        :countt countt
                                        :map-found map-found}
        (recur (inc z);;z
               (and (get map (inc z)) (get-in map [(inc z) value]));;map-value
               (if map-value;;map-check
                 (assoc map-check countt map-value)
                 map-check)
               (if map-value;;countt
                 (inc countt)
                 countt)
               (if map-value;;map-found
                 true
                 map-found)))))

;;ver si las variables que asigne en el loop se originan dentro del for, fuera del for pero dentro de la funcion o sino totalmente fuera de la funcion
(defn for-search [{:keys [resolution threshold -map] :as flex} ;;para resolution hace una asignacion interna... significa que el resolution global no lo toca?
                  words use-contextual ctx-map length]
  (loop [a 0
         value (get words a)
         ctx-root nil ;;ctx hace referencia a contextual
         check-words {}
         map-check []
         map-found false
         countt 0
         map (if use-contextual (get ctx-map ctx-root) -map)
         check []]
    (cond (= a length) {:ctx-root ctx-root
                        :check-words check-words
                        :check check};;ver por las dudas si no falta found en estas 2 primeras condiciones de terminacion
          (and value
               use-contextual
               (not ctx-root)
               (not (get ctx-map value))) {:ctx-root ctx-root
                                           :check-words check-words
                                           :check check
                                           :return []};;este es return result
          (and value
               (not (get check-words value))
               (not map-found)) {:found false
                                 :ctx-root ctx-root
                                 :check-words check-words
                                 :check check
                                 :return []};;BREAK
          :else (recur
                 (inc a);;a
                 (get words (inc a));;value
                 (if (and value;;ctx-root
                          use-contextual
                          (not ctx-root)
                          (get ctx-map value))
                   value
                   (if (and value
                            (not (get check-words value))
                            map-found)
                     value
                     ctx-root));;ojo con el continue que aparece abajo en (!ctx-root) que no lo estoy considerando me parece. me parece que no importa porque yo estoy marcando solo el caso en el que ctx-root asume value, y en el resto de los casos mantiene el valor previo. de todas maneras me parece que no va porque estoy evaluando la misma condicion que en el de arriba. y como esta debajo del cambio que se le realiza si no tenia un valor previo esa condicion nunca se activara. si no tenia valor lo asume y la 2da comprobacion da falso quedando value, y si tenia valor queda con el valor que venia y el continue tampoco se activa
                 (if (and value;;check-words
                          use-contextual
                          (not ctx-root)
                          (get ctx-map value))
                   (assoc check-words value 1)
                   (if (and value
                            (not (get check-words value)))
                     (assoc check-words value 1)
                     check-words))
                 (if (and value;;map-check. ver si no se modifica tambien en if(map-found) de js
                          (not (get check-words value))
                          map)
                   (let [for-in (for-search-inner resolution threshold map value)]
                     (for-in :map-check))
                   map-check);;ver si el siguiente if, map-found, modifica a map-check cuando aplica sobre el concat.apply
                 (if (and value;;map-found
                          (not (get check-words value))
                          map)
                   (let [for-in (for-search-inner resolution threshold map value)]
                     (for-in :map-found))
                   map-found)
                 (if (and value;;countt
                          (not (get check-words value))
                          map)
                   (let [for-in (for-search-inner resolution threshold map value)]
                     (for-in :countt))
                   countt)
                 (if (and value;;map
                          (not (get check-words value)))
                   (if use-contextual (get ctx-map ctx-root) -map)
                   map)
                 (if (and value;;check
                          (not (get check-words value))
                          map-found)
                   (assoc check (count check) (if (< 1 countt)
                                                (concat map-check (concat [] map-check));;corroborar si esta bien y ver si en el js al aplicar cncat.apply sobre el objeto para obtener el resultado tambien estoy modificando ese objeto en cuestion
                                                (get map-check 0)))
                   check)))))

;;this en este caso se refiere a flex o a query?
(defn search [{:keys [threshold tokenize split filter depth -ctx] :as flex}
              query limit callback -recall]
  (let [callback (if (and limit (fn? limit))
                   limit
                   callback)
        limit (if (and limit (fn? limit))
                1000
                (if (pos? limit)
                  limit
                  (or (= limit 0) 1000)))
        result []
        -query query
        threshold (if (pos? threshold)
                    threshold
                    0)
        flex (merge flex {:callback callback
                          :limit limit
                          :threshold threshold})];;corroborar si esto y limit estan bien
    (if (and (not -recall) callback)
      (callback (search flex -query limit nil true));;callback deberia devolver flex....verificarlo. no le agregue los demas objetos modificados porque esta haciendo una recursion y teoricamente tendria que estar terminando por alguna de las otras salidas en las cuales si estarian incluidos. estoy suponiendo que estoy devolviendo this aca
      (if (or (not query) (not (string? query)))
        (merge flex {:result result})
        (let [-query (encode flex -query)
              flex (merge flex {:-query -query})];;ver si el -query va en value o en flex
          (if (not (count -query))
            (merge flex {:result result})
            (let [words (if (fn? tokenize)
                          (tokenize -query)
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
                  ctx-map nil
                  flex (merge flex {:words words
                                    :length length
                                    :found found
                                    :use-contextual use-contextual
                                    :ctx-map ctx-map})]
              (if (or (not use-contextual) (= ctx-map -ctx))
                (let [fs (for-search flex words use-contextual ctx-map length)];;aca por ejemplo en js reasigna const resolution = this.resolution. esto significa que el objeto global resolution no se cambia y solo estoy modificandolo internamente en la funcion?. notese de todas maneras que dentro del for no estoy reasignando nada a resolution, ya sea la posible interna o la global
                  (if (fs :result)
                    (merge flex fs)
                    (if found
                      (merge flex fs {:result (intersect (fs :check) limit nil nil nil)})
                      (merge flex {:result result}))));;ver porque intersect no tiene has-and y porque js usa SUPPORT_SUGGESTION && suggest como argumentos
                (merge flex {:result result})))))))))


;;AVERIGUAR:
;;else{limit || (limit === 0 ) || (limit = 1000);}
;;threshold || (threshold = this.threshold || 0);
;;callback(this.search(_query, limit, null, true));
;;(_query = query);
;;_query = this.encode((_query));




;;REVISAR SI TODOS LOS GET A VECTORES O LOS (MAP KEY) ESTAN CORRECTOS
;;VERIFICAR LA CONTINUIDAD DE LOS ELSE EN INTERSECT Y ADD
;;VERIFICAR TODOS LOS IF (VARIABLE) QUE PUEDAN REFERIRSE A NUMEROS CON VALOR Y DISTINTOS DE 0
;;SEGUIR REVISION DE INTERSECT POR FOR-CHICO
;;REVISAR EN LOS LOOPS SI HAY 2 ASIGNACIONES A UN MISMO ELEMENTO A PARTIR DEL VALOR DE OTRO EN EL QUE ESTE ULTIMO PUEDA HABER SIDO CAMBIADO EN EL MEDIO DE LAS DOS ASIGNACIONES EJ CTX-ROOT/VALUE EN FOR-SEARCH


;;CREACION, AGREGADO Y BUSQUEDA DE CONTENIDO Y SU INDEX:
;;(search(add (init ...)))