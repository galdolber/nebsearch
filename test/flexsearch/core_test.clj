(ns flexsearch.core-test
  (:require [clojure.test :refer [deftest is]]
            [flexsearch.core :as f]
            [clojure.string :as string]))

(deftest collapse-repeating-chars
  (is (string? (f/collapse-repeating-chars "")))
  (is (= (f/collapse-repeating-chars "") ""))
  (is (= (f/collapse-repeating-chars " ") " "))
  (is (= (f/collapse-repeating-chars "ea") "ea"))
  (is (= (f/collapse-repeating-chars "aa") "a"))
  (is (= (f/collapse-repeating-chars "-") "-"))
  (is (= (f/collapse-repeating-chars "-//") "-/"))
  (is (= (f/collapse-repeating-chars "  ") " "))
  (is (= (f/collapse-repeating-chars "eaaee") "eae"))
  (is (= (f/collapse-repeating-chars "hello eeaaa") "helo ea"))
  (is (= (f/collapse-repeating-chars "h4ell..o") "h4el.o"))
  (is (= (f/collapse-repeating-chars "ññ") "ñ"))
  (is (= (f/collapse-repeating-chars "ññ99-.au´s+eçd++/ & ") "ñ9-.au´s+eçd+/ & "))
  (is (= (f/collapse-repeating-chars "ñcc-cççc99*/(()$%2@@)ñ") "ñc-cçc9*/()$%2@)ñ"))
  #_(is (= (f/collapse-repeating-chars "ihello") "ielo"))
  #_(is (= (f/collapse-repeating-chars "Hhell99o") "Hel9o"))
  #_(is (= (f/collapse-repeating-chars "HhHello") "HHelo"))
  #_(is (= (f/collapse-repeating-chars "Hhhello") "Helo"))
  #_(is (= (f/collapse-repeating-chars "Hhhááaenññllo") "Háaenñlo"))
  #_(is (= (f/collapse-repeating-chars "hhHhHHkjnsKJNnNNññhHHh") "hHHkjnsKJNnNñH")))

(deftest replace-regexes
  (is (string? (f/replace-regexes "  " f/simple-regex)))
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

(deftest encoder-icase
  (is (string? (f/encoder-icase "Hello")))
  (is (= (f/encoder-icase "Hello") "hello"))
  (is (= (f/encoder-icase "ihello") "ihello"))
  (is (= (f/encoder-icase "LKAJSDL") "lkajsdl")))

(deftest encoder-simple
  (is (string? (f/encoder-simple "")))
  (is (= (f/encoder-simple "") ""))
  (is (= (f/encoder-simple " ") ""))
  (is (= (f/encoder-simple "Hellóñ") "hellon"))
  (is (= (f/encoder-simple "HeLLèñ") "hellen"))
  #_(is (= (f/encoder-simple "Ab-cab.c") "ab kabk"));;doesn't remove special characters. can done with balance-regex
  #_(is (= (f/encoder-simple "Abc & á   /-ççSs83bc") "abk and a kkss83bk")))

(deftest encoder-advanced
  (is (string? (f/encoder-advanced "áAc")));;it used advanced-regex inside
  (is (= (f/encoder-advanced "áAc") "ac"))
  (is (= (f/encoder-advanced "áAc") "ac"))
  (is (= (f/encoder-advanced "áAc") "ac"))
  (is (= (f/encoder-advanced "áAc") "ac")))

#_(deftest global-encoder-extra
  (is (= (f/global-encoder-extra "lsdlkjn83298)(/KMHB.,.,KKnnnaññ") "lstlkjm83298 kmbkm"))
  (is (= (f/global-encoder-extra "lsdlkznñd298)(ppB.,.,KKn and nnddaññ") "lstlksmt298bkm amt nmtm"))
  (is (= (f/global-encoder-extra "lsdlkjn8vw3298)(/Kaei y MHB.,.ww,KKnnvnaññ") "lstlkjm8f3298 k y mbfkmfm"))
  (is (= (f/global-encoder-extra "lsdlkjn8 cc329c8)(/KMHgB.,.qQ,KKnQnnaññ") "lstlkjm8 k329k8 kmkbkmkm"))
  (is (= (f/global-encoder-extra "lsdlkszjn8cc3298)(/KççMHaeoeB.,.,KKnnnaññ") "lstlksjm8k3298 kmbkm")))

#_(deftest global-encoder-balance
  (is (= (f/global-encoder-balance "") ""))
  (is (= (f/global-encoder-balance " ") " "))
  (is (= (f/global-encoder-balance "cc") "c"))
  (is (= (f/global-encoder-balance "ççpvwW") "pvw"))
  (is (= (f/global-encoder-balance "-") " "))
  (is (= (f/global-encoder-balance "-/") " "))
  (is (= (f/global-encoder-balance "hhHçkjahsH- & aslk288jjjshy`s´dáè ae ou cc k") "hkjas aslk28jsysd ae ou c k"))
  (is (= (f/global-encoder-balance "kjahsH- & aslk288jjjshy`s´dáè ae ou cc k") "kjas aslk28jsysd ae ou c k")))


(deftest get-encoder
  (is (fn? (f/get-encoder :icase)))
  (is (= (f/get-encoder nil) f/encoder-icase))
  (is (= (f/get-encoder :icase) f/encoder-icase))
  (is (= (f/get-encoder :simple) f/encoder-simple))
  (is (= (f/get-encoder :advanced) f/encoder-advanced)))

(deftest encode-value
  (is (string? (f/encode-value "rationate" {:encoder (f/get-encoder :icase) :stemmer [[#"ate" "ational"]]})))
  (is (= (f/encode-value "rationate" {:encoder (f/get-encoder :icase) :stemmer [[#"ate" "ational"]]}) "rationational")))

(deftest filter-words
  (is (vector? (f/filter-words ["the" "cat" "and" "the" "dog" "or" "the" "coco"] #{"and" "or"})))
  (is (= (f/filter-words ["the" "cat" "and" "the" "dog" "or" "the" "coco"] #{"and" "or"})
         ["the" "cat" "the" "dog" "the" "coco"])));;it's ok filterer to be a set?

(deftest index-reverse
  (is (map? (f/index-reverse {} f/add-index "gato" 1)))
  (is (= (f/index-reverse {} f/add-index "gato" 1)
         {"gato" #{1}, "ato" #{1}, "to" #{1}, "o" #{1}})))

(deftest index-forward
  (is(map? (f/index-forward {} f/add-index "gato" 1)))
  (is (= (f/index-forward {} f/add-index "gato" 1)
         {"g" #{1}, "ga" #{1}, "gat" #{1}, "gato" #{1}})))

(deftest index-both
  (is(map? (f/index-both {} f/add-index "gato" 1)))
  (is (= (f/index-both {} f/add-index "gato" 1)
         {"gato" #{1}, "ato" #{1}, "to" #{1}, "o" #{1}, "g" #{1}, "ga" #{1}, "gat" #{1}})))

(def gato (f/index-full {} f/add-index "gato" 1))
(def gatorade (f/index-full {} f/add-index "gatorade" 2))
(def gato+gatorade (f/index-full gato f/add-index "gatorade" 2))
(def gato+gatorade-gato (f/index-full gato+gatorade f/remove-index "gato" 1))
(deftest index-full
  (is (map? gato))
  (is (= gato {"ato" #{1}
               "gat" #{1}
               "a" #{1}
               "ga" #{1}
               "t" #{1}
               "g" #{1}
               "gato" #{1}
               "to" #{1}
               "at" #{1}
               "o" #{1}}))
  (is (= gatorade {"d" #{2}
                   "rad" #{2}
                   "orade" #{2}
                   "tora" #{2}
                   "ad" #{2}
                   "ato" #{2}
                   "e" #{2}
                   "tor" #{2}
                   "atorad" #{2}
                   "gatora" #{2}
                   "gat" #{2}
                   "atora" #{2}
                   "atorade" #{2}
                   "gator" #{2}
                   "rade" #{2}
                   "ora" #{2}
                   "or" #{2}
                   "de" #{2}
                   "a" #{2}
                   "ga" #{2}
                   "gatorad" #{2}
                   "gatorade" #{2}
                   "t" #{2}
                   "ator" #{2}
                   "r" #{2}
                   "torade" #{2}
                   "ra" #{2}
                   "g" #{2}
                   "torad" #{2}
                   "gato" #{2}
                   "orad" #{2}
                   "to" #{2}
                   "at" #{2}
                   "o" #{2}
                   "ade" #{2}}))
  (is (= gato+gatorade {"d" #{2}
                        "rad" #{2}
                        "orade" #{2}
                        "tora" #{2}
                        "ad" #{2}
                        "ato" #{1 2}
                        "e" #{2}
                        "tor" #{2}
                        "atorad" #{2}
                        "gatora" #{2}
                        "gat" #{1 2}
                        "atora" #{2}
                        "atorade" #{2}
                        "gator" #{2}
                        "rade" #{2}
                        "ora" #{2}
                        "or" #{2}
                        "de" #{2}
                        "a" #{1 2}
                        "ga" #{1 2}
                        "gatorad" #{2}
                        "gatorade" #{2}
                        "t" #{1 2}
                        "ator" #{2}
                        "r" #{2}
                        "torade" #{2}
                        "ra" #{2}
                        "g" #{1 2}
                        "torad" #{2}
                        "gato" #{1 2}
                        "orad" #{2}
                        "to" #{1 2}
                        "at" #{1 2}
                        "o" #{1 2}
                        "ade" #{2}}))
(is(= gato+gatorade-gato {"d" #{2}
                          "rad" #{2}
                          "orade" #{2}
                          "tora" #{2}
                          "ad" #{2}
                          "ato" #{2}
                          "e" #{2}
                          "tor" #{2}
                          "atorad" #{2}
                          "gatora" #{2}
                          "gat" #{2}
                          "atora" #{2}
                          "atorade" #{2}
                          "gator" #{2}
                          "rade" #{2}
                          "ora" #{2}
                          "or" #{2}
                          "de" #{2}
                          "a" #{2}
                          "ga" #{2}
                          "gatorad" #{2}
                          "gatorade" #{2}
                          "t" #{2}
                          "ator" #{2}
                          "r" #{2}
                          "torade" #{2}
                          "ra" #{2}
                          "g" #{2}
                          "torad" #{2}
                          "gato" #{2}
                          "orad" #{2}
                          "to" #{2}
                          "at" #{2}
                          "o" #{2}
                          "ade" #{2}})))

(deftest get-indexer
  (is (fn? (f/get-indexer nil)))
  (is (= (f/get-indexer nil) f/index-forward))
  (is (= (f/get-indexer :forward) f/index-forward))
  (is (= (f/get-indexer :reverse) f/index-reverse))
  (is (= (f/get-indexer :both) f/index-both))
  (is (= (f/get-indexer :full) f/index-full)))

(deftest encode-value
  (is (= (f/encode-value "Rationate" {:encoder (f/get-encoder :icase) :stemmer [[#"ate" "ational"]]})
         "rationational")))


(deftest init
  (is (map? (f/init {:tokenizer false :split #"\W+" :indexer :forward :filter #{"and" "or"}})))
  (is (= ((:tokenizer (f/init {:tokenizer false :split #"\W+" :indexer :forward :filter #{"and" "or"}})) "asd fjfg")
         ["asd" "fjfg"]))
  (is (= (string/split "asdsa sada" (:split (f/init {:tokenizer false :split #"\W+" :indexer :forward :filter #{"and" "or"}})))
         ["asdsa" "sada"]))
  (is (= (dissoc (f/init {:tokenizer false :split #"\W+" :indexer :forward :filter #{"and" "or"}}) :tokenizer :split)
         {:ids {}
          :data {}
          :indexer f/index-forward
          :filter #{"or" "and"}
          :encoder f/encoder-icase})))
(def flex (f/init {:tokenizer false :split #"\W+" :indexer :forward :filter #{"and" "or"}}))

(deftest add-index
  (is (= (f/add-index {} "jorge" 1)
         {"jorge" #{1}})))
(def jorge (f/add-index {} "jorge" 1))

(deftest remove-index
  (is (= (f/remove-index jorge "jorge" 1)
         {"jorge" #{}}))
  (is (= (f/remove-index jorge "jorge" 2)
         {"jorge" #{1}})))

(deftest add-indexes
  (is (= (dissoc (f/add-indexes flex f/index-forward ["johnny" "alias" "deep"] 1) :tokenizer :split)
         {:ids {}
          :data
          {"d" #{1}
           "al" #{1}
           "dee" #{1}
           "johnn" #{1}
           "j" #{1}
           "johnny" #{1}
           "john" #{1}
           "de" #{1}
           "a" #{1}
           "deep" #{1}
           "alia" #{1}
           "alias" #{1}
           "joh" #{1}
           "ali" #{1}
           "jo" #{1}}
          :indexer f/index-forward
          :filter #{"or" "and"}
          :encoder f/encoder-icase})))
(def f-johnny (f/add-indexes flex f/index-forward ["johnny" "alias" "deep"] 1))

(deftest remove_indexes
  (is(= (dissoc (f/remove-indexes f-johnny f/index-forward ["alias" "deep"] 1) :tokenizer :split)
        {:ids {}
         :data
         {"d" #{}
          "al" #{}
          "dee" #{}
          "johnn" #{1}
          "j" #{1}
          "johnny" #{1}
          "john" #{1}
          "de" #{}
          "a" #{}
          "deep" #{}
          "alia" #{}
          "alias" #{}
          "joh" #{1}
          "ali" #{}
          "jo" #{1}}
         :indexer f/index-forward
         :filter #{"or" "and"}
         :encoder f/encoder-icase})))

(def pedro-gonzales (f/flex-add flex 1 "Pedro Gonzales"))
(def gonzales+garcia (f/flex-add pedro-gonzales 2 "Pedro Garcia"))

(deftest flex-add
  (is (= (dissoc (f/flex-add flex 1 "Pedro Gonzales") :tokenizer :split)
         {:ids {1 #{"pedro" "gonzales"}}
          :data
          {"pedro" #{1}
           "pe" #{1}
           "ped" #{1}
           "p" #{1}
           "gonz" #{1}
           "gonzale" #{1}
           "gonzal" #{1}
           "go" #{1}
           "pedr" #{1}
           "g" #{1}
           "gonzales" #{1}
           "gon" #{1}
           "gonza" #{1}}
          :indexer f/index-forward
          :filter #{"or" "and"}
          :encoder f/encoder-icase}))
  (is (= (dissoc (f/flex-add pedro-gonzales 1 "alias rambo") :tokenizer :split)
         {:ids {1 #{"rambo" "alias"}}
          :data
          {"pedro" #{}
           "al" #{1}
           "pe" #{}
           "ped" #{}
           "p" #{}
           "gonz" #{}
           "gonzale" #{}
           "gonzal" #{}
           "go" #{}
           "a" #{1}
           "pedr" #{}
           "r" #{1}
           "ra" #{1}
           "g" #{}
           "alia" #{1}
           "rambo" #{1}
           "alias" #{1}
           "ali" #{1}
           "gonzales" #{}
           "gon" #{}
           "gonza" #{}
           "ramb" #{1}
           "ram" #{1}}
          :indexer f/index-forward
          :filter #{"or" "and"}
          :encoder f/encoder-icase})))

(deftest flex-remove
  (is (= (dissoc (f/flex-remove pedro-gonzales 1) :tokenizer :split)
         {:ids {}
          :data
          {"pedro" #{}
           "pe" #{}
           "ped" #{}
           "p" #{}
           "gonz" #{}
           "gonzale" #{}
           "gonzal" #{}
           "go" #{}
           "pedr" #{}
           "g" #{}
           "gonzales" #{}
           "gon" #{}
           "gonza" #{}}
          :indexer f/index-forward
                    :filter #{"or" "and"}
          :encoder f/encoder-icase}))
  (is (= (dissoc (f/flex-remove gonzales+garcia 2) :tokenizer :split)
         {:ids {1 #{"pedro" "gonzales"}}
          :data
          {"pedro" #{1}
           "garci" #{}
           "pe" #{1}
           "ped" #{1}
           "p" #{1}
           "gonz" #{1}
           "gonzale" #{1}
           "gonzal" #{1}
           "garcia" #{}
           "gar" #{}
           "go" #{1}
           "ga" #{}
           "pedr" #{1}
           "g" #{1}
           "garc" #{}
           "gonzales" #{1}
           "gon" #{1}
           "gonza" #{1}}
          :indexer f/index-forward
          :filter #{"or" "and"}
          :encoder f/encoder-icase})))

(deftest flex-search
  (is (= (f/flex-search gonzales+garcia "gonz") #{1}))
  (is (= (f/flex-search gonzales+garcia "ga") #{2}))
  (is (= (f/flex-search gonzales+garcia "pedro") #{1 2}))
  (is (= (f/flex-search gonzales+garcia "nz") nil)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;ADD-INIT;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(deftest create-page
  (is (map? (f/create-page "kjnk" "kjask" "asdas")))
  (is (string? ((f/create-page "kjnk" "kjask" "asdas") :next)))
  (is (vector? ((f/create-page "kjnk" "kjask" [1 2 3]) :result)))
  (is (= ((f/create-page "kjnk" "kjask" "asdas") :next) "kjask"))
  (is (= (f/create-page nil "kjask" "asdas") "asdas"))
  (is (= true ((f/create-page true "kjask" "asdas") :page)))
  (is (= ((f/create-page "kjnk" false "asdas") :next) nil)))

#_(deftest build-dupes
  (is (vector? (f/build-dupes {:resolution 9 :threshold 0})))
  (is (= (f/build-dupes {:resolution 7 :threshold nil}) [{} {} {} {} {} {} {}])))

#_(deftest init
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

#_(deftest encode-f
  (is (string? (f/encode-f {:encode "balance" :stemmer {"ational" "ate"} :matcher f/simple-regex} "dfsdd    dfational")))
  (is (= (f/encode-f {:encode "balance" :stemmer {"ational" "ate"} :matcher f/simple-regex} "dfsdd    dfational") "dfsd dfate")))

#_(deftest filter-words
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

#_(deftest add-index
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

#_(deftest remove-index
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

#_(deftest remove-flex;;OJO QUE FALTA AGREGAR CALLBACK!!!
  (is (map? (f/remove-flex {:ids {} :depth 1 :map [{} {} {}] :resolution 2 :threshold 2 :ctx {"asd" 2}} 1 nil nil)))
  (is (= (f/remove-flex {:ids {} :depth 1 :map [{} {} {}] :resolution 2 :threshold 2 :ctx {"asd" 2}} 1 nil nil)
         {:ids {}, :depth 1, :map [{} {} {}], :resolution 2, :threshold 2, :ctx {"asd" 2}}))
  (is (= (f/remove-flex {:ids {"@1" "aaa"} :depth 1 :map [{} {} {}] :resolution 2 :threshold 2 :ctx {"asd" 2}} 1 nil nil)
         {:ids {}, :depth 1, :map [{} {} {}], :resolution 2, :threshold 2, :ctx {"asd" 2}})))

#_(deftest reverse-t
  (is (map? (f/reverse-t {:map [{} {} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} 3 "asd" {"asd" 1} 1)))
  (is (= (f/reverse-t {:map [{} {} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} 3 "asd"  {"asd" 1} 1)
         {:score 4/3 :dupes {"asd" 1 "" 2/3 "d" 4/3} :arr nil :token "s"})))

#_(deftest forward-t
  (is (map? (f/forward-t {:map [{} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} "asd" {"asd" 1} 3 1)))
  (is (= (f/forward-t {:map [{} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} "asd" {"asd" 1} 3 1)
         {:score 2 :dupes {"asd" 1 "a" 2 "as" 2} :arr {"as" [] 1 2} :token "asd"}))
  (is (= (f/forward-t {:map [{"xcxc" :10} {"fghfgh" :88} {}] :id 2 :rtl false :threshold 2 :resolution 3} "fghfgh" {"asd" 1} 3 1)
         {:score 2 :dupes {"asd" 1 "f" 2 "fg" 2 "fgh" 2} :arr {"xcxc" :10 "fgh" [] 2 2} :token "fghf"})))

#_(deftest full-t
  (is (map? (f/full-t {:map [{} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} 3 "asd"  {"asd" 1} 1)))
  (is (= (f/full-t {:map [{} {} {}] :id 2 :rtl false :threshold 2 :resolution 3} 3 "asd"  {"asd" 1} 1)
         {:dupes {"asd" 1, "as" 2, "a" 2, "sd" 4/3, "s" 4/3, "d" 2/3}, :partial-score 0})))

#_(deftest default-t
  (is (= (f/default-t {:map [{} {} {} {} {}] :id 2 :threshold 1 :resolution 3 :depth 2 :ctx {"_ctx" [{}]}}  {"asd" 1, :ctx {"asd" 2}} "asd" 1 3 2 ["_ctx" "aaa"])
         [{:map [{} {} {} {} {}]
           :id 2
           :threshold 1
           :resolution 3
           :depth 2
           :ctx {"_ctx" [{}] "asd" [{} {}]}}
          {:dupes {"asd" 1 :ctx {"asd" 2}}}])))

#_(deftest for-add;;FALTAN PROBAR LOS OTROS TOKENIZERS
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
#_(deftest limit-true
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

#_(deftest length-z-true
  (is (map? (f/length-z-true [] nil ["asd" "as" "a" 1 2 3] "asdfas")))
  (is (= (f/length-z-true [] false ["asd" "as" "a" 1 2 3] "65425")
         {:pointer 6, :result "asd"}))
  (is (= (f/length-z-true [] ["sdfsdf"] ["asd" "as" "a" 1 2 3] "65425")
         {:pointer "65425", :result []}))
  (is (= (f/length-z-true [] nil ["asd" "as" "a" 1 2 3] nil)
         {:pointer nil, :result "asd"})))

#_(deftest first-result-true
  (is (map? (f/first-result-true ["a" "b" "c"] true "3" ["asd" "as" "a"] {"@asd" 2} 1)))
  (is (= (f/first-result-true ["a" "b" "c"] true "3" ["asd" "as" "a"] {"@asd" 2} 1)
         {:countt 1 :result ["asd" "as" "a"]}))
  (is (= (f/first-result-true ["a" "b" "c"] true false ["asd" "as" "a"] {"@asd" 2} 1)
         {:countt 4, :result ["asd" "a" "b" "c"]}))
  (is (= (f/first-result-true ["a" "b" "c"] true 3 ["asd" "as" "a"] {"@asd" 2} 1)
         {:countt 1, :result ["asd" "as" "a"]}))
  (is (= (f/first-result-true ["a" "b" "c"] false false ["asd" "as" "a"] {"@asd" 2} 1)
         {:result ["a" "b" "c"]})))

#_(deftest for-chico
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

#_(deftest init-true
  (is (map? (f/init-true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} true ["a" "b" "c"] ["a" "b" "c"] 2)))
  (is (= (f/init-true ["asd" "as" "a"] true {"@asd" 2} {"@asd" 3} true ["a" "b" "c"] ["a" "b" "c"] 2)
         {:first-result nil :init false :check {"@asd" 3 "@as" 1 "@a" 1} :result ["a" "b" "c"] :countt 2}))
  (is (= (f/init-true nil true {"@asd" 2} {"@asd" 3} true ["a" "b" "c"] ["a" "b" "c"] 2)
         {:first-result ["a" "b" "c"]}))
  (is (= (f/init-true nil false {} {} true ["a" "b" "c" "sdfsdfs"] "a" 0)
         {:first-result "a"})))

#_(deftest for-grande
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

#_(deftest length-z>1
  (is (map? (f/length-z>1 "3" 2 1 ["a" "asdd" "rrr" "z"] false 2 false ["a" "b" "c" "sdfsdfs"] 8)))
  (is (= (f/length-z>1 "3" 2 1 ["a" "asdd" "rrr" "z"] false 2 false ["a" "b" "c" "sdfsdfs"] 8)
         {:first-result "a", :result "a", :countt 0, :pointer 3}))
  (is (= (f/length-z>1 false 2 1 ["a" "asdd" "rrr"] true 2 true ["a" "b" "c"] 8)
         {:first-result "a", :result [\a "b" "c"], :countt 1}))
  (is (= (f/length-z>1 false 2 1 [["a" "fxf"] ["asdd"] ["rrr"]] true 2 true ["a" "b" "c"] 8)
         {:first-result ["a" "fxf"], :result ["a" "fxf" "c"], :countt 2})));;VER SI ESTA BIEN LO DEL CARACTER \a

#_(deftest intersect
  (is (map? (f/intersect ["a" "asdd" "rrr"] 8 true ["not"] true)))
  (is (= (f/intersect ["a" "asdd" "rrr"] 8 true ["not"] true)
         {:page 0, :next nil, :result []})))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;FIN INTERSECT;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;SEARCH;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#_(deftest for-search-inner
  (is (map? (f/for-search-inner {:map [{"asdasd" 1} {"asd" 2}] :threshold 1 :resolution 2} "asdasd")))
  (is (= (f/for-search-inner {:map [{"asdasd" 1} {"asd" 2}] :threshold 1 :resolution 2} "asdasd")
         {:map [{"asdasd" 1} {"asd" 2}], :threshold 1, :resolution 2, :map-check [1], :countt 1, :map-found true})))

#_(deftest for-search
  (is (map? (f/for-search {:resolution 2 :threshold 2 :map []} ["add" "as" "a"] false {"f" 1} 3)))
  (is (= (f/for-search {:resolution 2 :threshold 2 :map []} ["add" "as" "a"] false {"f" 1} 3)
         {:resolution 2, :threshold 2, :map [], :found false, :ctx-root nil, :check-words {}, :check [], :return []})))