clj -Sdeps '{:deps {uberdeps/uberdeps {:mvn/version "1.0.2"}}}' -m uberdeps.uberjar
java -Dgraal.PGOInstrument=flexsearch.iprof -cp target/flexsearch.jar clojure.main -m flexsearch.core "alice in wonder land and gal"
