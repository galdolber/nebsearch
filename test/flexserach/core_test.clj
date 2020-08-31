(ns flexsearch.core-test
  (:require [clojure.test :refer [deftest is]]
            [flexsearch.core :as f]))

(deftest sort-by-length-down-test
  (is (= (f/sort-by-length-down "abc" "ab") -1))
  (is (= (f/sort-by-length-down "abc" "abc") 0))
  (is (= (f/sort-by-length-down "abc" "abcd") 1))
  (is (= (f/sort-by-length-down "aáG7/(..*)bc" "aahd66.- & -ººº") 1)))

(deftest collapse-repeating-chars-test
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

(deftest replace-regexes-test
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

(deftest global-encoder-icase-test
  (is (= (f/global-encoder-icase "Hello") "hello"))
  (is (= (f/global-encoder-icase "ihello") "ihello"))
  (is (= (f/global-encoder-icase "LKAJSDL") "lkajsdl")))

(deftest global-encoder-simple-test
  (is (= (f/global-encoder-simple "") ""))
  (is (= (f/global-encoder-simple " ") ""))
  (is (= (f/global-encoder-simple "Hellóñ") "hellon"))
  (is (= (f/global-encoder-simple "Ab-cab.c") "ab kabk"))
  (is (= (f/global-encoder-simple "Abc & á   /-ççSs83bc") "abk and a kkss83bk")))

(deftest global-encoder-advanced-test
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

(deftest global-encoder-extra-test
  (is (= (f/global-encoder-extra "lsdlkjn83298)(/KMHB.,.,KKnnnaññ") "lstlkjm83298 kmbkm"))
  (is (= (f/global-encoder-extra "lsdlkznñd298)(ppB.,.,KKn and nnddaññ") "lstlksmt298bkm amt nmtm"))
  (is (= (f/global-encoder-extra "lsdlkjn8vw3298)(/Kaei y MHB.,.ww,KKnnvnaññ") "lstlkjm8f3298 k y mbfkmfm"))
  (is (= (f/global-encoder-extra "lsdlkjn8 cc329c8)(/KMHgB.,.qQ,KKnQnnaññ") "lstlkjm8 k329k8 kmkbkmkm"))
  (is (= (f/global-encoder-extra "lsdlkszjn8cc3298)(/KççMHaeoeB.,.,KKnnnaññ") "lstlksjm8k3298 kmbkm")))

(deftest global-encoder-balance-test
  (is (= (f/global-encoder-balance "") ""))
  (is (= (f/global-encoder-balance " ") " "))
  (is (= (f/global-encoder-balance "cc") "c"))
  (is (= (f/global-encoder-balance "ççpvwW") "pvw"))
  (is (= (f/global-encoder-balance "-") " "))
  (is (= (f/global-encoder-balance "-/") " "))
  (is (= (f/global-encoder-balance "hhHçkjahsH- & aslk288jjjshy`s´dáè ae ou cc k") "hkjas aslk28jsysd ae ou c k"))
  (is (= (f/global-encoder-balance "kjahsH- & aslk288jjjshy`s´dáè ae ou cc k") "kjas aslk28jsysd ae ou c k")))

(deftest create-page-test
  (is (map? (f/create-page "kjnk" "kjask" "asdas")))
  (is (string? ((f/create-page "kjnk" "kjask" "asdas") :next)))
  (is (vector? ((f/create-page "kjnk" "kjask" [1 2 3]) :result)))
  (is (= ((f/create-page "kjnk" "kjask" "asdas") :next) "kjask"))
  (is (= (f/create-page nil "kjask" "asdas") "asdas"))
  (is (= true ((f/create-page true "kjask" "asdas") :page)))
  (is (= ((f/create-page "kjnk" false "asdas") :next) nil)))

(deftest remove-index-test
  (is (= (f/remove-index {:a [2 3 2] :b []} 2) {:a [3 2], :b []}))
  (is (= (f/remove-index {:a [2 3 2] :b []} 1) {:a [2 3 2] :b []}))
  (is (= (f/remove-index {:a [2 2 3 2] :b []} 2) {:a [2 3 2] :b []}))
  (is (= (f/remove-index {} 2) nil))
  (is (= (f/remove-index {:a [2 3 2] :b [2]} 2) {:a [3 2]}))
  (is (= (f/remove-index {:a [2 3 2 {:a []}] :b []} 2) {:a [3 2 {:a []}] :b []}))
  (is (= (f/remove-index {:a [{:a [1 2 3 2]} 2 3 2 {:a []}] :b []} 2) {:a [{:a [1 3 2]} 3 2 {:a []}] :b []})))

(deftest build-dupes-test
  (is (vector? (f/build-dupes {:resolution 9 :threshold 0})))
  (is (= (f/build-dupes {:resolution 7 :threshold nil}) [{} {} {} {} {} {} {}])))

(deftest init-test
  (is (= (f/init {:resolution 9 :threshold 9 :preset :memory :encoder nil}) {:async false
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
  (is (= ((f/init {:resolution 9 :threshold 9 :preset :memory :encoder nil}) :resolution) 1))
  (is (= ((f/init {:resolution 0 :threshold 0 :preset :memory :encoder nil}) :resolution) 1)))

(deftest encode-test
  (is (= (f/encode {:encoder f/global-encoder-balance :stemmer {"ational" "ate"} :-matcher f/simple-regex} "dfsdd    dfational") "dfsd dfate")))

(deftest filter-words-test
  (is (= (f/filter-words ["and" "sdfsdf"] #{"and"}) {:filtered ["sdfsdf"], :count 1}))
  (is (= (f/filter-words ["and" "sdfsdf"] #{}) {:filtered ["and" "sdfsdf"], :count 2}))
  (is (= (f/filter-words ["and" "sdfsdf"] #{"zsc"}) {:filtered ["and" "sdfsdf"], :count 2}))
  (is (= (f/filter-words [] #{"zsc"}) {:filtered [], :count 0}))
  (is (= ((f/filter-words [] #{"zsc"}) :count) 0))
  (is (= (count ((f/filter-words [] #{"zsc"}) :filtered)) 0))
  (is (= ((f/filter-words ["and"] #{"zsc"}) :count) 1))
  (is (= (count ((f/filter-words ["and"] #{"zsc"}) :filtered)) 1))
  (is (vector? ((f/filter-words [] #{"zsc"}) :filtered)))
  (is (number? ((f/filter-words [] #{"zsc"}) :count))))