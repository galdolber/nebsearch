(ns flexsearch.core-test
  (:require [clojure.test :refer [deftest is]]
            [flexsearch.core :as f]))

(def sample-data (into {} (map vector (range) (read-string (slurp "data.edn")))))
(deftest test-flex
  (let [flex (time (f/flex-add (f/init {}) sample-data))
        _ (is (= ["30 Nights of Paranormal Activity with the Devil Inside the Girl with the Dragon Tattoo"
                  "The Girl with the Dragon Tattoo"]
                 (mapv sample-data (f/flex-search flex "girl tatto"))))

        _ (is (= ["30 Nights of Paranormal Activity with the Devil Inside the Girl with the Dragon Tattoo"]
                 (mapv sample-data (f/flex-search flex "30 girl tatto"))))

        _ (is (= ["A Man of Iron"
                  "Iron Man"
                  "Iron Man 2"
                  "The Man with the Iron Fists"
                  "Iron Man 3"
                  "Man of Iron"
                  "The Man in the Iron Mask"]
                 (mapv sample-data (f/flex-search flex "man iron"))))

        ;; update
        _ (is (= ["$ aka Dollars"] (mapv sample-data (f/flex-search flex "aka Dollars"))))
        _ (is (= [] (mapv sample-data (f/flex-search flex "aka Dollars edited"))))
        flex (time (f/flex-add flex {0 "aka Dollars edited"}))
        _ (is (= ["$ aka Dollars"] (mapv sample-data (f/flex-search flex "aka Dollars edited"))))

        ;; delete
        flex (f/flex-remove flex [0])]

    (is (= [] (mapv sample-data (f/flex-search flex "aka Dollars"))))

    ;; compacting the index (garbage collection)
    (is (= 464954 (count (:index flex))))
    (is (= 464921 (count (:index (f/flex-gc flex)))))))
