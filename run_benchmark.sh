#!/bin/bash
# Simple script to run the real-world benchmark

set -e

echo "═══════════════════════════════════════════════════════════════"
echo "          Nebsearch Real World Benchmark"
echo "═══════════════════════════════════════════════════════════════"
echo ""

# Check if dataset exists
if [ ! -f "dataset.txt" ]; then
  echo "Dataset not found!"
  echo ""
  read -p "Download dataset now? (y/n): " download
  if [ "$download" = "y" ]; then
    chmod +x download_dataset.sh
    ./download_dataset.sh
  else
    echo ""
    echo "Please run ./download_dataset.sh first or manually place dataset as dataset.txt"
    exit 1
  fi
fi

echo ""
echo "Dataset found: $(du -h dataset.txt | cut -f1)"
echo ""
echo "Starting benchmark..."
echo "This may take 10-30 minutes depending on dataset size."
echo ""
read -p "Continue? (y/n): " continue
if [ "$continue" != "y" ]; then
  echo "Cancelled."
  exit 0
fi

echo ""
echo "Running benchmark (output will be saved to benchmark_results.txt)..."
echo ""

# Run with 4GB heap (load as script file, not namespace)
java -Xmx4g -server \
  -cp clojure-1.11.1.jar:spec.alpha-0.3.218.jar:core.specs.alpha-0.2.62.jar:persistent-sorted-set-0.3.0.jar:src \
  clojure.main \
  bench_real_world.clj \
  | tee benchmark_results.txt

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "Benchmark complete!"
echo "Results saved to: benchmark_results.txt"
echo "═══════════════════════════════════════════════════════════════"
