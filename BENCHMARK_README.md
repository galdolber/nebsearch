# Real World Benchmark Guide

This benchmark tests nebsearch with real-world datasets (1GB+) to demonstrate production performance.

## Quick Start

### Option 1: Download Wikipedia Abstracts (Recommended)

```bash
# Download Wikipedia abstracts (~400MB compressed, ~1.2GB uncompressed)
wget https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-abstract1.xml.gz

# Extract
gunzip enwiki-latest-abstract1.xml.gz

# Rename to expected filename
mv enwiki-latest-abstract1.xml dataset.txt

# Run benchmark
java -Xmx4g -cp clojure-1.11.1.jar:spec.alpha-0.3.218.jar:core.specs.alpha-0.2.62.jar:persistent-sorted-set-0.3.0.jar:src clojure.main bench_real_world.clj
```

### Option 2: Stack Overflow Posts

```bash
# Download Stack Overflow data dump (from Internet Archive)
wget https://archive.org/download/stackexchange/stackoverflow.com-Posts.7z

# Extract (requires p7zip)
7z x stackoverflow.com-Posts.7z

# Rename
mv Posts.xml dataset.txt

# Run benchmark
java -Xmx4g -cp clojure-1.11.1.jar:spec.alpha-0.3.218.jar:core.specs.alpha-0.2.62.jar:persistent-sorted-set-0.3.0.jar:src clojure.main bench_real_world.clj
```

### Option 3: Generate Synthetic Dataset (Fallback)

```bash
# Run benchmark and choose 'generate' when prompted
java -Xmx4g -cp clojure-1.11.1.jar:spec.alpha-0.3.218.jar:core.specs.alpha-0.2.62.jar:persistent-sorted-set-0.3.0.jar:src clojure.main bench_real_world.clj
# Type: generate
```

## What the Benchmark Tests

### 1. Initial Index Build
- Builds index from 50K-500K documents
- Measures throughput (docs/sec)
- Measures compression ratio
- Tests disk storage with pre-computed inverted index

### 2. Incremental Adds
- **Single document adds**: Simulates real-time ingestion (100 docs)
- **Micro batches (10 docs)**: Simulates buffered ingestion
- **Medium batches (5K docs)**: Simulates bulk imports
- Tests O(log n) scaling of hybrid insert strategy

### 3. Search Performance
- **Cold cache**: First-time searches (inverted index builds)
- **Warm cache**: Repeated searches (cached results)
- **Multi-word queries**: AND searches with multiple terms
- Tests substring matching performance
- Measures queries per second

### 4. Memory Usage
- Measures actual RAM consumption
- Verifies index string is empty (disk storage optimization)
- Estimates memory for larger indexes

## Expected Performance

Based on a typical dataset (Wikipedia abstracts, 100K docs):

| Metric | Expected Value |
|--------|---------------|
| Initial build | 1,000-5,000 docs/sec |
| Single doc add | 2-10ms |
| Cold search | 5-50ms |
| Warm search | 0.1-5ms |
| Queries/sec | 200-10,000 |
| Memory usage | 10-50MB |

## Interpreting Results

### Good Performance Indicators
- ✅ Single doc adds: < 10ms (O(log n) scaling)
- ✅ Warm searches: < 5ms (inverted index working)
- ✅ Memory usage: < 50MB for 100K docs
- ✅ Index compression: 2-5x (efficient storage)

### Scalability Assessment
The benchmark extrapolates to 1M and 10M documents based on actual performance.

### Real-World Use Cases

**Fast**: Good for applications needing:
- Real-time document ingestion (< 10ms per add)
- Sub-second search responses
- Million+ document scale

**Optimize if**:
- Single adds > 50ms: Check disk I/O, consider batching
- Searches > 100ms: Check query complexity, cache hit rate
- Memory > 500MB: Large dataset, consider sharding

## Troubleshooting

### Out of Memory
```bash
# Increase heap size
java -Xmx8g -cp ... bench_real_world.clj
```

### Slow Downloads
Use a different mirror or download dataset separately, then place as `dataset.txt`

### Dataset Not Recognized
The benchmark auto-detects:
- Wikipedia XML (`<doc>` tags)
- JSON lines (one JSON object per line)
- Plain text (one document per line)

If your dataset doesn't match, convert it to one of these formats.

## Dataset Sources

### Wikipedia
- Dumps: https://dumps.wikimedia.org/
- Abstracts: https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-abstract1.xml.gz
- Size: ~1.2GB uncompressed, ~500K articles

### Stack Exchange
- Archive: https://archive.org/details/stackexchange
- Various sites available (Stack Overflow, Math, etc.)
- Size: 1GB+ per site

### News Articles
- AG News: https://github.com/mhjabreel/CharCnn_Keras/tree/master/data
- Size: ~30MB, 120K articles

### Academic Papers
- arXiv: https://www.kaggle.com/Cornell-University/arxiv
- Size: 1GB+, 1.7M papers

## Running on Your Computer

The benchmark is designed to run on any modern computer:
- **RAM**: 4GB+ recommended (8GB for large datasets)
- **Disk**: 5GB free space (for dataset + index files)
- **Time**: 5-30 minutes depending on dataset size
- **CPU**: Any modern CPU (multi-core helps for initial build)

## Benchmark Output

The benchmark will print:
1. **Progress updates** during dataset parsing
2. **Test results** for each phase
3. **Final summary** with all metrics
4. **Scalability estimates** for larger datasets

Save the output for comparison:
```bash
java -Xmx4g -cp ... bench_real_world.clj | tee benchmark_results.txt
```
