#!/bin/bash
# Run full dataset benchmark with batched loading

set -e

echo "═══════════════════════════════════════════════════════════════"
echo "     Nebsearch Full Dataset Benchmark (Batched Loading)"
echo "═══════════════════════════════════════════════════════════════"
echo ""

# Check if dataset exists
if [ ! -f "dataset.txt" ]; then
  echo "ERROR: dataset.txt not found!"
  echo "Please download or create a dataset first."
  exit 1
fi

echo "Dataset found: $(du -h dataset.txt | cut -f1)"
echo ""
echo "This benchmark will:"
echo "  - Load the ENTIRE dataset in batches (no truncation)"
echo "  - Build disk-backed search index incrementally"
echo "  - Run search performance tests"
echo "  - Show compression with Snappy"
echo ""
echo "Note: This may take significant time for large datasets."
echo ""
read -p "Continue? (y/n): " continue
if [ "$continue" != "y" ]; then
  echo "Cancelled."
  exit 0
fi

echo ""
echo "Running benchmark..."
echo ""

# Check if clojure CLI is available
if ! command -v clojure &> /dev/null; then
  echo "Error: 'clojure' command not found."
  echo "Please install Clojure CLI tools: https://clojure.org/guides/install_clojure"
  exit 1
fi

# Run with 4GB heap
clojure -J-Xmx4g -J-server -M bench_full_dataset.clj

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "Benchmark complete!"
echo "═══════════════════════════════════════════════════════════════"
