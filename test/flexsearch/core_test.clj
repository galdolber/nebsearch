(ns flexsearch.core-test
  (:require [clojure.test :refer [deftest is]]
            [flexsearch.core :as f]))

(deftest sort-by-length-down
  (is (= (f/sort-by-length-down "abc" "ab") -1))
  (is (= (f/sort-by-length-down "abc" "abc") 0))
  (is (= (f/sort-by-length-down "abc" "abcd") 1))
  (is (= (f/sort-by-length-down "aáG7/(..*)bc" "aahd66.- & -ººº") 1)))

(deftest collapse-repeating-chars
  (is (= (f/collapse-repeating-chars "") ""))
  (is (= (f/collapse-repeating-chars " ") " "))
  (is (= (f/collapse-repeating-chars "ea") "ea"))
  (is (= (f/collapse-repeating-chars "aa") "a"))
  (is (= (f/collapse-repeating-chars "-") "-"))
  (is (= (f/collapse-repeating-chars "-//") "-/"))
  (is (= (f/collapse-repeating-chars "  ") " "))
  (is (= (f/collapse-repeating-chars "eaaee") "eae"))
  (is (= (f/collapse-repeating-chars "hello eeaaa") "helo ea"))
  (is (= (f/collapse-repeating-chars "ihello") "ielo"))
  (is (= (f/collapse-repeating-chars "Hhell99o") "Hel9o"))
  (is (= (f/collapse-repeating-chars "h4ell..o") "h4el.o"))
  (is (= (f/collapse-repeating-chars "HhHello") "HHelo"))
  (is (= (f/collapse-repeating-chars "Hhhello") "Helo"))
  (is (= (f/collapse-repeating-chars "Hhhááaenññllo") "Háaenñlo"))
  (is (= (f/collapse-repeating-chars "ññ") "ñ"))
  (is (= (f/collapse-repeating-chars "ññ99-.au´s+eçd++/ & ") "ñ9-.au´s+eçd+/ & "))
  (is (= (f/collapse-repeating-chars "hhHhHHkjnsKJNnNNññhHHh") "hHHkjnsKJNnNñH"))
  (is (= (f/collapse-repeating-chars "ñcc-cççc99*/(()$%2@@)ñ") "ñc-cçc9*/()$%2@)ñ")))

(deftest replace-regexes
  ;;simple
  (is (= (f/replace-regexes "  " f/simple-regex) " "))
  (is (= (f/replace-regexes "--  " f/simple-regex) " "))
  (is (= (f/replace-regexes "abc & abc" f/simple-regex) "abk and abk"))
  (is (= (f/replace-regexes "asd dfd" f/simple-regex) "asd dfd"))
  (is (= (f/replace-regexes "asd.dfd." f/simple-regex) "asddfd"))
  (is (= (f/replace-regexes "asd/lkal" f/simple-regex) "asd lkal"))
  (is (= (f/replace-regexes "asdlk-al" f/simple-regex) "asdlk al"))
  (is (= (f/replace-regexes "asdááóññsbplk-al" f/simple-regex) "asdaaonnsbplk al"))
  (is (= (f/replace-regexes "ahHh & h-/s/...d  lk-a 99*/´ñlLKJHGñççññl" f/simple-regex) "ahh and h s d lk a 99 nlnkknnl"))
  ;;advanced
  (is (= (f/replace-regexes "aesdlkael" f/advanced-regex) "asdlkal"))
  (is (= (f/replace-regexes "asauouaeioiuuioiauoaiaaieieyydadthtdhdlk-zs" f/advanced-regex) "asauioiuuioiauiaeiiiydattdhdlk-s"))
  (is (= (f/replace-regexes "aspszzshckkkcphhfpfptdttdf-dlkal" f/advanced-regex) "aspsskkkcfhffptttdf-dlkal"))
  (is (= (f/replace-regexes "ayasckkckkhscshhcskthdtpfdlk-al" f/advanced-regex) "eiaskkkkhscshcskttfdlk-al"))
  ;;extra
  (is (= (f/replace-regexes "" f/extra-regex) ""))
  (is (= (f/replace-regexes "H  -/h" f/extra-regex) "H  -/h"))
  (is (= (f/replace-regexes "aççcçccbc & abc" f/extra-regex) "ççkçkkbk & bk"))
  (is (= (f/replace-regexes "n" f/extra-regex) "m"))
  (is (= (f/replace-regexes "vw" f/extra-regex) "ff"))
  (is (= (f/replace-regexes "aecqgpze-/35168askjhHGTFR" f/extra-regex) "kkkbs-/35168skjhHGTFR"))
  (is (= (f/replace-regexes "pdenwvvwwddj" f/extra-regex) "btmfffffttj"))
  (is (= (f/replace-regexes "anaaaáéìòò" f/extra-regex) "máéìòò"))
  (is (= (f/replace-regexes "ddndzzdnt" f/extra-regex) "ttmtsstmt"))
  ;;balance
  (is (= (f/replace-regexes "" f/balance-regex) ""))
  (is (= (f/replace-regexes " " f/balance-regex) " "))
  (is (= (f/replace-regexes "  " f/balance-regex) " "))
  (is (= (f/replace-regexes " - /" f/balance-regex) " "))
  (is (= (f/replace-regexes "abc & abc-7/aeeaqeeáèì.,88" f/balance-regex) "abc abc 7 aeeaqee88")))

