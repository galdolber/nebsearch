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
   :split #"\W+"})

(def presets
  {"memory" {:encode "extra" :tokenize "strict" :threshold 0 :resolution 1}
   "speed" {:encode "icase" :tokenize "strict" :threshold 1 :resolution 3 :depth 2}
   "match" {:encode "extra" :tokenize "full" :threshold 1 :resolution 3}
   "score" {:encode "extra" :tokenize "strict" :threshold 1 :resolution 9 :depth 4}
   "balance" {:encode "balance" :tokenize "strict" :threshold 0 :resolution 3 :depth 3}
   "fast" {:encode "icase" :tokenize "strict" :threshold 8 :resolution 9 :depth 1}})

(defn sort-by-length-down [a b] (cond
                                  (> (count a) (count b)) -1
                                  (< (count a) (count b)) 1
                                  :else 0))

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
  {"icase" global-encoder-icase
   "simple" global-encoder-simple
   "advanced" global-encoder-advanced
   "extra" global-encoder-extra
   "balance" global-encoder-balance})

(defn encode-f [{:keys [encode stemmer matcher] :as flex}
                value]
  (let [global-matcher []]
    (when value
      (cond
        stemmer (replace-regexes (if (global-encoder encode)
                                   ((global-encoder encode) (if (count matcher)
                                                              (replace-regexes (if (count global-matcher)
                                                                                 (replace-regexes value global-matcher)
                                                                                 value)
                                                                               matcher)
                                                              value))
                                   value)
                                 stemmer)
        (global-encoder encode) ((global-encoder encode) (if (count matcher)
                                                           (replace-regexes (if (count global-matcher)
                                                                              (replace-regexes value
                                                                                               global-matcher)
                                                                              value) matcher)
                                                           value))
        (count matcher) (replace-regexes (if (count global-matcher)
                                           (replace-regexes value
                                                            global-matcher)
                                           value) matcher)
        (count global-matcher) (replace-regexes value
                                                global-matcher)
        :else "error"))))

