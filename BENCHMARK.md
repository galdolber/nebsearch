# NebSearch Performance Benchmark

Comparison of **In-Memory** vs **Durable** index performance across all operations.

## Test Configuration

- **Small**: 100 documents
- **Medium**: 1,000 documents
- **Large**: 5,000 documents

Each document contains ~50 characters of text.

## Results Summary

### 1. Initialization

| Mode | Time |
|------|------|
| In-Memory | ~170 μs |
| Durable | ~14 ms |

**Verdict**: In-memory is **83x faster** for initialization.

### 2. Bulk Add Operations

| Documents | In-Memory | Durable | Ratio |
|-----------|-----------|---------|-------|
| 100 | 10.4 ms | 113.3 ms | 11x |
| 1,000 | 11.2 ms | 875.9 ms | 78x |
| 5,000 | 19.2 ms | 3.81 s | 198x |

**Verdict**: In-memory is **11-198x faster** for bulk adds. Performance gap widens with dataset size.

### 3. Search Operations (100 queries)

| Index Size | In-Memory | Durable | Ratio |
|------------|-----------|---------|-------|
| 100 docs | 31.2 ms | 77.1 ms | 2.5x |
| 1,000 docs | 80.5 ms | 3.96 s | 49x |
| 5,000 docs | 301.6 ms | 87.76 s | 291x |

**Verdict**: In-memory is **2.5-291x faster** for searches. Durable mode search performance degrades significantly with larger datasets.

### 4. Remove Operations (50% of documents)

| Documents Removed | In-Memory | Durable | Ratio |
|-------------------|-----------|---------|-------|
| 50 | 2.8 ms | 39.8 ms | 14x |
| 500 | 16.4 ms | 798.4 ms | 49x |
| 2,500 | 279.1 ms | 5.11 s | 18x |

**Verdict**: In-memory is **14-49x faster** for removals.

### 5. Persistence Operations

#### Serialize/Flush

| Documents | In-Memory (serialize) | Durable (flush) | Ratio |
|-----------|----------------------|-----------------|-------|
| 100 | 1.0 ms | 49.0 ms | 49x |
| 1,000 | 2.2 ms | 570.6 ms | 259x |
| 5,000 | 10.7 ms | 3.71 s | 347x |

#### Reopen (Durable only)

| Documents | Time |
|-----------|------|
| 100 | 38.2 ms |
| 1,000 | 579.8 ms |
| 5,000 | 3.52 s |

**Verdict**: In-memory serialization is **49-347x faster** than durable flush. Reopening durable indexes takes significant time proportional to dataset size.

## Key Findings

1. **In-Memory mode is significantly faster** (2.5x - 347x) for all operations
2. **Durable mode provides persistence** at the cost of performance
3. **Search performance degrades dramatically** in durable mode with larger datasets
4. **Bulk operations are efficient** in both modes
5. **Performance gap widens** with dataset size

## Use Case Recommendations

### Choose In-Memory When:
- ✅ Working with temporary or volatile data
- ✅ Need maximum search performance
- ✅ Dataset fits comfortably in RAM
- ✅ Data can be rebuilt quickly if lost
- ✅ Running tests or prototypes

### Choose Durable When:
- ✅ Need crash recovery and persistence
- ✅ Dataset is too large for memory
- ✅ Data must survive restarts
- ✅ Building long-lived indexes
- ✅ Performance trade-off is acceptable

## Performance Characteristics

### In-Memory Mode
- **Strengths**: Extremely fast for all operations, consistent performance
- **Weaknesses**: Data lost on crash/restart, limited by available RAM
- **Sweet Spot**: Temporary indexes, high-frequency searches, small-medium datasets

### Durable Mode
- **Strengths**: Persistent storage, handles large datasets, crash recovery
- **Weaknesses**: 10-300x slower than in-memory, performance degrades with size
- **Sweet Spot**: Long-lived indexes, large datasets, when persistence is critical

## Running the Benchmark

```bash
java -cp "lib/*:src:test" clojure.main benchmark_quick.clj
```

## Technical Notes

- All benchmarks run on a single thread
- Durable mode uses B-tree indexing with COW (Copy-on-Write) semantics
- In-memory mode uses sorted sets
- Each test is run once (no averaging across multiple runs)
- File I/O overhead is included in durable mode timings