(deftest global-encoder-icase
  (is (= (f/global-encoder-icase "Hello") "hello"))
  (is (= (f/global-encoder-icase "ihello") "ihello"))
  (is (= (f/global-encoder-icase "LKAJSDL") "lkajsdl")))

(deftest global-encoder-simple
  (is (= (f/global-encoder-simple "") ""))
  (is (= (f/global-encoder-simple " ") ""))
  (is (= (f/global-encoder-simple "Hellóñ") "hellon"))
  (is (= (f/global-encoder-simple "Ab-cab.c") "ab kabk"))
  (is (= (f/global-encoder-simple "Abc & á   /-ççSs83bc") "abk and a kkss83bk")))

(deftest global-encoder-advanced
  ;;true
  (is (= (f/global-encoder-advanced "áAc" true) "aak"))
  (is (= (f/global-encoder-advanced "-pA/9/& " true) " pa 9 "))
  (is (= (f/global-encoder-advanced "A." true) "a"))
  (is (= (f/global-encoder-advanced "" true) ""))
  (is (= (f/global-encoder-advanced "ññ" true) "nn"))
  (is (= (f/global-encoder-advanced "kj34KJH/(..,7/´s`wçç``^^)" true) "kj34kjh 7 swkk"))
  (is (= (f/global-encoder-advanced "HhhHHkj34KJH/sh(.´F´RÉáászááhé.-.??uo.,7/´s`wçç``^^)" true) "hhhhhkj34kjh sfreaasaahe u7 swkk"))
  (is (= (f/global-encoder-advanced "ñññ" true) "nnn"))
  ;;false
  (is (= (f/global-encoder-advanced "" false) ""))
  (is (= (f/global-encoder-advanced "á" false) "a"))
  (is (= (f/global-encoder-advanced "Áe" false) "ae"))
  (is (= (f/global-encoder-advanced "kj34KJH/(..,7/´s`wçç``^^)" false) "kj34kj 7 swk"))
  (is (= (f/global-encoder-advanced "ññ" false) "n")))

(deftest global-encoder-extra
  (is (= (f/global-encoder-extra "lsdlkjn83298)(/KMHB.,.,KKnnnaññ") "lstlkjm83298 kmbkm"))
  (is (= (f/global-encoder-extra "lsdlkznñd298)(ppB.,.,KKn and nnddaññ") "lstlksmt298bkm amt nmtm"))
  (is (= (f/global-encoder-extra "lsdlkjn8vw3298)(/Kaei y MHB.,.ww,KKnnvnaññ") "lstlkjm8f3298 k y mbfkmfm"))
  (is (= (f/global-encoder-extra "lsdlkjn8 cc329c8)(/KMHgB.,.qQ,KKnQnnaññ") "lstlkjm8 k329k8 kmkbkmkm"))
  (is (= (f/global-encoder-extra "lsdlkszjn8cc3298)(/KççMHaeoeB.,.,KKnnnaññ") "lstlksjm8k3298 kmbkm")))