(defn filter-words [words fn-or-map];;ver porque me parece que funciona al reves
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

(defn build-dupes [{:keys [resolution threshold]}]
  (vec (repeat (- resolution (or threshold 0)) {})))

(defn init [options];;options es para poner otras opciones entre las que esta :presets. :presets despues se mergea con el resto
  (let [{:keys [threshold] :as options} (-> defaults ;; arranco con defaults
                                            (merge options) ;;agrego opciones extras de las cuales derivo las presets
                                            (merge (presets (options :presets)))) ;;agrego presets a partir de options
        options (update options
                        :resolution
                        #(if (<= % threshold) (inc threshold) %))
        {:keys [encode] :as options} (update options
                                              :encode
                                              #(or (global-encoder %) %));;este or me devuelve una funcion POR GLOBAL ENCODER!!!
        options (update options
                        :filter
                        #(when % (set (mapv (options encode) %))))];;que coleccion va en :filterer???
    (merge options {:map (build-dupes options);;es flexmap ?
                    :ctx {}
                    :ids {}
                    :timer 0})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;ESTA SOLO TOMA VALORES Y DEVUELVE SCORE O DUPES[VALUE]. NO TOCA NINGUN OBJETO, POR ESO NO HAY THIS Y LOS INPUTS SE PASAN TODOS SEPARADOS
(defn add-index [{:keys [map id threshold resolution] :as flex}
                 dupes value partial-score context-score]
  (or (if (number? dupes)
        dupes
        (dupes value))
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
         :arr (if (<= threshold score) arr nil)})))

;;me parece que tengo que hacer 2 loops porque estoy alternando entre 2 recorridos distintos.... uno cuando entro a un vector y otro cuando entro a un mapa
(defn remove-index [{:keys [map id] :as flex}]
  (loop [coll (vec map)
         ret []
         c 0]
    (let [key (first (first coll))
          vectorr (second (first coll))];;tmp(temporary?, token map?)
      (if (= c (count map)) (if (empty? ret) nil
                                (apply assoc {} (apply concat ret)))
          (recur (vec (rest coll));;coll
                 (cond
                   (or (string? vectorr) (number? vectorr)) (conj ret [key vectorr])
                   (and (= 1 (count vectorr)) (= id (first vectorr))) ret
                   :else (loop [vals vectorr
                                rett []
                                cc 0]
                           (cond (= cc (count vectorr)) (conj ret [key rett])
                                 (= id (first vals)) (conj ret [key (into rett (rest vals))])
                                 :else (recur (rest vals);;vals
                                              (cond (map? (first vals)) (conj rett (remove-index {:map (first vals) :id id}))
                                                    (= id (first vals)) rett
                                                    :else (conj rett (first vals)))
                                              (inc cc)))))
                 (inc c))))))
#_(remove-index {:a [{:a [1 2 3 2]} 2 3 2 {:a []}] :b []} 2);;la de js no veo que haga nada.... deja todo como está
#_(apply assoc {} (apply concat [[:a [1 2 3]]]));;mete adentro del vector tanto assoc como {}. recordar que transforma la coleccion en lista para que pueda ser ejecutada con la funcion al principio 

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;REMUEVE ITEM DE UN INDEX
(defn for-remove-flex [{:keys [resolution threshold map id] :as flex}]
  (loop [z 0
         map map;;map es un vector de mapas
         ret []]
    (if (= z (- resolution (or threshold 0))) (if (= z 0) map ret)
        (recur (inc z)
               (rest map)
               (conj ret (remove-index {:map (first map)
                                        :id id}))))))

(defn remove-flex [{:keys [ids depth map resolution threshold ctx] :as flex};;ids es un objeto
                   id callback -recall];;recall es un booleano
  (let [index (str "@" id)]
    (if (ids index);;el valor de ids es a su vez un mapa con llaves string
      (if (and (not -recall)
               callback)
        #_callback(remove-flex flex id nil true);;??? como hago con callback que se llama sin ningun argumento?. creo mque asi esta bien ya que la estaria llamando sobre el flex.. pero porque no figura como this en el js????
        (assoc (if depth
                 (assoc flex
                        :ctx (remove-index {:map ctx
                                            :id id})
                        :map (for-remove-flex (merge flex {:resolution resolution
                                                           :threshold threshold
                                                           :map map
                                                           :id id})))
                 (assoc flex
                        :map (for-remove-flex (merge flex {:resolution resolution
                                                           :threshold threshold
                                                           :map map
                                                           :id id}))))
               :ids (dissoc ids index)))
      flex)));;ver si hace falta devolver algun otro objeto modificado junto a flex

;;MODIFICA ITEM DE UN INDEX
(declare add)
(defn update-flex [{:keys [id ids] :as flex}
                   content callback -recall];;callback es de support-callback?
  (let [index (str "@" id)]
    (if (and (ids index) (string? content))
      (add (merge flex (remove-flex flex id callback -recall)) id content callback nil true);;ver bien si el true de js corresponde a -skip-update o a -recall
      flex)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn reverse-t [{:keys [map id rtl threshold resolution] :as flex}
                 length value dupes context-score];;ojo que este map en realidad figura como map solo. posiblemente creada como variable interna que obtiene el valor de this.map. ojo tambien que el input rtl es raro, tiene un if adentro ademas del que se ve aca
  (loop [a length
         b (dec a)
         token (str (get value a) "")
         dupes dupes
         ret {}];;ver si conviene meter dupes
    (if (= 0 b) (merge ret {:token token})
        (recur (dec a)
               (dec b)
               (str (get value (dec a)))
               ((add-index (merge flex {:map map
                                        :id id
                                        :threshold threshold
                                        :resolution (dec resolution)}) dupes token (if rtl 1 (/ (- length b) length)) context-score) :dupes)
               (merge ret (add-index (merge flex {:map map
                                                  :id id
                                                  :threshold threshold
                                                  :resolution (dec resolution)})  dupes token (if rtl 1 (/ (- length b) length)) context-score))))))

(defn forward-t [{:keys [map id rtl threshold resolution] :as flex}
                 value dupes length context-score]
  (loop [a 0
         token (str (get value a));;token es un string
         dupes dupes
         ret {}];;ver si conviene meter dupes
    (if (= a length) (merge ret {:token token})
        (recur (inc a)
               (str token (get value (inc a)))
               (if (map? (add-index (merge flex {:map map
                                                 :id id
                                                 :threshold threshold
                                                 :resolution (dec resolution)}) dupes token  (if rtl (/ (+ a 1) length) 1) context-score))
                 ((add-index (merge flex {:map map
                                          :id id
                                          :threshold threshold
                                          :resolution (dec resolution)}) dupes token (if rtl (/ (+ a 1) length) 1) context-score) :dupes)
                 dupes)
               (if (map? (add-index (merge flex {:map map
                                                 :id id
                                                 :threshold threshold
                                                 :resolution (dec resolution)})  dupes token (if rtl (/ (+ a 1) length) 1) context-score))
                 (merge ret (add-index (merge flex {:map map
                                                    :id id
                                                    :threshold threshold
                                                    :resolution (dec resolution)}) dupes token (if rtl (/ (+ a 1) length) 1) context-score))
                 ret)))))

(defn for-full-t [{:keys [map id threshold resolution] :as flex}
                  x length value  dupes partial-score context-score]
  (loop [y length
         token (subs value x y)
         dupes dupes
         ret {:dupes dupes}]
    (if (= y x) (merge ret {:token token})
        (recur (dec y)
               (subs value x (dec y));;puede ser que el (dec y) no haga falta aca porque no es un valor que se necesite para calcular alguno de los proximos valores, en js figura token = value.substring(x, y); directamente. controlar las demas recursiones por este tema
               (if (map? (add-index (merge flex {:map map
                                                 :id id
                                                 :threshold threshold
                                                 :resolution (dec resolution)}) dupes token partial-score context-score))
                 ((add-index (merge flex {:map map
                                          :id id
                                          :threshold threshold
                                          :resolution (dec resolution)}) dupes token partial-score context-score) :dupes)
                 dupes)
               (if (map? (add-index (merge flex {:map map
                                                 :id id
                                                 :threshold threshold
                                                 :resolution (dec resolution)}) dupes token partial-score context-score))
                 (merge ret (add-index (merge flex {:map map
                                                    :id id
                                                    :threshold threshold
                                                    :resolution (dec resolution)}) dupes token partial-score context-score))
                 ret)))))

(defn full-t [{:keys [map id rtl threshold resolution] :as flex}
              length value dupes context-score]
  (loop [x 0
         partial-score (/ (if rtl (inc x) (- length x)) length)
         ret {}]
    (if (= x length) (merge ret {:partial-score partial-score})
        (recur (inc x)
               (/ (if rtl (inc (inc x)) (- length (inc x))) length);;estos inc x
               (assoc ret :dupes (merge (ret :dupes) ((for-full-t (merge flex {:map map
                                                                               :id id
                                                                               :threshold threshold
                                                                               :resolution resolution})x length value dupes partial-score context-score) :dupes)))))))

(defn default-t [{:keys [map id threshold resolution depth] :as flex}
                 dupes value context-score word-length i words]
  (let [result (add-index (merge flex {:map map
                                       :id id
                                       :threshold threshold
                                       :resolution (dec resolution)}) dupes value 1 context-score)
        score (if (map? result)
                (result :score)
                result)
        dupes (if (map? result)
                (result :dupes)
                dupes)]
    (if (and depth
             (< 1 word-length)
             (<= threshold score))
      (let [ctxdupes (or (get-in dupes [:ctx value]) (assoc-in dupes [:ctx value] {}))
            ctxtmp (or (get-in flex [:ctx value]) (vec (concat [] (repeat (- resolution (or threshold 0)) {}))))
            flex (assoc-in flex [:ctx value] (vec (concat [] (repeat (- resolution (or threshold 0)) {}))))
            x (if (< (- i depth) 0) 0 (- i depth))
            y (if (< word-length (+ i depth 1)) word-length (+ i depth 1))
            dupes (loop [x x
                         dupes dupes]
                    (if (= x y) dupes
                        (recur (inc x)
                               (if (not= x i)
                                 (if (map? (add-index (merge flex {:map ctxtmp
                                                                   :id id
                                                                   :threshold threshold
                                                                   :resolution (dec resolution)}) ctxdupes (get words x) 0 (- resolution (if (< x i) (- i x) (- x i)))))
                                   ((add-index (merge flex {:map ctxtmp
                                                            :id id
                                                            :threshold threshold
                                                            :resolution (dec resolution)}) ctxdupes (get words x) 0 (- resolution (if (< x i) (- i x) (- x i)))) :dupes)
                                   dupes)
                                 dupes))))]
        [flex {:dupes dupes}])
      {:dupes dupes})))

(defn for-add [{:keys [map id rtl threshold resolution depth] :as flex}
               words word-length tokenizer dupes]
  (loop [i 0
         value (get words i)
         length (count value)
         context-score (/ (if rtl (+ i 1) (- word-length i)) word-length)
         token nil
         dupes dupes
         score nil]
    (cond (= i word-length) (merge flex
                                   {:value value
                                    :length length
                                    :context-score context-score
                                    :token token;;como se si todos estos valores van en el flex o no?
                                    :dupes dupes
                                    :score score})
          :else (recur (inc i);;i
                       (get words (inc i));;value
                       (if value;;length
                         (count (get words (inc i)))
                         length)
                       (if value;;context-score
                         (/ (if rtl (+ i (inc 1)) (- word-length (inc i))) word-length)
                         context-score)
                       (if value;;token
                         (case tokenizer
                           "reverse" ""
                           "forward" ((forward-t (merge flex {:map map
                                                              :id id
                                                              :rtl rtl
                                                              :threshold threshold
                                                              :resolution resolution}) value dupes length context-score) :token)
                           "both" ((forward-t (merge flex {:map map
                                                           :id id
                                                           :rtl rtl
                                                           :threshold threshold
                                                           :resolution resolution})
                                              value ((reverse-t (merge flex {:map map
                                                                             :id id
                                                                             :rtl rtl
                                                                             :threshold threshold
                                                                             :resolution resolution}) length value dupes context-score) :dupes)
                                              length context-score) :token)
                           "full" ((full-t (merge flex {:map map
                                                        :id id
                                                        :rtl rtl
                                                        :threshold threshold
                                                        :resolution resolution}) length value dupes context-score) :token)
                           "default" token)
                         token)
                       (if value;;dupes
                         (case tokenizer
                           "reverse" (merge dupes ((reverse-t (merge flex {:map map
                                                                           :id id
                                                                           :rtl rtl
                                                                           :threshold threshold
                                                                           :resolution resolution}) length value dupes context-score) :dupes))
                           "forward" (merge dupes ((forward-t (merge flex {:map map
                                                                           :id id
                                                                           :rtl rtl
                                                                           :threshold threshold
                                                                           :resolution resolution}) value dupes length context-score) :dupes))
                           "both" (merge dupes ((forward-t (merge flex {:map map
                                                                        :id id
                                                                        :rtl rtl
                                                                        :threshold threshold
                                                                        :resolution resolution})
                                                           value ((reverse-t (merge flex {:map map
                                                                                          :id id
                                                                                          :rtl rtl
                                                                                          :threshold threshold
                                                                                          :resolution resolution}) length value dupes context-score) :dupes)
                                                           length context-score) :dupes))
                           "full" (merge dupes ((full-t (merge flex {:map map
                                                                     :id id
                                                                     :rtl rtl
                                                                     :threshold threshold
                                                                     :resolution resolution}) length value dupes context-score) :dupes))
                           "default" (merge dupes ((default-t (merge flex {:map map
                                                                           :id id
                                                                           :threshold threshold
                                                                           :resolution resolution
                                                                           :depth depth}) dupes value context-score word-length i words) :dupes)))
                         dupes)
                       (if value;;score
                         (case tokenizer
                           "reverse" ((reverse-t (merge flex {:map map
                                                              :id id
                                                              :rtl rtl
                                                              :threshold threshold
                                                              :resolution resolution}) length value dupes context-score) :score)
                           "forward" ((forward-t (merge flex {:map map
                                                              :id id
                                                              :rtl rtl
                                                              :threshold threshold
                                                              :resolution resolution}) value dupes length context-score) :score)
                           "both" ((forward-t (merge flex {:map map
                                                           :id id
                                                           :rtl rtl
                                                           :threshold threshold
                                                           :resolution resolution})
                                              value ((reverse-t (merge flex {:map map
                                                                             :id id
                                                                             :rtl rtl
                                                                             :threshold threshold
                                                                             :resolution resolution}) length value dupes context-score) :dupes)
                                              length context-score) :score)
                           "full" ((full-t (merge flex {:map map
                                                        :id id
                                                        :rtl rtl
                                                        :threshold threshold
                                                        :resolution resolution}) length value dupes context-score) :score)
                           "default" ((default-t (merge flex {:map map
                                                              :id id
                                                              :threshold threshold
                                                              :resolution resolution
                                                              :depth depth}) dupes value context-score word-length i words) :score))
                         score)))))

(defn add [{:keys [ids tokenize split filter threshold depth resolution map rtl] :as flex}
           id content callback -skip-update -recall]
  (cond
    (and content
         (string? content)
         (or id (= id 0))
         (and (ids (str "@" id)) (not -skip-update))) (update-flex flex content nil nil)
    (and content
         (string? content)
         (or id (= id 0))
         (not -recall)
         callback) (callback (add flex id content nil -skip-update true));;ver como aplico callback, aridad y como son las funciones que ingreso a traves de el
    (and content
         (string? content)
         (or id (= id 0))) (let [content (encode-f flex content)];;ver porque (content) esta entre parentesis en el js
                             (if (not (count content))
                               (assoc flex :content content);;ver si tengo que meterlo adentro o armo un vector o un mapa con flex mas content
                               (let [tokenizer tokenize
                                     words (if (fn? tokenizer)
                                             (tokenizer content)
                                             (str/split content split))
                                     words (if filter
                                             (filter-words words filter)
                                             words)
                                     dupes {:ctx {}}
                                     word-length (count words)
                                     fff (for-add (merge flex {:map map
                                                               :id id
                                                               :rtl rtl
                                                               :threshold threshold
                                                               :resolution resolution
                                                               :depth depth}) words word-length tokenizer dupes)
                                     fff (assoc-in fff [ids (str "@" id)] 1)
                                     fff (merge fff {:tokenizer tokenizer
                                                     :words words
                                                     :word-length word-length})]
                                 fff)))
    :else flex))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;INTERSECT;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-page [cursor page result]
  ;; TODO str page?
  (if cursor
    {:page cursor, :next (if page (str page) nil), :result result}
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

(defn length-z-true [result bool arrays pointer]
  (if (not bool)
    (let [result (first arrays)]
      (if pointer
        {:pointer (Character/digit (first pointer) 10) :result result}
        {:pointer pointer :result result}))
    {:pointer pointer :result result}))

(defn for-first-result-true [i first-result result-length result check-not countt]
  (loop [i i
         id (get first-result i)
         countt countt
         result result]
    (if (= i result-length) {:countt countt
                             :result result}
        (recur (inc i)
               (get first-result (inc i))
               (if (not (get check-not (str "@" id)))
                 (inc countt)
                 countt)
               (if (not (get check-not (str "@" id)))
                 (assoc result countt id)
                 result)))))

(defn first-result-true
  "pointer es un string numerico o un numero"
  [first-result has-not pointer result check-not countt]
  (let [result-length (count first-result)]
    (if has-not
      (if pointer
        (for-first-result-true (if (number? pointer) pointer (Integer/parseInt pointer)) first-result result-length result check-not countt)
        (for-first-result-true 0 first-result result-length result check-not countt))
      {:result first-result})))

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
                                  (or is-final-loop bool-or))
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
           i-t (init-true first-result has-not check-not check has-and result arr countt)
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
                        nil
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

(defn intersect [arrays limit cursor bool has-not]
  (let [result []
        length-z (count arrays)
        pointer (if (true? cursor) "" (and cursor (str/split cursor #":")))
        cursor (if (true? cursor) 0 nil)];;ver porque cuando activo esto falla
    (if (< 1 length-z)
      (let [lz (length-z>1 pointer nil length-z arrays cursor nil has-not result limit)]
        (if (lz :return)
          (lz :return)
          (if (pos? length-z)
            (create-page cursor nil ((length-z-true result bool arrays (lz :pointer)) :result))
            (if limit
              (limit-true (lz :result) (lz :pointer) limit cursor)
              (create-page cursor nil (lz :result))))))
      (if (pos? length-z)
        (create-page cursor nil ((length-z-true result bool arrays pointer) :result))
        (if limit
          (limit-true result pointer limit cursor)
          (create-page cursor nil result))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;SEARCH;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn for-search-inner [{:keys [map threshold resolution] :as flex}
                        value]
  (loop [z 0
         map-value (and (get map z) (get-in map [z value]))
         map-check []
         countt 0
         map-found false]
    (if (= z (- resolution threshold)) (merge flex {:map-check map-check
                                                    :countt countt
                                                    :map-found map-found});;en realidad map-check y map-found los tengo que poner afuera de flex
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

(defn for-search [{:keys [map] :as flex} ;;para resolution hace una asignacion interna... significa que el resolution global no lo toca?
                  words use-contextual ctx-map length]
  (loop [a 0
         value (get words a)
         ctx-root nil ;;ctx hace referencia a contextual
         check-words {}
         map-check []
         map-found false
         countt 0
         map (if use-contextual (get ctx-map ctx-root) map)
         check []]
    (cond (= a length) (merge flex {:ctx-root ctx-root ;;en realidad estos 3 tendrian que ir fuera de flex
                                    :check-words check-words
                                    :check check});;ver por las dudas si no falta found en estas 2 primeras condiciones de terminacion
          (and value
               use-contextual
               (not ctx-root)
               (not (get ctx-map value))) (merge flex {:ctx-root ctx-root
                                                       :check-words check-words
                                                       :check check
                                                       :return []});;este es return result
          (and value
               (not (get check-words value))
               (not map-found)) (merge flex {:found false
                                             :ctx-root ctx-root
                                             :check-words check-words
                                             :check check
                                             :return []});;BREAK
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
                   (let [for-in (for-search-inner flex value)]
                     (for-in :map-check))
                   map-check);;ver si el siguiente if, map-found, modifica a map-check cuando aplica sobre el concat.apply
                 (if (and value;;map-found
                          (not (get check-words value))
                          map)
                   (let [for-in (for-search-inner flex value)]
                     (for-in :map-found))
                   map-found)
                 (if (and value;;countt
                          (not (get check-words value))
                          map)
                   (let [for-in (for-search-inner flex value)]
                     (for-in :countt))
                   countt)
                 (if (and value;;map
                          (not (get check-words value)))
                   (if use-contextual (get ctx-map ctx-root) map)
                   map)
                 (if (and value;;check
                          (not (get check-words value))
                          map-found)
                   (assoc check (count check) (if (< 1 countt)
                                                (concat map-check (concat [] map-check));;corroborar si esta bien y ver si en el js al aplicar cncat.apply sobre el objeto para obtener el resultado tambien estoy modificando ese objeto en cuestion
                                                (get map-check 0)))
                   check)))))

(defn search [{:keys [threshold tokenize split filter depth ctx limit] :as flex}
              query callback -recall]
  (let [callback (if (and limit (fn? limit))
                   limit
                   callback)
        limit (if (and limit (fn? limit))
                1000
                (if (pos? limit)
                  limit
                  (or (= limit 0) 1000)))
        threshold (if (pos? threshold) threshold 0);;corroborar si threshold y limit estan bien asignados
        flex (merge flex {:limit limit :threshold threshold})]
    (if (and (not -recall)
             callback)
      (callback (search flex query nil true));;callback deberia devolver flex....verificarlo. no le agregue los demas objetos modificados porque esta haciendo una recursion y teoricamente tendria que estar terminando por alguna de las otras salidas en las cuales si estarian incluidos. estoy suponiendo que estoy devolviendo this aca
      (if (or (not query)
              (not (string? query)))
        [flex {:result []}];;ver si hacen falta agregar todo lo demas modificado
        (let [query (encode-f flex query)]
          (if (not (count query))
            [flex {:result []}]
            (let [words (if (fn? tokenize)
                          (tokenize query)
                          (str/split query split))
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
              (if (or (not use-contextual) (= ctx-map ctx))
                (let [fs (for-search flex words use-contextual ctx-map length)]
                  (if (fs :return)
                    [flex fs]
                    (if found
                      [flex (merge fs {:result (intersect (fs :check) limit nil nil nil)})]
                      [flex (merge fs {:result []})])));;ver porque intersect no tiene has-and y porque js usa SUPPORT_SUGGESTION && suggest como argumentos
                [flex {:result []}]))))))))



(def iniciado (init {:presets "match"}))

(def texto (add iniciado 1 "el perro iba caminando por el parque" nil nil nil))

(search (merge texto {:limit 3}) "perr" nil nil)

(do (prn "2")
    (let [a 1]
      [a]))

#_(INTERSECT
   (LENGTH-Z > 1
             (FOR
              INIT
              INNER-FOR)
             (FIRST-RESULT))
   (LENGTH-Z)
   (LIMIT))