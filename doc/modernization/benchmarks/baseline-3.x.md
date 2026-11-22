# Benchmark baseline: spark-redis 3.x

The performance of the current 3.x connector, recorded before anything is ported (Phase 0, step B5). The 4.x
benchmarks are compared with these numbers. The medians are in [`baseline-3.x.csv`](baseline-3.x.csv) (`name,millis`).

## How it was run

```bash
mvn -B -o -Pbenchmark test -Dredis.image=redis:8.2
```

- **Three full runs**, back to back on 2026-11-22 from 21:46 to 22:21 UTC. Each run took 10.5–12 minutes and passed all 52
  benchmark tests. The CSV keeps the **median** of the three runs for each benchmark.
- Every suite tagged `@BenchmarkTest`, on both topologies. Standalone is one Redis; cluster is three primaries in one
  container (lane A's harness).
- Code: `spike/phase-0-b` at `8d7f319` (B5), on `spike/phase-0` at `a3d4c91`. The connector code under `src/main` is
  unchanged from 3.x.
- Each run's `target/benchmarks.csv` is written by `RedisBenchmarks.time`. The median is per name, over the three runs.

| Benchmark | What it measures |
|---|---|
| `<topology>/<model>/many-1000000/<step>` | 1,000,000 `Person` rows (`hash` or `binary` model, `key.column = name`): `write` (Overwrite), `read`, `read-all-fields`, `read-1-field`, `read-0-fields`, `take-10`, `cleanup` (pipelined DEL of every key) |
| `<topology>/<model>/single/<step>` | The same steps on one row: mostly fixed per-job overhead |
| `<topology>/hash/keylist-10000-of-1000000/read` | 10,000 rows by an explicit key list (`sc.fromRedisHash(keys)`, 8 partitions) from a 1,000,000-row `hash` table |
| `<topology>/stream/read-1000000` | 1,000,000 stream entries in one Structured Streaming batch (`Trigger.Once`, from `0-0`, into `noop`), with the source's defaults: one consumer, `stream.read.batch.size` 100 |

## Environment

| | |
|---|---|
| Machine | MacBook Pro (Mac17,7), Apple M5 Max, 18 cores (6 Super, 12 Performance), 36 GB |
| OS | macOS 26.7.3 |
| Docker | Docker Desktop, engine 29.9.3, **16 CPUs, 31.5 GiB** for the VM, aarch64 |
| Redis | `redis:8.2` = Redis **8.2.11**, in Testcontainers (dynamic host ports) |
| JVM | Azul Zulu 1.8.0_514, `-Xmx2g` (the `benchmark` profile) |
| Build | Apache Maven 3.10.1 (Homebrew), Scala 2.12 (`scala-library` 2.12.0), Spark 3.2.1, `local[*]` |
| Other load | **The machine was not idle.** Containers unrelated to this repo were running (a Redis Software node and three ADBC containers). The load average was 9.6–10.2 before the first run and 8.9–14.3 during the runs |

## Results

### 1M-row, key-list and stream benchmarks

| Benchmark | Run 1 (ms) | Run 2 (ms) | Run 3 (ms) | Median (ms) | Spread |
|---|--:|--:|--:|--:|--:|
| `cluster/binary/many-1000000/cleanup` | 10,141 | 9,882 | 7,718 | **9,882** | 25% |
| `cluster/binary/many-1000000/read` | 15,368 | 13,828 | 14,035 | **14,035** | 11% |
| `cluster/binary/many-1000000/read-0-fields` | 4,602 | 3,587 | 3,120 | **3,587** | 41% |
| `cluster/binary/many-1000000/read-1-field` | 15,478 | 12,890 | 14,037 | **14,037** | 18% |
| `cluster/binary/many-1000000/read-all-fields` | 15,390 | 13,000 | 13,757 | **13,757** | 17% |
| `cluster/binary/many-1000000/take-10` | 1,991 | 1,568 | 1,728 | **1,728** | 24% |
| `cluster/binary/many-1000000/write` | 5,669 | 4,709 | 4,129 | **4,709** | 33% |
| `cluster/hash/keylist-10000-of-1000000/read` | 1,575 | 1,220 | 1,218 | **1,220** | 29% |
| `cluster/hash/many-1000000/cleanup` | 8,941 | 10,626 | 9,344 | **9,344** | 18% |
| `cluster/hash/many-1000000/read` | 9,547 | 6,247 | 5,408 | **6,247** | 66% |
| `cluster/hash/many-1000000/read-0-fields` | 4,698 | 4,482 | 3,440 | **4,482** | 28% |
| `cluster/hash/many-1000000/read-1-field` | 6,570 | 9,384 | 6,906 | **6,906** | 41% |
| `cluster/hash/many-1000000/read-all-fields` | 7,548 | 8,994 | 5,599 | **7,548** | 45% |
| `cluster/hash/many-1000000/take-10` | 1,057 | 1,677 | 1,198 | **1,198** | 52% |
| `cluster/hash/many-1000000/write` | 4,008 | 3,426 | 3,625 | **3,625** | 16% |
| `cluster/stream/read-1000000` | 149,990 | 152,789 | 150,146 | **150,146** | 2% |
| `standalone/binary/many-1000000/cleanup` | 10,555 | 15,694 | 13,164 | **13,164** | 39% |
| `standalone/binary/many-1000000/read` | 26,940 | 34,488 | 24,158 | **26,940** | 38% |
| `standalone/binary/many-1000000/read-0-fields` | 13,906 | 20,243 | 15,860 | **15,860** | 40% |
| `standalone/binary/many-1000000/read-1-field` | 23,200 | 28,019 | 21,881 | **23,200** | 26% |
| `standalone/binary/many-1000000/read-all-fields` | 21,902 | 34,713 | 20,729 | **21,902** | 64% |
| `standalone/binary/many-1000000/take-10` | 6,946 | 10,053 | 6,791 | **6,946** | 47% |
| `standalone/binary/many-1000000/write` | 6,439 | 7,026 | 4,859 | **6,439** | 34% |
| `standalone/hash/keylist-10000-of-1000000/read` | 1,322 | 1,002 | 849 | **1,002** | 47% |
| `standalone/hash/many-1000000/cleanup` | 16,603 | 13,273 | 9,515 | **13,273** | 53% |
| `standalone/hash/many-1000000/read` | 27,709 | 19,275 | 13,130 | **19,275** | 76% |
| `standalone/hash/many-1000000/read-0-fields` | 16,432 | 15,690 | 14,959 | **15,690** | 9% |
| `standalone/hash/many-1000000/read-1-field` | 19,825 | 19,225 | 15,774 | **19,225** | 21% |
| `standalone/hash/many-1000000/read-all-fields` | 34,701 | 24,288 | 12,389 | **24,288** | 92% |
| `standalone/hash/many-1000000/take-10` | 8,185 | 8,076 | 5,932 | **8,076** | 28% |
| `standalone/hash/many-1000000/write` | 4,015 | 2,070 | 2,578 | **2,578** | 75% |
| `standalone/stream/read-1000000` | 153,532 | 156,709 | 154,431 | **154,431** | 2% |

### Single-row benchmarks (fixed overhead)

| Benchmark | Run 1 (ms) | Run 2 (ms) | Run 3 (ms) | Median (ms) | Spread |
|---|--:|--:|--:|--:|--:|
| `cluster/binary/single/cleanup` | 45 | 8 | 17 | **17** | 218% |
| `cluster/binary/single/read` | 38 | 33 | 60 | **38** | 71% |
| `cluster/binary/single/read-0-fields` | 67 | 29 | 26 | **29** | 141% |
| `cluster/binary/single/read-1-field` | 44 | 26 | 26 | **26** | 69% |
| `cluster/binary/single/read-all-fields` | 43 | 31 | 181 | **43** | 349% |
| `cluster/binary/single/take-10` | 137 | 36 | 42 | **42** | 240% |
| `cluster/binary/single/write` | 59 | 117 | 124 | **117** | 56% |
| `cluster/hash/single/cleanup` | 17 | 20 | 13 | **17** | 41% |
| `cluster/hash/single/read` | 42 | 71 | 42 | **42** | 69% |
| `cluster/hash/single/read-0-fields` | 33 | 34 | 28 | **33** | 18% |
| `cluster/hash/single/read-1-field` | 41 | 56 | 37 | **41** | 46% |
| `cluster/hash/single/read-all-fields` | 38 | 127 | 52 | **52** | 171% |
| `cluster/hash/single/take-10` | 73 | 132 | 98 | **98** | 60% |
| `cluster/hash/single/write` | 64 | 101 | 140 | **101** | 75% |
| `standalone/binary/single/cleanup` | 12 | 29 | 16 | **16** | 106% |
| `standalone/binary/single/read` | 48 | 45 | 50 | **48** | 10% |
| `standalone/binary/single/read-0-fields` | 50 | 35 | 38 | **38** | 39% |
| `standalone/binary/single/read-1-field` | 61 | 54 | 62 | **61** | 13% |
| `standalone/binary/single/read-all-fields` | 40 | 41 | 43 | **41** | 7% |
| `standalone/binary/single/take-10` | 73 | 184 | 97 | **97** | 114% |
| `standalone/binary/single/write` | 181 | 132 | 89 | **132** | 70% |
| `standalone/hash/single/cleanup` | 11 | 15 | 8 | **11** | 64% |
| `standalone/hash/single/read` | 74 | 82 | 79 | **79** | 10% |
| `standalone/hash/single/read-0-fields` | 43 | 34 | 32 | **34** | 32% |
| `standalone/hash/single/read-1-field` | 29 | 40 | 35 | **35** | 31% |
| `standalone/hash/single/read-all-fields` | 35 | 44 | 37 | **37** | 24% |
| `standalone/hash/single/take-10` | 55 | 55 | 49 | **55** | 11% |
| `standalone/hash/single/write` | 79 | 111 | 100 | **100** | 32% |

Spread is (max − min) / median over the three runs.

## Reading these numbers

- **The baseline is noisy.** Because of the background load, many 1M-row steps vary by 20–90% between runs. The stream
  benchmarks are steady (about 2%). The single-row steps are tens of milliseconds and mostly noise. Don't apply a
  10% regression threshold (spec §2.12) to a single run measured against these numbers. Compare medians of several
  runs, on the same machine, preferably idle. The plan treats regressions as warnings until a stable runner exists.
- **Stream reads dominate.** About 150 s for 1,000,000 entries on either topology: 3.x reads 100 entries per
  `XREADGROUP` with one consumer, so the run is round-trip bound.
- **The cluster is faster than standalone for 1M-row reads** (for example 6.2 s against 19.3 s for `hash` `read`).
  The cluster's three primaries serve the scan and the pipelines in parallel.
- **Key-list lookups:** 10,000 rows from a 1,000,000-row table take about 1–1.2 s.

## Re-running

Run the command above three times and take the median of each name across the three `target/benchmarks.csv` files.
Record the machine and anything else running on it.
