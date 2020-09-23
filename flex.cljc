(ns datalite.flex
  (:require [clojure.string :as string]))

(def defaults
   {:encode "icase",
    :tokenize "forward",
    :split #"\W+",
    :cache false,
    :async false,
    :worker false,
    :rtl false,
    :resolution 9,
    :threshold 0,
    :depth 0})

(def presets
  {:memory {:encode "extra" :tokenize "strict" :threshold 0 :resolution 1}
   :speed {:encode "icase":tokenize "strict":threshold 1:resolution 3:depth 2}
   :match  {:encode "extra" :tokenize "full" :threshold 1 :resolution 3}
   :score {:encode "extra":tokenize "strict":threshold 1:resolution 9:depth 4}
   :balance {:encode "balance":tokenize "strict":threshold 0:resolution 3:depth 3}
   :fast {:encode "icase":tokenize "strict":threshold 8:resolution 9:depth 1}})

(defn sort_by_length_down [a b]
  (let [diff (- (count a) (count b))]
    (if (< diff 0) 1 (if diff (- 1) 0))))

(defn create_page [cursor page result]
  ;; TODO str page?
  (if cursor
    {:page cursor, :next (if page (str page) nil), :result result}
    result))

(defn add_index [map dupes value id partial_score context_score threshold resolution]
  (when (aget dupes value) (aget dupes value))
  (let [score (if partial_score
                (+
                 (* (- resolution (or threshold (/ resolution 1.5))) context_score)
                 (* (or threshold (/ resolution 1.5)) partial_score))
                context_score)]
    (aset dupes value score)
    (when (>= score threshold)
      ;; TODO?
      (let [arr (aget map (- resolution (bit-shift-right #_>> (+ score 0.5) 0)))]
        (set! arr (or (aget arr value) (aset arr value #js [])))
        (aset arr (.-length arr) id)))
    score))

(def char_prev_is_vowel #{\a \e \i \o \u \y})

(defn collapse-repeating-chars [string]
  "saca las repetidas y todas las h salvo la del principio"
  (loop [collapsed-string ""
         char-prev nil
         [[i char] & cx] (map-indexed vector string)]
    (if char
      (let [char-next (first cx)]
        (println collapsed-string char-prev i char cx char-next)
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
  (reduce (fn [str [regex rep]]
            (string/replace str regex rep))
          str regexp))

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

(defn global_encoder_icase [value] (string/lower-case value))

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
        (collapse_repeating_chars value)
        value))))

(defn global-encoder-extra [value]
  (when value
    (let [value (global-encoder-advanced-raw value)]
      (if (seq value)
        (collapse_repeating_chars
         (string/join
          " "
          (mapv (fn [current]
                  (if (seq current)
                    (str (first current)
                         (replace- (subs current 1) extra-regex))
                    current))
                (string/split value #" "))))
        value))))

(defn global_encoder_balance [value]
  (when value
    (collapse_repeating_chars
     (replace-
      (string/lower-case value)
      {#"[-/]" " " #"[^a-z0-9 ]" "" #"\\s+" ""}))))


(def global_encoder
  {:icase global_encoder_icase
   :simple global-encoder-simple
   :advanced global-encoder-advanced
   :extra global-encoder-extra
   :balance global_encoder_balance})

;;function init_stemmer(stem, encoder){const final = []; for(const key in stem){if(stem.hasOwnProperty(key)){const tmp = encoder ? encoder(key) : key; final.push(regex(tmp + "($|\\W)"), encoder ? encoder(stem[key]) : stem[key]);}} return final;}

(defn build-dupes [{:keys [resolution threshold]}]
  (vec (repeat (- resolution (or threshold 0)) {})))

(defn init [options]
  (let [{:keys [threshold resolution] :as options}
        (-> defaults (merge options) (merge (presets (:preset options))))
        options (update options :resolution #(if (<= % threshold) (inc threshold) %))
        {:keys [encoder resolution] :as options}
        (update options :encoder #(or (global_encoder %) %))
        options (update options :filterer #(when % (set (mapv encoder %))))]
    (into
     options
     {:fmap (build-dupes options)
      :ctx {}
      :id {}
      :timer 0})))

(defn encode [{:keys [matcher encoder stemmer]} value]
  (when value
    (-> value
        (replace- matcher)
        encoder
        (replace- stemmer))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn remove-flex [{:keys [ids depth fmap] :as flex} id callback recall]
  (let [index (str "@" id)]
    (if (ids index)
      (let [flex (reduce #(remove-index flex %) flex fmap)
            flex (if depth (remove-index flex id) flex)]
        (update flex :ids #(dissoc % index)))
      (throw (ex-info "Index not found" {:index id})))))

(defn update-flex [{:keys [ids] :as flex} id content callback]
  (let [index (str "@" id)]
    (if (ids index)
      (-> flex
          (remove-flex id)
          ;; TODO callback?
          (add-flex id content callback true))
      (throw (ex-info "Index not found" {:index id})))))

(defn forward-tokenizer [flex context_score dupes value]
  (let [letters (count value)]
    (reduce
     (fn [flex [a token]]
       (add-index flex fmap dupes token id
                  (if rtl (/ (inc a) letters) 1) context_score
                  threshold (dec resolution)))
     flex
     (mapv #(vector % (subs value 0 %)) (range letters)))))

(defn reverse-tokenizer [flex context_score dupes value]
  (let [letters (count value)]
    (reduce
     (fn [flex [a token]]
       (add-index flex fmap dupes token id
                  (/ (if rtl 1 (- letters a)) letters) context_score
                  threshold (dec resolution)))
     flex
     (mapv #(vector % (subs value % letters)) (range 1 letters)))))

(defn full-tokenizer [flex context_score dupes value]
  (let [letters (count value)]
    (reduce
     (fn [flex [x word]]
       (let [partial-score (if rtl (inc x) (/ (- letters x) letters))]
         (reduce
          (fn [flex [y token]]
            (add-index flex fmap dupes token id
                       partial-score context_score
                       threshold (dec resolution)))
          flex
          (mapv #(vector % (subs value x %)) (range x letters)))))
     flex
     (mapv #(vector % (subs value 0 %)) (range letters)))))

(defn default-tokenizer [{:keys [depth threshold] :as flex} context_score dupes value]
  ;; TODO score
  (let [flex #_score
        (add-index flex fmap dupes value id
                   1 context_score
                   threshold (dec resolution))]
    (if (and depth (> (count value) 1) (>= score threshold))
      (let [ctx-dupes (or (get-in dupes [:ctx value]) {})
            ;; TODO put back
            ctx-tmp (or (get-in flex [:ctx value]) (build-dupes flex))
            x (max (- i depth) 0)
            ;; count words?
            y (min (+ i depth 1) (count words))]
        (reduce
         (fn [flex x]
           (if (= x i)
             flex
             (add-index flex ctx-tmp ctx-dupes (nth words x) id
                        0 (- resolution (if (< x i) (- i x) (- x i)))
                        threshold (dec resolution))))
         flex (range x y)))
      flex)))

(defn add-flex [{:keys [encoder tokenizer filterer fmap
                        threshold depth resolution rtl] :as flex}
                id content callback skip-update recall]
  (if (and (string? content) id (zero? id)) ;; id zero?
    (let [index (str "@" id)]
      (if (and (ids index) (not skip-update))
        (update-flex flex id content)
        (if-let [content (encode flex content)]
          (let [words (tokenizer content)
                words (if filterer (filter-words words filterer) words)
                ;; TODO?
                dupes {:ctx {}}
                flex
                (reduce
                 (fn [flex [i value]]
                   (if value
                     (let [letters (count value)
                           context_score (/ (if rtl (inc i) (- letters i)) letters)]
                       (case tokenizer
                         :forward (forward-tokenizer flex context_score dupes value)
                         :reverse (reverse-tokenizer flex context_score dupes value)
                         :both (-> flex
                                   (reverse-tokenizer context_score dupes value)
                                   (forward-tokenizer context_score dupes value))
                         :full (full-tokenizer flex context_score dupes value)
                         (default-tokenizer flex context_score dupes value)))
                     flex))
                 flex (map-indexed vector words))]
            (assoc-in flex [:ids index] 1))
          flex)))))

(defn search [{:keys [tokenizer filterer resolution] :as flex}
              {:keys [cursor limit threshold suggest query] :as query}
              limit callback recall]
  (let [limit (or limit 1000)
        query (encode flex query)
        words (tokenizer query)
        words (if filterer (filter-words words filterer) words)
        word-count (count words)
        found true
        check []
        check-words {}
        ctx-root nil
        [use-contextual words] (if (and depth (= tokenize :strict))
                                 [true words]
                                 ;;TODO?
                                 [false (reverse (sort words))])
        a 0
        ctx-map nil]
    (if (or (not use-contextual) (= ctx-map (:ctx flex)))
      (loop [[value & ws] words]
        (if value
          (if use-contextual
            ))))))

const search = function(query, limit, callback, _recall){
            let result = [];
            let _query = query;
            let ctx_map;

            if(!use_contextual || (ctx_map = this._ctx)){
                for(; a < length; a++){
                    if(value){

                        if(use_contextual){

                            if(!ctx_root){

                                if(ctx_map[value]){

                                    ctx_root = value;
                                    check_words[value] = 1;
                                }
                                else if(!suggest){

                                    return result;
                                }
                            }

                            if(suggest && (a === length - 1) && !check.length){
                                use_contextual = false;
                                value = ctx_root || value;
                                check_words[value] = 0;
                            }
                            else if(!ctx_root){

                                continue;
                            }
                        }

                        if(!check_words[value]){

                            const map_check = [];
                            let map_found = false;
                            let count = 0;

                            const map = use_contextual ? ctx_map[ctx_root] : this._map;

                            if(map){

                                let map_value;

                                for(let z = 0; z < (resolution - threshold); z++){

                                    if((map_value = (map[z] && map[z][value]))){

                                        map_check[count++] = map_value;
                                        map_found = true;
                                    }
                                }
                            }

                            if(map_found){
                                ctx_root = value;
                                check[check.length] = (

                                    count > 1 ?

                                        map_check.concat.apply([], map_check)
                                    :
                                        map_check[0]
                                );
                            }
                            else if(!suggest){

                                found = false;
                                break;
                            }

                            check_words[value] = 1;
                        }
                    }
                }
            } else {
                found = false;
            }

            if(found) {
                result = (intersect(check, limit, cursor, suggest));
            }

            return result;
};
