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
