(ns flexserach.core-test
  (:require [clojure.test :refer [deftest is]]
            [flexserach.core :as f]))

(deftest sort-by-length-down-test
  (is (= (f/sort-by-length-down "abc" "ab") -1))
  (is (= (f/sort-by-length-down "abc" "abc") 0))
  (is (= (f/sort-by-length-down "abc" "abcd") 1))
  (is (= (f/sort-by-length-down "aáG7/(..*)bc" "aahd66.- & -ººº") 1)))

(deftest collapse-repeating-chars-test
  (is (= (f/collapse-repeating-chars "hello") "helo"))
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
  (is (= (f/replace-regexes "abc & abc" f/simple-regex) "abk and abk"))
  (is (= (f/replace-regexes "asd dfd" f/simple-regex) "asd dfd"))
  (is (= (f/replace-regexes "asd.dfd." f/simple-regex) "asddfd"))
  (is (= (f/replace-regexes "asd/lkal" f/simple-regex) "asd lkal"))
  (is (= (f/replace-regexes "asdlk-al" f/simple-regex) "asdlk al"))
  (is (= (f/replace-regexes "asdááóññsbplk-al" f/simple-regex) "asdaaonnsbplk al"))
  (is (= (f/replace-regexes "ahHh & h-/s/...d  lk-a 99*/´ñlLKJHGñççññl" f/simple-regex) "ahh and h s d lk a 99 nlnkknnl"))
  ;
  (is (= (f/replace-regexes "aesdlkael" f/advanced-regex) "asdlkal"))
  (is (= (f/replace-regexes "asauouaeioiuuioiauoaiaaieieyydadthtdhdlk-zs" f/advanced-regex) "asauioiuuioiauiaeiiiydattdhdlk-s"))
  (is (= (f/replace-regexes "aspszzshckkkcphhfpfptdttdf-dlkal" f/advanced-regex) "aspsskkkcfhffptttdf-dlkal"))
  (is (= (f/replace-regexes "ayasckkckkhscshhcskthdtpfdlk-al" f/advanced-regex) "eiaskkkkhscshcskttfdlk-al"))
  ;
  (is (= (f/replace-regexes "n" f/extra-regex) "m"))
  (is (= (f/replace-regexes "vw" f/extra-regex) "ff"))
  (is (= (f/replace-regexes "aecqgpze-/35168askjhHGTFR" f/extra-regex) "kkkbs-/35168skjhHGTFR"))
  (is (= (f/replace-regexes "pdenwvvwwddj" f/extra-regex) "btmfffffttj"))
  (is (= (f/replace-regexes "anaaaáéìòò" f/extra-regex) "máéìòò"))
  (is (= (f/replace-regexes "ddndzzdnt" f/extra-regex) "ttmtsstmt")))

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
  (is (= (f/global-encoder-advanced "áAc" true) "aak"))
  (is (= (f/global-encoder-advanced "-pA/9/& " true) " pa 9 "))
  (is (= (f/global-encoder-advanced "A." true) "a"))
  (is (= (f/global-encoder-advanced "" true) ""))
  (is (= (f/global-encoder-advanced "ññ" true) "nn"))
  (is (= (f/global-encoder-advanced "kj34KJH/(..,7/´s`wçç``^^)" true) "kj34kjh 7 swkk"))
  (is (= (f/global-encoder-advanced "HhhHHkj34KJH/sh(.´F´RÉáászááhé.-.??uo.,7/´s`wçç``^^)" true) "hhhhhkj34kjh sfreaasaahe u7 swkk"))
  (is (= (f/global-encoder-advanced "ñññ" true) "nnn"))
  ;
  (is (= (f/global-encoder-advanced "" false) ""))
  (is (= (f/global-encoder-advanced "á" false) "a"))
  (is (= (f/global-encoder-advanced "Áe" false) "ae"))
  (is (= (f/global-encoder-advanced "kj34KJH/(..,7/´s`wçç``^^)" false) "kj34kj 7 swk"))
  (is (= (f/global-encoder-advanced "ññ" false) "n")))