(deftest global-encoder-balance
  (is (= (f/global-encoder-balance "") ""))
  (is (= (f/global-encoder-balance " ") " "))
  (is (= (f/global-encoder-balance "cc") "c"))
  (is (= (f/global-encoder-balance "ççpvwW") "pvw"))
  (is (= (f/global-encoder-balance "-") " "))
  (is (= (f/global-encoder-balance "-/") " "))
  (is (= (f/global-encoder-balance "hhHçkjahsH- & aslk288jjjshy`s´dáè ae ou cc k") "hkjas aslk28jsysd ae ou c k"))
  (is (= (f/global-encoder-balance "kjahsH- & aslk288jjjshy`s´dáè ae ou cc k") "kjas aslk28jsysd ae ou c k")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;ADD-INIT;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-page
  (is (map? (f/create-page "kjnk" "kjask" "asdas")))
  (is (string? ((f/create-page "kjnk" "kjask" "asdas") :next)))
  (is (vector? ((f/create-page "kjnk" "kjask" [1 2 3]) :result)))
  (is (= ((f/create-page "kjnk" "kjask" "asdas") :next) "kjask"))
  (is (= (f/create-page nil "kjask" "asdas") "asdas"))
  (is (= true ((f/create-page true "kjask" "asdas") :page)))
  (is (= ((f/create-page "kjnk" false "asdas") :next) nil)))

(deftest build-dupes
  (is (vector? (f/build-dupes {:resolution 9 :threshold 0})))
  (is (= (f/build-dupes {:resolution 7 :threshold nil}) [{} {} {} {} {} {} {}])))

(deftest init
  #_(is (= (f/init {:resolution 9 :threshold 9 :preset :memory :encoder nil})
           {:async false
            :filterer nil
            :timer 0
            :encoder nil
            :threshold 0
            :resolution 1
            :preset :memory
            :split #"\W+"
            :cache false
            :tokenize "strict"
            :id {}
            :fmap [{}]
            :depth 0
            :encode "extra"
            :ctx {}
            :doc false
            :worker false
            :rtl false}));;falla por el regex aun sacandolo del def y poniendolo aparte
  ;;ESTA LISTO EL OTRO TEST QUE FALLA POR LA FUNCION EN LIMPIEZA
  )

(deftest encode-f
  (is (string? (f/encode-f {:encode "balance" :stemmer {"ational" "ate"} :matcher f/simple-regex} "dfsdd    dfational")))
  (is (= (f/encode-f {:encode "balance" :stemmer {"ational" "ate"} :matcher f/simple-regex} "dfsdd    dfational") "dfsd dfate")))

