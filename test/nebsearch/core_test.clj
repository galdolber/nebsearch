(ns nebsearch.core-test
  (:require [clojure.test :refer [deftest is]]
            [nebsearch.core :as f]))

(def sample-data (into {} (mapv vector (range) (read-string (slurp "data.edn")))))

(deftest test-flex
  (let [flex (f/search-add (f/init) sample-data)
        flex (f/deserialize (f/serialize flex))

        _ (is (= ["30 Nights of Paranormal Activity with the Devil Inside the Girl with the Dragon Tattoo"
                  "The Girl with the Dragon Tattoo"]
                 (mapv sample-data (f/search flex "girl tatto"))))

        _ (is (= ["30 Nights of Paranormal Activity with the Devil Inside the Girl with the Dragon Tattoo"]
                 (mapv sample-data (f/search flex "30 girl tatto"))))

        _ (dotimes [_ 3] ;; test cache
            (is (= ["A Man of Iron"
                    "Iron Man"
                    "Iron Man 2"
                    "The Man with the Iron Fists"
                    "Iron Man 3"
                    "Man of Iron"
                    "The Man in the Iron Mask"]
                   (mapv sample-data (f/search flex "man iron")))))

        _ (is (= {#{"tatto" "girl"} #{118 20110},
                  #{"tatto" "30" "girl"} #{118},
                  #{"man" "iron"} #{475 9434 9432 21523 9433 11379 21465}}
                 @(:cache (meta flex))))

        ;; update
        _ (is (= ["$ aka Dollars"] (mapv sample-data (f/search flex "aka Dollars"))))
        _ (is (= [] (mapv sample-data (f/search flex "aka Dollars edited"))))
        flex (f/search-add flex {0 "aka Dollars edited"})

        ;; cache after add
        _ (is (= {} @(:cache (meta flex))))

        _ (is (= ["$ aka Dollars"] (mapv sample-data (f/search flex "aka Dollars edited"))))

        ;; cache after add and search
        _ (is (= {#{"aka" "edited" "dollars"} #{0}}
                 @(:cache (meta flex))))
        ;; delete
        flex (f/search-remove flex [0])

        ;; cache on remove
        _ (is (= {#{"aka" "edited" "dollars"} #{}}
                 @(:cache (meta flex))))]

    (is (= [] (mapv sample-data (f/search flex "aka Dollars"))))

    (let [g-flex (f/search-gc flex)]
      (is (= 464954 (count (:index flex))))
      (is (= 464921 (count (:index g-flex)))))))

(deftest hashing-consistent-test
  (is
   (= (hash (f/search-add (f/init) sample-data))
      (hash (f/search-add (f/init) sample-data))
      (hash (f/search-add (f/init) sample-data)))))
