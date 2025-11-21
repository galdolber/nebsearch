#!/bin/bash
# Run Clojure scripts with Java directly (no Maven or Clojure CLI needed)

set -e

# Download dependencies if not present
if [ ! -d "lib" ]; then
  echo "Downloading dependencies..."
  mkdir -p lib
  cd lib

  curl -L -o clojure-1.11.1.jar https://repo1.maven.org/maven2/org/clojure/clojure/1.11.1/clojure-1.11.1.jar
  curl -L -o core.specs.alpha-0.2.62.jar https://repo1.maven.org/maven2/org/clojure/core.specs.alpha/0.2.62/core.specs.alpha-0.2.62.jar
  curl -L -o spec.alpha-0.3.218.jar https://repo1.maven.org/maven2/org/clojure/spec.alpha/0.3.218/spec.alpha-0.3.218.jar

  cd ..
  echo "Dependencies downloaded!"
fi

# Check if a script was provided
if [ -z "$1" ]; then
  echo "Usage: $0 <script.clj> [java-options]"
  echo "Example: $0 test_serializer.clj"
  echo "Example: $0 bench_bulk_load.clj -Xmx4g"
  exit 1
fi

SCRIPT=$1
shift

# Default to 1GB heap if not specified
JAVA_OPTS="-Xmx1g -server"
if [ $# -gt 0 ]; then
  JAVA_OPTS="$@"
fi

# Run with Java
echo "Running $SCRIPT with Java $JAVA_OPTS..."
java $JAVA_OPTS -cp "lib/clojure-1.11.1.jar:lib/core.specs.alpha-0.2.62.jar:lib/spec.alpha-0.3.218.jar:src" clojure.main "$SCRIPT"