(deftest filter-words
  (is (vector? ((f/filter-words [] #{"zsc"}) :filtered)))
  (is (number? ((f/filter-words [] #{"zsc"}) :countt)))
  (is (= (f/filter-words ["and" "sdfsdf"] #{"and"}) {:filtered ["sdfsdf"], :countt 1}))
  (is (= (f/filter-words ["and" "sdfsdf"] #{}) {:filtered ["and" "sdfsdf"], :countt 2}))
  (is (= (f/filter-words ["and" "sdfsdf"] #{"zsc"}) {:filtered ["and" "sdfsdf"], :countt 2}))
  (is (= (f/filter-words [] #{"zsc"}) {:filtered [], :countt 0}))
  (is (= ((f/filter-words [] #{"zsc"}) :countt) 0))
  (is (= (count ((f/filter-words [] #{"zsc"}) :filtered)) 0))
  (is (= ((f/filter-words ["and"] #{"zsc"}) :countt) 1))
  (is (= (count ((f/filter-words ["and"] #{"zsc"}) :filtered)) 1)))

(deftest add-index
  (is (= (f/add-index {:map ["asd" "as" "a"] :id 1 :threshold 5 :resolution 4} {"_ctx" 1} "_ctx" 1.5 2) 1))
  (is (= (f/add-index {:map [{"asd" 1} {"as" 2}  {"a" 3}] :id 1 :threshold 5 :resolution 2} {"_ctx" 1} "aaa" 1.5 2)
         {:score 1.5 :dupes {"_ctx" 1 "aaa" 1.5} :arr nil}))
  (is (= (f/add-index {:map [{} {} {} {}] :id 2 :threshold 2 :resolution 2} {"asd" 1} "d" 1 1)
         {:score 2 :dupes {"asd" 1 "d" 2} :arr {"d" [] 1 2}}))
  (is (= (f/add-index {:map [{} {} {}] :id 2 :threshold 2 :resolution 2} {"asd" 1 \a 2 "as" 2} "asd" 1 1) 1))
  (is (= (f/add-index {:map [{} {} {}] :id 2 :threshold 2 :resolution 2} {"asd" 1 "as" 2} "a" 1 1)
         {:score 2, :dupes {"asd" 1, "as" 2, "a" 2}, :arr {"a" [], 1 2}}))
  (is (= (f/add-index {:map [{} {} {}] :id 2 :threshold 2 :resolution 2} {"asd" 1 "as" 2 "a" 2} "" 1 1)
         {:score 2, :dupes {"asd" 1, "as" 2, "a" 2, "" 2}, :arr {"" [], 1 2}})))

(deftest remove-index
  (is (map? (f/remove-index {:map {:a [2 3 2] :b []} :id 2})))
  (is (= (f/remove-index {:map {:a [2 3 2] :b []} :id 2}) {:a [3 2], :b []}))
  (is (= (f/remove-index {:map {:a [2 3 2] :b []} :id 1}) {:a [2 3 2] :b []}))
  (is (= (f/remove-index {:map {:a [2 2 3 2] :b []} :id 2}) {:a [2 3 2] :b []}))
  (is (= (f/remove-index {:map {} :id 2}) nil))
  (is (= (f/remove-index {:map {:a [2 3 2] :b [2]} :id 2}) {:a [3 2]}))
  (is (= (f/remove-index {:map {:a [2 3 2 {:a []}] :b []} :id 2}) {:a [3 2 {:a []}] :b []}))
  (is (= (f/remove-index {:map {:a [{:a [1 2 3 2]} 2 3 2 {:a []}] :b []} :id 2}) {:a [{:a [1 3 2]} 3 2 {:a []}] :b []}))
  (is (= (f/remove-index {:map {:a [2 3 2 {:b [2 3]}]} :id 2}) {:a [3 2 {:b [2 3]}]}))
  (is (= (f/remove-index {:map {"a" ["fff" {"asd" 2 "fff" 5} 2 2 3 2] "dgfdf" 2} :id 2}) {"a" ["fff" {"asd" 2, "fff" 5} 2 3 2], "dgfdf" 2})))

(deftest remove-flex;;OJO QUE FALTA AGREGAR CALLBACK!!!
  (is (map? (f/remove-flex {:ids {} :depth 1 :map [{} {} {}] :resolution 2 :threshold 2 :ctx {"asd" 2}} 1 nil nil)))
  (is (= (f/remove-flex {:ids {} :depth 1 :map [{} {} {}] :resolution 2 :threshold 2 :ctx {"asd" 2}} 1 nil nil)
         {:ids {}, :depth 1, :map [{} {} {}], :resolution 2, :threshold 2, :ctx {"asd" 2}}))
  (is (= (f/remove-flex {:ids {"@1" "aaa"} :depth 1 :map [{} {} {}] :resolution 2 :threshold 2 :ctx {"asd" 2}} 1 nil nil)
         {:ids {}, :depth 1, :map [{} {} {}], :resolution 2, :threshold 2, :ctx {"asd" 2}})))


(deftest update-flex);;no haria falta porque esta dentro de add

(deftest reverse-t
  (is (map? (f/reverse-t {:map [{} {} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} 3 "asd" {"asd" 1} 1)))
  (is (= (f/reverse-t {:map [{} {} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} 3 "asd"  {"asd" 1} 1)
         {:score 4/3 :dupes {"asd" 1 "" 2/3 "d" 4/3} :arr nil :token "s"})))

(deftest forward-t
  (is (map? (f/forward-t {:map [{} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} "asd" {"asd" 1} 3 1)))
  (is (= (f/forward-t {:map [{} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} "asd" {"asd" 1} 3 1)
         {:score 2 :dupes {"asd" 1 "a" 2 "as" 2} :arr {"as" [] 1 2} :token "asd"}))
  (is (= (f/forward-t {:map [{"xcxc" :10} {"fghfgh" :88} {}] :id 2 :rtl false :threshold 2 :resolution 3} "fghfgh" {"asd" 1} 3 1)
         {:score 2 :dupes {"asd" 1 "f" 2 "fg" 2 "fgh" 2} :arr {"xcxc" :10 "fgh" [] 2 2} :token "fghf"})))

(deftest full-t
  (is (map? (f/full-t {:map [{} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} 3 "asd"  {"asd" 1} 1)))
  (is (= (f/full-t {:map [{} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} 3 "asd"  {"asd" 1} 1)
         {:dupes {"asd" 1, "as" 2, "a" 2, "sd" 4/3, "s" 4/3, "d" 2/3}, :partial-score 0})))

(deftest default-t
  (is (= (f/default-t {:map [{} {} {} {} {}] :id 2 :threshold 1 :resolution 3 :depth 2 :ctx {"_ctx" [{}]}}  {"asd" 1, :ctx {"asd" 2}} "asd" 1 3 2 ["_ctx" "aaa"])
         [{:map [{} {} {} {} {}]
           :id 2
           :threshold 1
           :resolution 3
           :depth 2
           :ctx {"_ctx" [{}] "asd" [{} {}]}}
          {:dupes {"asd" 1 :ctx {"asd" 2}}}])))

(deftest for-add;;FALTAN PROBAR LOS OTROS TOKENIZERS
  (is (map? (f/for-add {:map [{} {} {}] :id 2 :rtl false :threshold 3 :resolution 5 :depth 2 :ctx {"perro" 1}} ["perro" "gato" "cama"] 4 "forward" {"asd" 1})))
  (is (= (f/for-add {:map [{} {} {}] :id 2 :rtl false :threshold 3 :resolution 5 :depth 2 :ctx {"perro" 1}} ["perro" "gato" "cama"] 4 "forward" {"asd" 1})
         {:context-score 1/4
          :value nil
          :threshold 3
          :resolution 5
          :token "cama"
          :dupes {"pe" 4
                  "p" 4
                  "gat" 15/4
                  "cam" 7/2
                  "ca" 7/2
                  "asd" 1
                  "ga" 15/4
                  "cama" 7/2
                  "g" 15/4
                  "gato" 15/4
                  "per" 4
                  "perr" 4
                  "perro" 4
                  "c" 7/2}
          :id 2
          :score 7/2
          :length 0
          :depth 2
          :ctx {"perro" 1}
          :map [{} {} {}]
          :rtl false})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;INTERSECT;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest limit-true
  (is (map? (f/limit-true [] 0 3 true)))
  (is ((f/limit-true [] 0 3 true) :page))
  (is ((f/limit-true [] 0 3 true) :next))
  (is ((f/limit-true [] 0 3 true) :result))
  (is (= (f/limit-true ["asd" "as" "a"] 4 2 true)
         {:page true, :next "2", :result ["asd" "as"]}))
  (is (= (f/limit-true ["asd" "as" "a"] 4 4 true)
         {:page true, :next "0", :result ["asd" "as" "a"]}))
  (is (= (f/limit-true ["asd" "as" "a"] 2 0 true)
         {:page true, :next "2", :result []}))
  (is (= (f/limit-true ["asd" "as" "a"] 2 2 true)
         {:page true, :next "0", :result ["a"]}))
  (is (= (f/limit-true "asd" 1 1 true)
         {:page true :next "2" :result [\s]})))

(deftest length-z-true
  (is (map? (f/length-z-true [] nil ["asd" "as" "a" 1 2 3] "asdfas")))
  (is (= (f/length-z-true [] false ["asd" "as" "a" 1 2 3] "65425")
         {:pointer 6, :result "asd"}))
  (is (= (f/length-z-true [] ["sdfsdf"] ["asd" "as" "a" 1 2 3] "65425")
         {:pointer "65425", :result []}))
  (is (= (f/length-z-true [] nil ["asd" "as" "a" 1 2 3] nil)
         {:pointer nil, :result "asd"})))

(deftest first-result-true
  (is (map? (f/first-result-true ["a" "b" "c"] true "3" ["asd" "as" "a"] {"@asd" 2} 1)))
  (is (= (f/first-result-true ["a" "b" "c"] true "3" ["asd" "as" "a"] {"@asd" 2} 1)
         {:countt 1 :result ["asd" "as" "a"]}))
  (is (= (f/first-result-true ["a" "b" "c"] true false ["asd" "as" "a"] {"@asd" 2} 1)
         {:countt 4, :result ["asd" "a" "b" "c"]}))
  (is (= (f/first-result-true ["a" "b" "c"] true 3 ["asd" "as" "a"] {"@asd" 2} 1)
         {:countt 1, :result ["asd" "as" "a"]}))
  (is (= (f/first-result-true ["a" "b" "c"] false false ["asd" "as" "a"] {"@asd" 2} 1)
         {:result ["a" "b" "c"]})))

(deftest for-chico
  (is (map? (f/for-chico 1 3 ["asd" "as" "a"] true {"@asd" 2} false {"@asd" 3} true 1 ["a" "b" "c"] 2 3 true "2" true)))
  (is (= (f/for-chico 1 3 ["asd" "as" "a"] true {"@asd" 2} false {"@asd" 3} true 1 ["a" "b" "c"] 2 3 true "2" true)
         {:found true :countt 2 :result ["a" "b" "c"]}))
  (is (= (f/for-chico 1 3 ["asd" "as" "a"] true {"@asd" 2} false {"@asd" 3} true 1 ["a" "b" "c"] 2 3 true "aaa" true)
         {:found true :countt 2 :result ["a" "b" "c"]}))
  (is (= (f/for-chico 1 3 ["asd" "as" "a"] true {"@asd" 2} false {"@asd" 3} true 1 ["a" "b" "c"] 2 3 true true true)
         {:found true :countt 2 :result ["a" "b" "c"]}))
  (is (= (f/for-chico 1 3 ["asd" "as" "a"] true {"@asd" 2} false {"@asd" 3} true 1 ["a" "b" "c"] 2 3 true false true)
         {:found true :countt 2 :result ["a" "b" "c"]}))
  (is (= (f/for-chico 1 3 ["asd" "as" "a"] true {"@asd" 2} false {"@asd" 3} true 1 ["a" "b" "c"] 2 3 true 3 true)
         {:found true :countt 2 :result ["a" "b" "c"]}))
  (is (= (f/for-chico 0 1 "a" true {} false {} false 3 ["a" "b" "c" "sdfsdfs"] 0 8 false 3 false)
         {:found false, :countt 0, :result ["a" "b" "c" "sdfsdfs"]})))
;;FALTA PROBAR EL RETURN

(deftest init-true
  (is (map? (f/init-true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} true ["a" "b" "c"] ["a" "b" "c"] 2)))
  (is (= (f/init-true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} true ["a" "b" "c"] ["a" "b" "c"] 2)
         {:first-result nil :init false :check {"@asd" 3 "@as" 1 "@a" 1} :result ["a" "b" "c"] :countt 2}))
  (is (= (f/init-true nil true {"@asd" 2} {"@asd" 3} true ["a" "b" "c"] ["a" "b" "c"] 2)
         {:first-result ["a" "b" "c"]}))
  (is (= (f/init-true nil false {} {} true ["a" "b" "c" "sdfsdfs"] "a" 0)
         {:first-result "a"})))

(deftest for-grande
  (is (map? (f/for-grande 3 1 2 ["1" "asdd" "rrr"] true 2 true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} ["a" "b" "c"] 2 3 1 false)))
  (is (= (f/for-grande 3 1 2 ["1" "asdd" "rrr"] true 2 true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} ["a" "b" "c"] 2 3 1 false)
         {:first-result ["asd" "as" "a"] :result ["a" "b" "c"] :countt 2}))
  (is (= (f/for-grande 3 1 2 ["1" "asdd" "rrr"] true 2 true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} ["a" "b" "c"] 2 3 1 true)
         {:first-result ["asd" "as" "a"] :result ["a" "b" "c"] :countt 2}))
  (is (= (f/for-grande 3 1 2 ["1" "asdd" "rrr"] true 2 true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} ["a" "b" "c"] 2 3 1 8)
         {:first-result ["asd" "as" "a"] :result ["a" "b" "c"] :countt 2}))
  (is (= (f/for-grande 3 1 2 ["1" "asdd" "rrr"] true 2 true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} ["a" "b" "c"] 2 3 1 "8")
         {:first-result ["asd" "as" "a"] :result ["a" "b" "c"] :countt 2}))
  (is (= (f/for-grande 3 1 2 ["1" "asdd" "rrr"] true 2 true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} ["a" "b" "c"] 2 3 1 "aaa")
         {:first-result ["asd" "as" "a"] :result ["a" "b" "c"] :countt 2}))
  (is (= (f/for-grande 0 2 1 ["a" "asdd" "rrr" "z"] false 2 true nil false {} {} ["a" "b" "c" "sdfsdfs"] 0 3 8 3)
         {:first-result "a", :result ["a" "b" "c" "sdfsdfs"], :countt 0}))
  (is (= (f/for-grande 0 2 1 ["a" "asdd" "rrr"] true 2 true nil true {} {} ["a" "b" "c"] 0 nil 8 false)
         {:first-result "a", :result ["a" "b" "c"], :countt 0})))
;;FALTAN PROBAR LOS 2 RETURNS

(deftest length-z>1
  (is (map? (f/length-z>1 "3" 2 1 ["a" "asdd" "rrr" "z"] false 2 false ["a" "b" "c" "sdfsdfs"] 8)))
  (is (= (f/length-z>1 "3" 2 1 ["a" "asdd" "rrr" "z"] false 2 false ["a" "b" "c" "sdfsdfs"] 8)
         {:first-result "a", :result "a", :countt 0, :pointer 3}))
  (is (= (f/length-z>1 false 2 1 ["a" "asdd" "rrr"] true 2 true ["a" "b" "c"] 8)
         {:first-result "a", :result [\a "b" "c"], :countt 1}))
  (is (= (f/length-z>1 false 2 1 [["a" "fxf"] ["asdd"] ["rrr"]] true 2 true ["a" "b" "c"] 8)
         {:first-result ["a" "fxf"], :result ["a" "fxf" "c"], :countt 2})));;VER SI ESTA BIEN LO DEL CARACTER \a

(deftest intersect
  (is (map? (f/intersect ["a" "asdd" "rrr"] 8 true ["not"] true)))
  (is (= (f/intersect ["a" "asdd" "rrr"] 8 true ["not"] true)
         {:page 0, :next nil, :result []})))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;FIN INTERSECT;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;SEARCH;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest for-search-inner
  (is (map? (f/for-search-inner {:map [{"asdasd" 1} {"asd" 2}] :threshold 1 :resolution 2} "asdasd")))
  (is (= (f/for-search-inner {:map [{"asdasd" 1} {"asd" 2}] :threshold 1 :resolution 2} "asdasd")
         {:map [{"asdasd" 1} {"asd" 2}], :threshold 1, :resolution 2, :map-check [1], :countt 1, :map-found true})))

(deftest for-search
  (is (map? (f/for-search {:resolution 2 :threshold 2 :map []} ["add" "as" "a"] false {"f" 1} 3)))
  (is (= (f/for-search {:resolution 2 :threshold 2 :map []} ["add" "as" "a"] false {"f" 1} 3)
         {:resolution 2, :threshold 2, :map [], :found false, :ctx-root nil, :check-words {}, :check [], :return []})))