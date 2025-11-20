(println "Testing vector comparisons:")

(def entry [0 "doc1" "hello world"])
(def start [1 nil])

(println "Entry:" entry)
(println "Start:" start)
(println "Compare result:" (compare entry start))
(println "Is <= 0?" (<= (compare entry start) 0))

;; Try with different values
(println "\nTesting [0 \"doc1\"] vs [1 nil]:")
(println "Compare:" (compare [0 "doc1"] [1 nil]))

(println "\nTesting [0 nil] vs [1 nil]:")
(println "Compare:" (compare [0 nil] [1 nil]))

(System/exit 0)
