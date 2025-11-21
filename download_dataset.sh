#!/bin/bash
# Helper script to download real-world dataset for benchmarking

set -e

echo "═══════════════════════════════════════════════════════════════"
echo "          Real World Dataset Downloader"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "Select a dataset to download:"
echo ""
echo "1) Wikipedia Abstracts (Recommended)"
echo "   - Size: ~400MB compressed, ~1.2GB uncompressed"
echo "   - Documents: ~500K articles"
echo "   - Content: Encyclopedia entries"
echo ""
echo "2) Wikipedia Simple English (Smaller)"
echo "   - Size: ~150MB compressed, ~400MB uncompressed"
echo "   - Documents: ~200K articles"
echo "   - Content: Simplified encyclopedia entries"
echo ""
echo "3) Generate Synthetic (Fast, not real data)"
echo "   - Size: ~1GB"
echo "   - Documents: 1M synthetic articles"
echo "   - Content: Generated text"
echo ""
read -p "Enter choice (1-3): " choice

case $choice in
  1)
    echo ""
    echo "Downloading Wikipedia Abstracts..."
    echo "This may take several minutes depending on your connection."
    echo ""

    if command -v wget &> /dev/null; then
      wget -O dataset.xml.gz https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-abstract1.xml.gz
    elif command -v curl &> /dev/null; then
      curl -L -o dataset.xml.gz https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-abstract1.xml.gz
    else
      echo "ERROR: Neither wget nor curl found. Please install one of them."
      exit 1
    fi

    echo ""
    echo "Extracting..."
    gunzip dataset.xml.gz
    mv dataset.xml dataset.txt

    echo ""
    echo "✓ Dataset downloaded successfully!"
    echo "  File: dataset.txt"
    echo "  Size: $(du -h dataset.txt | cut -f1)"
    ;;

  2)
    echo ""
    echo "Downloading Simple Wikipedia Abstracts..."
    echo ""

    if command -v wget &> /dev/null; then
      wget -O dataset.xml.gz https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-abstract.xml.gz
    elif command -v curl &> /dev/null; then
      curl -L -o dataset.xml.gz https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-abstract.xml.gz
    else
      echo "ERROR: Neither wget nor curl found. Please install one of them."
      exit 1
    fi

    echo ""
    echo "Extracting..."
    gunzip dataset.xml.gz
    mv dataset.xml dataset.txt

    echo ""
    echo "✓ Dataset downloaded successfully!"
    echo "  File: dataset.txt"
    echo "  Size: $(du -h dataset.txt | cut -f1)"
    ;;

  3)
    echo ""
    echo "Generating synthetic dataset..."
    echo "This will create 1M documents (~1GB)..."
    echo ""

    cat > dataset.txt << 'EOF_SCRIPT'
EOF_SCRIPT

    # Generate using printf (much faster than echo in loop)
    for i in {1..1000000}; do
      if [ $((i % 10000)) -eq 0 ]; then
        echo "  Generated $i documents..."
      fi
      printf "<doc><title>Article %d</title><abstract>This is article %d about an important topic with detailed information including historical background geographical context cultural significance economic impact political implications social relevance technological aspects scientific principles and comprehensive analysis</abstract></doc>\n" $i $i >> dataset.txt
    done

    echo ""
    echo "✓ Synthetic dataset generated!"
    echo "  File: dataset.txt"
    echo "  Size: $(du -h dataset.txt | cut -f1)"
    ;;

  *)
    echo "Invalid choice. Exiting."
    exit 1
    ;;
esac

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "Ready to benchmark!"
echo ""
echo "Run the benchmark with:"
echo "  java -Xmx4g -cp clojure-1.11.1.jar:spec.alpha-0.3.218.jar:core.specs.alpha-0.2.62.jar:persistent-sorted-set-0.3.0.jar:src clojure.main bench_real_world.clj"
echo ""
echo "Or use the shortcut:"
echo "  ./run_benchmark.sh"
echo "═══════════════════════════════════════════════════════════════"
