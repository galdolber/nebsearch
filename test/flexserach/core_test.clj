(ns flexserach.core-test
  (:require [clojure.test :refer [deftest is]]
            [flexserach.core :as f]))

(deftest collapse-repeating-chars-test
  (is (= (f/collapse-repeating-chars "hello") "helo"))
  (is (= (f/collapse-repeating-chars "ihello") "ielo")))
