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

# Check if clojure CLI is available
if ! command -v clojure &> /dev/null; then
  echo "Error: 'clojure' command not found."
  echo "Please install Clojure CLI tools: https://clojure.org/guides/install_clojure"
  exit 1
fi

# Run with 4GB heap using deps.edn
clojure -J-Xmx4g -J-server -M bench_real_world.clj \
  | tee benchmark_results.txt

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "Benchmark complete!"
echo "Results saved to: benchmark_results.txt"
echo "═══════════════════════════════════════════════════════════════"
