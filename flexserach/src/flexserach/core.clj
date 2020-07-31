(ns flexserach.core)

(def char-prev-is-vowel #{\a \e \i \o \u \y})


(defn collapse-repeating-chars1 [string]
  (loop [collapsed_string ""
         char_prev nil
         [[i char] & cx] (map-indexed vector string)]
    (if char
      (let [char_next (first cx)]
        (recur (if (not= char char_prev)
                 (if (and (pos? i) (= \h char))
                   (if (or (and (char-prev-is-vowel char_prev)
                                (char-prev-is-vowel char_next))
                           (= char_prev \ ))
                     (str collapsed_string char)
                     collapsed_string)
                   (str collapsed_string char))
                 collapsed_string)
               char
               cx))
      collapsed_string)))