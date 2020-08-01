(ns flexserach.core-test
  (:require [clojure.test :refer [deftest is]]
            [flexserach.core :as f]))

(deftest sort-by-length-down-test
  (is (= (- 1) (f/sort-by-length-down "abc" "ab")))
  (is (= (- 1) (f/sort-by-length-down "abc" "abc")))
  (is (= 1 (f/sort-by-length-down "abc" "abcd"))))

(deftest collapse-repeating-chars-test
  (is (= (f/collapse-repeating-chars "hello") "helo"))
  (is (= (f/collapse-repeating-chars "ihello") "ielo")))

(deftest replace--test
  (is (= (f/replace- "abc & abc" f/simple-regex) "abc and abc"))
  (is (= (f/replace- "asd dfd" f/simple-regex) "asd dfd"))
  (is (= (f/replace- "asd.dfd." f/simple-regex) "asddfd"))
  (is (= (f/replace- "asd/lkal" f/simple-regex) "asd lkal"))
  (is (= (f/replace- "asdlk-al" f/simple-regex) "asdlk al"))
  (is (= (f/replace- "aesdlkael" f/advanced-regex) "asdlkal"))
  (is (= (f/replace- "asdlk-zs" f/advanced-regex) "asdlk-s"))
  (is (= (f/replace- "aspf-dlkal" f/advanced-regex) "asf-dlkal"))
  (is (= (f/replace- "ayasdlk-al" f/advanced-regex) "eiasdlk-al"))
  (is (= (f/replace- "n" f/extra-regex) "m"))
  (is (= (f/replace- "v" f/extra-regex) "f"))
  (is (= (f/replace- "a" f/extra-regex) ""))
  (is (= (f/replace- "pj" f/extra-regex) "bj"))
  #_(is (= (f/replace- "an" f/extra-regex) "am"))
  #_(is (= (f/replace- "dt" f/extra-regex) "pb")))

(deftest global-encoder-icase-test
  (is (= (f/global-encoder-icase "Hello") "hello"))
  (is (= (f/global-encoder-icase "ihello") "ihello"))
  (is (= (f/global-encoder-icase "LKAJSDL") "lkajsdl")))

(deftest global-encoder-simple-test
  (is (= (f/global-encoder-simple "") ""))
  (is (= (f/global-encoder-simple "Hello") "hello"))
  (is (= (f/global-encoder-simple "Abc.abc") "abcabc"))
  #_(is (= (f/global-encoder-simple "Abc & abc") "abc and abc")))
