# Spec: spark-redis 4.x

> Stage: **Design** (AI-native SDLC playbook). Input: [`intent.md`](intent.md). Status: **development spike**; decisions are recorded in [`intent.md`](intent.md).
> Next artifact: [`roadmap.md`](roadmap.md), then one `plan.md` per phase, written in plan mode from this spec.

This spec describes the connector we would build today if we were starting from scratch. The design is fixed
here, and [`roadmap.md`](roadmap.md) sets the order of the work. Each section lists the open GitHub issues it
resolves, so reviewers can check the design against what users have asked for.

---

## 1. Supported versions

The rule is the one in the intent doc: **support what upstream is actively developing and maintaining
today, and nothing else.** Snapshot taken on 2026-11-21.

**Apache Spark.** Releases are now quarterly. The x.0, x.1 and x.2 lines get 6 months of maintenance
(4.2 keeps its earlier 18-month promise), and the last line of each major release is the LTS, with 18 months
([versioning policy](https://spark.apache.org/versioning-policy.html)).

| Line | GA | Upstream status | JDK | Python | Connector |
|---|---|---|---|---|---|
| 4.4 | RC due Dec 2026 | In development | 17/21/25 | 3.11+ | CI against RCs; supported from GA |
| **4.3** | 2026-10-30 | Maintained (6-month window, about Apr 2027) | 17/21/25 | 3.11+ | **Supported** |
| **4.2** | 2026-07-14 | Maintained (18-month window, about Jan 2028) | 17/21/25 | 3.10+ | **Supported** |
| **4.1** | 2025-12-16 | Maintained; EOL not published (we estimate about Jun 2027) | 17/21 | 3.10+ | **Supported (floor)** |
| 4.5 | Branch cut about Feb 2027 | Future **4.x LTS** (18 months) | — | — | The long-term anchor once it ships |
| 4.0 | 2025-05-23 | At EOL (4.0.5 is probably the last release) | 17/21 | 3.9+ | Not supported |
| 3.5 | 2023-09-13 | Extended LTS, security fixes only, until Nov 2027 | 8/11/17 | 3.8+ | Not supported. `branch-3.x` hosts only the migration tool (§2.11) |

All supported Spark lines use **Scala 2.13** only. The build targets **JDK 17** and CI also runs on JDK 21
and 25.

**Redis.** Standard releases get fixes for 6 months after the next minor ships. Extended releases (the
second minor and the last minor of each major) get 5 years
([lifecycle](https://redis.io/docs/latest/operate/oss_and_stack/install/version-mgmt/)).

| Line | GA | Upstream status | Connector |
|---|---|---|---|
| 8.12 | RC (Nov 2026) | In development | CI against RCs; supported from GA |
| **8.10** | 2026-07 | Standard, patched | **Supported** |
| **8.8** | 2026-05-25 | Standard, patched | **Supported** |
| **8.6** | 2026-02-10 | Standard, patched; default database version on Redis Software 8.2 | **Supported** |
| **8.4** | 2025-11-18 | Standard, patched; Software and Cloud EOL 2027-01-01 | **Supported** until EOL |
| **8.2** | 2025-08-04 | **Extended**, patched until 2030-09-01 | **Supported (floor)** |
| 8.0 | 2025-05-02 | EOL 2026-12-01; no patch since Feb 2026 | Not supported |
| 7.4, 7.2 | 2024, 2023 | Extended, security fixes only, until 2029-12-01 | Not supported (intent Q6) |

- **Redis Software and Redis Cloud:** only the **latest** Redis Software release and the latest database
  version Redis Cloud offers are certified. Today that is Redis Software 8.2 (default database version 8.6)
  and Redis Cloud 8.6. Older managed versions may work, but they are not tested.
- **Java client:** Jedis 8.0.x (8.0.2, October 2026), which supports Redis 7.2 through 8.10.
- **Wire protocol: RESP3 only.** Every connection opens with `HELLO 3`. There is no RESP2 fallback and no
  option to choose RESP2. Every supported Redis line has spoken RESP3 since 6.0, so the floor costs
  nothing here (see §2.1).

### What the floor buys us

Setting the floor at **Spark 4.1 and Redis 8.2** means the connector can rely on the following
*unconditionally*, with no fallback code:

- **Spark:** the whole DSv2 pushdown family, including `SupportsPushDownVariantExtractions` and
  `SupportsPushDownJoin` (both 4.1); `VARIANT` as a GA type; the Real-Time Mode interfaces; and Python Data
  Source filter pushdown.
- **Redis:** JSON; the Query Engine, including on OSS Cluster; TimeSeries; the probabilistic types; Vector
  Sets; `HSETEX`/`HGETEX`/`HGETDEL` and hash-field TTL; `XACKDEL`/`XDELEX`/`ACKED`; `CLUSTER SLOT-STATS`;
  and SVS-VAMANA vector indexes.

Anything newer is **capability-gated**:

| Feature | Needs | Behaviour when it is missing |
|---|---|---|
| `FT.HYBRID` hybrid search | Redis 8.4 | Planning fails if requested; KNN with a filter is still available |
| `SET IFEQ/IFDEQ`, `DELEX`, `DIGEST` (conditional writes) | Redis 8.4 | Planning fails if `write.condition` needs them |
| `MSETEX` | Redis 8.4 | Pipelined `SET … EX` |
| `XREADGROUP … CLAIM` | Redis 8.4 | `XAUTOCLAIM` |
| `XADD IDMP` (idempotent producer) | Redis 8.6 | At-least-once delivery with a deterministic dedup field |
| `XNACK` | Redis 8.8 | Reclaim after the idle timeout |
| Array data type | Redis 8.8 | No model yet (a later lane) |
| `XREAD/XREADGROUP MAXSIZE` | Redis 8.10 | Reads bounded by count only |
| `HIMPORT` bulk hash load | Redis 8.10, Open Source only | Pipelined `HSET` |
| `TS.NRANGE` / `TS.READ` | Redis 8.10 | `TS.MRANGE` |
| `CLUSTER MIGRATION STATUS` | Redis 8.12 | Detect migration from `MOVED`/`ASK` redirects |
| Python Data Source admission control and `AvailableNow` | Spark 4.2 | Pure-Python source is batch-only on 4.1 |
| `SupportsSchemaEvolution`, DSv2 `Changelog`, `CustomTaskMetric.mergeWith` | Spark 4.2 | Built only into the 4.2+ artifacts |
| Real-Time Mode with stateful queries, non-experimental | Spark 4.3 | Stateless only on 4.1 and 4.2 |
| `variant_get` filter pushdown | Spark 4.3 | Spark filters after the scan |

### Version policy

- A Spark or Redis line is **supported while upstream is actively developing or maintaining it**:
  - for Spark, a line inside its published maintenance window
  - for Redis, the current extended 8.x line and every standard 8.x line that is still getting patches

  Lines that get **security fixes only** (Spark 3.5 extended LTS, Redis 7.x and 6.2) are out of scope.
- CI tests the **latest patch release of every supported line**, across every supported topology.
- We add support for a new upstream line in the **first connector minor release after its GA**. We drop a
  line in the **first connector minor release after its upstream EOL**.
- The connector detects **capabilities, not versions**. It probes `COMMAND INFO <cmd>` and `MODULE LIST` /
  `FT._LIST` once per job, so the same build works against Redis Open Source, Redis Software and Redis
  Cloud, whose version numbers do not line up. If a write option needs a command the server lacks (for
  example `SET IFEQ` or `XADD IDMP`), the job fails at planning time with a clear message rather than in the
  middle of a write.

---

## 2. Architecture

```mermaid
flowchart LR
  subgraph Clients
    PY[PySpark] --- CON[Spark Connect<br/>Python / Scala]
    SQL[Spark SQL] --- SC[Scala / Java]
  end
  Clients --> DS

  subgraph sqlmod["sql module (DataSource V2)"]
    DS[RedisTableProvider<br/>format 'redis'] --> CAT[RedisCatalog<br/>TableCatalog + FunctionCatalog]
    DS --> T[RedisTable]
    T --> RB[ScanBuilder<br/>pushdown planner]
    T --> WB[WriteBuilder<br/>distribution + row-level ops]
    T --> MS[MicroBatchStream<br/>source]
    T --> SW[StreamingWrite<br/>sink]
  end

  subgraph coremod["core module (no Spark SQL dependency)"]
    MOD[Table models<br/>hash, json, string, stream,<br/>timeseries, vectorset, zset, list, set]
    EX[RedisExecutor SPI<br/>Jedis implementation]
    TOP[Topology<br/>CLUSTER SHARDS, MOVED/ASK refresh,<br/>Sentinel, replicas]
    CAP[Capability probe]
    MET[Metrics + structured logging]
  end

  RB & WB & MS & SW --> MOD --> EX --> TOP
  EX --> CAP
  EX --> MET
  TOP --> R[(Redis 8<br/>OSS / Software / Cloud)]
```

### Modules

| Module | Contents | Depends on |
|---|---|---|
| `core` | Config, connections, topology, capability probe, table-model codecs, retry and backoff, metrics | Jedis, slf4j |
| `sql` | DataSource V2 provider, catalog, scan and write builders, streaming source and sink, SQL functions | `core`, Spark SQL (`provided`) |
| `python` | The `spark-redis` pip package: option helpers, docs, and a pure-Python fallback data source | PySpark, redis-py |
| `it` | Testcontainers integration tests, golden-layout compatibility tests, benchmarks | all |

There is no compatibility module. The migration tool, `spark-redis-migrate`, lives on `branch-3.x`, because it
needs the Scala 2.12 classpath to read 3.x's Java-serialized data (§2.11).

- **New package:** `com.redis.sparkredis`. New code is not added under `org.apache.spark.*`. The old package
  was used to reach `private[sql]` APIs, which is exactly the coupling we are removing. The old
  format name is not kept as an alias. A stub `org.apache.spark.sql.redis.DefaultSource` fails with a
  message telling the user to switch to `format("redis")`.
- **Build.** Maven multi-module with the Maven Wrapper; the stale sbt 0.13 files are deleted. Spark now
  ships a new minor line every quarter and adds connector interfaces in minors (4.1, 4.2 and 4.3 each added
  some). We therefore publish **one artifact per supported Spark minor**, following Iceberg's
  `iceberg-spark-runtime-<spark>_<scala>` convention, for example `com.redis:spark-redis-spark-4.2_2.13`. All
  artifacts share one source tree, and thin `spark-4.x` overlay source sets hold anything that needs a newer
  interface. Adding a Spark line means adding an overlay and a CI matrix entry. Dropping one means deleting
  them.
- **Coexisting with the Redis Connector for Spark.** That connector uses the `com.redis.spark` package and
  also registers the format name `redis`. Our separate package means no class names overlap. We keep the
  format name `redis`, because it is the natural one and 3.x already registers it for streaming. If both
  connectors are installed on one cluster, Spark cannot resolve `format("redis")` and fails with "Multiple
  sources found for redis". The docs say to install only one, or to name ours by its full class name
  (`format("com.redis.sparkredis.RedisTableProvider")`). The troubleshooting guide covers that error.

### 2.1 Client layer

- **Client: Jedis 8** (`RedisClient` / `RedisClusterClient`; Jedis 8 removes the `UnifiedJedis`
  constructors and deprecates `JedisCluster`), used behind an internal `RedisExecutor` interface so that a Lettuce implementation could be added later without touching
  `sql`. We chose Jedis because:
  1. JSON, Query Engine, TimeSeries, probabilistic and Vector Set commands are already built in.
  2. It has cluster-aware pipelining (`ClusterPipeline`).
  3. It supports RESP3, the only protocol this connector speaks (see *Protocol* below).
  4. It is maintained by Redis.
  5. It needs the least rewriting from today's code.
  
  A Spark task already gets its parallelism from Spark itself. What each task needs is deep pipelining,
  not async I/O.
- **Connections:** one client per executor JVM per *connection profile*. The pool is keyed by a hash of the
  config and never by the password itself. Pool size defaults to `spark.executor.cores × 2` and can be
  overridden. Exhausting the pool is reported with a clear message and is visible in metrics. Resolves
  [#382](https://github.com/RedisLabs/spark-redis/issues/382),
  [#357](https://github.com/RedisLabs/spark-redis/issues/357),
  [#341](https://github.com/RedisLabs/spark-redis/issues/341),
  [#325](https://github.com/RedisLabs/spark-redis/issues/325),
  [#237](https://github.com/RedisLabs/spark-redis/issues/237) and
  [#90](https://github.com/RedisLabs/spark-redis/issues/90).
- **Protocol: RESP3 only.**
  - Connections open with `HELLO 3`, which sends `AUTH` and `SETNAME` in the same round trip. We set the
    protocol explicitly instead of relying on Jedis 8's automatic fallback to RESP2. A server or proxy
    that refuses `HELLO 3` fails the job when it connects, with an error that names RESP3. The
    connector never quietly downgrades.
  - Codecs read RESP3's typed replies directly: maps for `HGETALL`, `XINFO` and Query Engine results,
    doubles for sorted-set scores, and booleans. That removes today's hand-rolled reply parsing, such as
    the custom `XINFO` decoder in `ConnectionUtils`.
  - Jedis's newer resilience features need RESP3. Smart Client Handoffs (Jedis 8.1) help long-running
    jobs keep their connections through planned maintenance on Redis Cloud and Redis Software.
    Client-side caching also needs RESP3. Bulk scans gain nothing from it, so it stays off.
  - Jedis 8's `ClusterPipeline` takes the protocol explicitly, so we always build it with RESP3.
  - With one protocol there is one code path to test, and CI has no RESP2 row.
- **Topology:**
  - Use `CLUSTER SHARDS` and refresh on `MOVED`/`ASK`. A partition re-resolves its slot owner instead of
    failing, which keeps reads correct during resharding and atomic slot migration.
  - Node roles come from `ROLE`, `CLUSTER SHARDS` and Sentinel. The connector never parses `INFO` text, which
    is how 3.x finds replicas today.
  - Supported deployment modes: standalone; **Sentinel** (resolves
    [#207](https://github.com/RedisLabs/spark-redis/issues/207),
    [#329](https://github.com/RedisLabs/spark-redis/issues/329),
    [#270](https://github.com/RedisLabs/spark-redis/issues/270),
    [#153](https://github.com/RedisLabs/spark-redis/issues/153),
    [#127](https://github.com/RedisLabs/spark-redis/issues/127)); OSS Cluster; and the Redis Software or
    Cloud proxy endpoint.
  - New option `read.from = primary | replica | replica-preferred` (resolves
    [#76](https://github.com/RedisLabs/spark-redis/issues/76)).
- **Proxy endpoints (Redis Software and Redis Cloud):** a proxy hides individual shards, so partitioning by
  slot is impossible unless the *OSS Cluster API* is enabled. Without it, we partition through Query Engine
  cursors when an index exists, or fall back to a single scan stream that is then repartitioned. The docs
  will recommend enabling the OSS Cluster API for bulk jobs.
- **Active-Active and multi-endpoint failover** use Jedis `MultiDbClient`, for writes that must survive a
  regional failover.
- **Upgrade note for users:** Jedis 8 turns on TLS hostname verification by default. Certificates that
  passed under 3.x can now fail, and `ssl.verify.hostname = false` is the documented escape hatch.

### 2.2 Table models

The `model` option chooses how a Spark row maps onto Redis, and it defaults to **`json`**. When a table has a
catalog entry, the entry's model wins, so tables migrated from 3.x keep `hash`. Every model implements one interface
(`RedisTableModel`: `encode`, `decode`, `readPlan`, `writePlan`, `capabilities`). That is the seam that lets
the work be split into parallel lanes (see `roadmap.md`).

| `model` | Redis type | Row shape | Read path | Write path | Needs more than the 8.2 floor |
|---|---|---|---|---|---|
| `hash` | Hash | one hash = one row | `HMGET` of the schema's fields, narrowed further by column pruning | `HSET`; `HSETEX` for per-field TTL; `HIMPORT` for bulk loads | `HIMPORT`: 8.10, Open Source only |
| `json` (default) | JSON | one document = one row; nested `struct`, `array`, `map` and `VARIANT` columns | `JSON.MGET` grouped by slot; JSONPath projection | `JSON.SET` / `JSON.MSET`; `JSON.MERGE` for partial updates | — |
| `string` | String | `_key`, `value` (string or binary) | `MGET` grouped by slot | `SET`; `MSETEX` for batches with TTL | `MSETEX`: 8.4 |
| `stream` | Stream | one entry = one row; `_id` metadata column | `XRANGE`, split by ID range | `XADD`, with `IDMP` for exactly-once | `IDMP`: 8.6 |
| `timeseries` | TimeSeries | `(_key, timestamp, value, labels…)` | `TS.MRANGE FILTER`, with aggregation pushdown | `TS.MADD`, with `TS.CREATE` labels | `TS.NRANGE`: 8.10 |
| `vectorset` | Vector Set | `(_key, element, vector, attributes)` | `VSIM` KNN reads (with `FILTER` on attributes); full export is an open item, because there is no confirmed enumeration command | `VADD` with quantization and `SETATTR` | — |
| `zset` / `list` / `set` | Sorted set, list, set | one member = one row (`_key`, `member`, `score` or `index`) | `ZSCAN` (or `ZRANGE … BYSCORE` when a score range is pushed down) / `LRANGE` in bounded chunks / `SSCAN` | `ZADD` / `RPUSH` (with `LTRIM` in the same `MULTI`/`EXEC` when `list.max.length` caps the list) / `SADD` | — |
| `binary` (legacy) | String | 3.x Java-serialized rows | none in the connector; only `spark-redis-migrate` reads it, to move the data off | — | — |

Two smaller additions:

- **Vectors inside hashes and JSON:** an `array<float>` column tagged `redis.vector = FLOAT32` (or another
  element type) is written as the binary blob a Query Engine `VECTOR` field expects.
- **Probabilistic types:** write-only "accumulator" options (`BF.MADD`, `CF.ADD`, `CMS.INCRBY`, `TOPK.ADD`,
  `TDIGEST.ADD`) plus a `redis_bf_exists` SQL function for deduplication and lookups. GEO support is a
  `zset` sub-mode returning `(member, lon, lat)`
  ([#185](https://github.com/RedisLabs/spark-redis/issues/185)).

### 2.3 Read path and pushdown

For each scan, the `ScanBuilder` picks the **cheapest strategy that can answer the query exactly**.

1. **Point lookup.** Predicates `_key = ?`, `_key IN (…)` or `<key.column> = ?` turn into a direct fetch
   with no `SCAN` at all. We also implement `SupportsRuntimeV2Filtering`, so when a large fact table joins
   a Redis table on the key, Spark passes the join keys down at runtime and we fetch only those keys. This
   is the enrichment-join pattern, and it resolves
   [#180](https://github.com/RedisLabs/spark-redis/issues/180) and
   [#297](https://github.com/RedisLabs/spark-redis/issues/297).
2. **Query Engine.** This applies when an index covers the table's key prefix, either named with the
   `index` option or discovered through `FT._LIST` / `FT.INFO`. Since 8.0 the Query Engine also runs on **Redis Open Source Cluster**, through the coordinator. Three caveats apply. `FT.*` commands are rejected inside `MULTI` or Lua on multi-shard databases. Cursors can miss results during atomic slot migration, a known issue in 8.6, so the planner falls back to key scan while a migration is in progress. It detects this with `CLUSTER MIGRATION STATUS` on 8.12+, or from `ASK` redirects on older lines. And on Redis Software it needs RS 8.0+ with a database at 8.0+ when the OSS Cluster API is on. Reads use Jedis 8's `ftAggregateIterator()`, which replaces the cursor API Jedis now calls unreliable on clusters.
   - **Filters:** V2 predicates (`=`, `<`, `<=`, `>`, `>=`, `IN`, `IS NULL`, `StartsWith`, `AND`, `OR`,
     `NOT`) become query syntax: `@tag:{a|b}`, `@num:[lo hi]`, prefix and text terms.
   - **Columns:** column pruning becomes `RETURN` / `LOAD`.
   - **Limit and TopN:** `SupportsPushDownLimit` and `SupportsPushDownTopN` become `LIMIT` and `SORTBY`.
   - **Aggregates:** `SupportsPushDownAggregates` (`COUNT`, `SUM`, `MIN`, `MAX`, `AVG` with `GROUP BY`)
     becomes `FT.AGGREGATE … GROUPBY … REDUCE`.
   - **Pagination:** large results are paged with `WITHCURSOR`.
   - **Vector KNN:** the `vector.query` and `vector.k` options (and hybrid text plus vector search
     with `FT.HYBRID`, 8.4+) return a `_score` metadata column.
   - **`VARIANT` paths:** with `model = json`, `SupportsPushDownVariantExtractions` (Spark 4.1) turns
     `variant_get(doc, '$.address.city', 'string')` into a JSONPath fetch (`JSON.GET key $.address.city`),
     so Redis returns only the fields the query needs. From Spark 4.3, `variant_get` *filters* are pushed
     down as well and become Query Engine predicates when the path is indexed.
3. **Key scan, the fallback.** Run `SCAN MATCH <prefix>* TYPE <type> COUNT n` on each primary (or
   replica) shard, with each partition covering a slot range. Using `TYPE` server-side replaces today's
   extra `TYPE` round trip. Values are fetched with slot-grouped `MGET` / `JSON.MGET` or pipelined
   `HMGET`. Resolves [#187](https://github.com/RedisLabs/spark-redis/issues/187),
   [#86](https://github.com/RedisLabs/spark-redis/issues/86),
   [#202](https://github.com/RedisLabs/spark-redis/issues/202),
   [#303](https://github.com/RedisLabs/spark-redis/issues/303) and
   [#264](https://github.com/RedisLabs/spark-redis/issues/264).

Further read behaviour:

- **Accurate filter reporting.** Only filters that were *not* pushed are returned as post-scan filters.
  Today the connector returns every filter.
- **Statistics.** `SupportsReportStatistics` uses `DBSIZE`, `FT.INFO num_docs` and `MEMORY USAGE` sampling,
  so Spark can choose broadcast joins correctly.
- **Metadata columns** (`SupportsMetadataColumns`): `_key`, `_ttl` (remaining milliseconds, from `PTTL` or
  `HPTTL`), `_slot`, `_id` (streams) and `_score` (search).
- **Partition count.** This is derived from per-slot key counts reported by `CLUSTER SLOT-STATS` (Redis 8.2)
  and a target number of keys per partition (`read.keys.per.partition`),
  replacing the fixed default `partitions.number = 3` (resolves
  [#366](https://github.com/RedisLabs/spark-redis/issues/366) and
  [#300](https://github.com/RedisLabs/spark-redis/issues/300)).

### 2.4 Write path

- **Keys are deterministic, so replays are safe.** Every row's key comes from the data, never from a
  random number:
  - `key.column` names the column that holds the key, and the table's prefix is added in front.
  - `key.generate = content-hash` is for data with no natural key. It derives the key from a 128-bit digest
    (for example XXH3-128) of the row's canonical encoding, which is defined by the connector, not by
    Spark's internal row format. Identical rows map to the same key and collapse into one, and the docs say
    so.

  A write with neither fails at planning. 3.x generated a random UUID key in that case, so a Spark task
  retry or a replayed micro-batch wrote the same rows again under new keys. With deterministic keys, a
  retry or replay rewrites the same keys, which is what makes the sink promise in §2.5 true.
- **Distribution.** The writer implements `RequiresDistributionAndOrdering`, clustered by
  `redis_slot(_key)`. Each task then writes to one shard, which means fewer connections, fuller pipelines
  and no cross-shard fan-out. This can be turned off with `write.distribution = none`. Resolves
  [#131](https://github.com/RedisLabs/spark-redis/issues/131),
  [#271](https://github.com/RedisLabs/spark-redis/issues/271),
  [#283](https://github.com/RedisLabs/spark-redis/issues/283) and
  [#138](https://github.com/RedisLabs/spark-redis/issues/138).
- **Save modes:**
  - **Append** is an *upsert*. The new option `write.mode = merge | replace` controls what happens to
    fields missing from the new row: `merge` keeps them (today's behaviour), and `replace` swaps the
    whole row atomically per key with `MULTI` / `UNLINK` / `HSET` (resolves
    [#351](https://github.com/RedisLabs/spark-redis/issues/351)).
  - **Overwrite** truncates the table with a per-shard `SCAN` + `UNLINK`, or with `FT.DROPINDEX DD` when
    the table is backed by an index.
  - For index-backed tables there is also **`overwrite.strategy = swap`**: write under a new prefix, run
    `FT.ALIASUPDATE`, then drop the old data asynchronously. Readers never see an empty table. Resolves
    [#389](https://github.com/RedisLabs/spark-redis/issues/389),
    [#332](https://github.com/RedisLabs/spark-redis/issues/332) and
    [#285](https://github.com/RedisLabs/spark-redis/issues/285).
- **Row-level operations.** `SupportsRowLevelOperations` is delta-based (`SupportsDelta`, with `_key` as the
  row ID), so `MERGE INTO`, `UPDATE` and `DELETE FROM … WHERE` run natively. Deleting by predicate is
  pushed through the Query Engine when an index exists. Resolves
  [#323](https://github.com/RedisLabs/spark-redis/issues/323) and
  [#340](https://github.com/RedisLabs/spark-redis/issues/340).
- **Conditional writes.** New option `write.condition = none | nx | xx | ifeq:<column>`. It maps to
  `SET NX/XX`, `HSETEX FNX/FXX` and compare-and-set `SET IFEQ`/`IFNE`/`IFDEQ`/`IFDNE` (8.4+; the `IFDEQ` form compares against a `DIGEST`, so no value has to be fetched). Conditional deletes use `DELEX` (8.4+). Resolves
  [#260](https://github.com/RedisLabs/spark-redis/issues/260) and
  [#204](https://github.com/RedisLabs/spark-redis/issues/204).
- **TTL** can be set four ways: table-level `ttl`; per-row `ttl.column` or `expireat.column`; and, for
  hashes, per-field TTL with `HSETEX`. A value and its TTL are always written atomically:
  - with one command where Redis has one: `SET … EX`, `MSETEX`, `HSETEX … EX`
  - otherwise in one `MULTI`/`EXEC` on the key: `HSET` + `EXPIRE` for a row TTL, `JSON.SET` + `EXPIRE` for
    JSON

  3.x sends a separate `EXPIRE` after the write. 4.x never does (§2.14).

  **Decision: row TTL stays key-level, as in 3.x.** We considered relying on `HSETEX` alone and rejected it,
  because it changes how expiry behaves:
  - `TTL key` returns -1.
  - A partial upsert in `merge` mode refreshes only the fields it writes, so a row can lose its older columns
    first.
  - A field another writer adds without a TTL keeps the key alive.
  - JSON has no field-TTL command at all.

  Key-level TTL costs two extra commands per key (`MULTI` and `EXEC`) inside the same pipelined round trip.
  The Phase 2 write benchmark measures that cost.
- **Binary data.** `BinaryType` columns are written as raw bytes rather than `String.valueOf`. Resolves
  [#205](https://github.com/RedisLabs/spark-redis/issues/205),
  [#304](https://github.com/RedisLabs/spark-redis/issues/304),
  [#320](https://github.com/RedisLabs/spark-redis/issues/320) and
  [#120](https://github.com/RedisLabs/spark-redis/issues/120).
- **Failures are loud.** Any error reply fails the task, and Spark retries it. Pipelines are sized by
  command count *and* by bytes (`pipeline.max.commands`, `pipeline.max.bytes`). Silent partial writes are treated as a bug class with dedicated tests.
  Resolves [#347](https://github.com/RedisLabs/spark-redis/issues/347),
  [#375](https://github.com/RedisLabs/spark-redis/issues/375),
  [#359](https://github.com/RedisLabs/spark-redis/issues/359),
  [#267](https://github.com/RedisLabs/spark-redis/issues/267) and
  [#197](https://github.com/RedisLabs/spark-redis/issues/197).

### 2.5 Streaming

**Source (`MicroBatchStream`):**

- **Offsets.** An offset is the last entry ID per stream key, and it lives in the **Spark checkpoint**,
  which is the single source of truth. Today's consumer-group `SETID` dance goes away.
- **`stream.read.mode = range` (default).** Reads use `XRANGE` with an exclusive start, bounded by the end
  offset, so every batch replays deterministically. Entry IDs start with a millisecond timestamp, so one
  stream can be split into several partitions by time range (`stream.partitions`). Resolves
  [#224](https://github.com/RedisLabs/spark-redis/issues/224).
- **`stream.read.mode = group`**, for sharing a stream with other consumers. It reads with `XREADGROUP`
  (without `NOACK`) and acknowledges after Spark commits, using `XACK`, or `XACKDEL` (8.2+) to acknowledge and delete in one step. When a Spark task fails, its claimed entries are released straight away with `XNACK` (8.8+) rather than waiting for the idle timeout. It
  takes over entries left idle by dead consumers with `XAUTOCLAIM` or the `XREADGROUP CLAIM` option
  (8.4+). Reads are bounded by bytes as well as by count with `XREADGROUP MAXSIZE` (8.10+).
- **Admission control and triggers.** `SupportsAdmissionControl` caps entries per trigger with
  `maxEntriesPerTrigger` (resolves [#178](https://github.com/RedisLabs/spark-redis/issues/178)).
  `SupportsTriggerAvailableNow` is also implemented.
- **Metrics.** `ReportsSourceMetrics` exposes lag per stream and per group (`XINFO GROUPS lag`).
- **Retention.** The option `stream.trim.acked = true` uses `XTRIM … ACKED` (8.2+) so that
  trimming never removes entries that have not been processed yet.
- **Real-Time Mode** (Spark 4.1+, stateful from 4.3). The source implements `SupportsRealTimeMode` and
  its partition reader implements `SupportsRealTimeRead`, built on a blocking `XREAD`/`XREADGROUP` with
  `nextWithTimeout`. There are two catches:
  - Both interfaces are `@Evolving`.
  - Upstream allowlists **only Kafka** as a Real-Time Mode source (`spark.sql.streaming.realTimeMode.allowlistCheck`
    defaults to `true`).
  
  So the Real-Time Mode source ships behind a flag in its own module, and we open an upstream discussion
  about making the allowlist extensible. **Writing to Redis in Real-Time Mode already works** through the
  Foreach sink, so the connector ships a supported `RedisForeachWriter`.

**Sinks:**

- **Every table model is also a streaming sink.** Keys are always deterministic (§2.4), so upserts are
  idempotent: replaying a batch rewrites the same keys and gives effectively exactly-once results.
- **`model = stream` sink.** Writes with `XADD`. Delivery is exactly-once when the server supports
  idempotent producers (`XADD IDMP`, 8.6+). The producer ID is derived from `(queryId, partition)` and the
  sequence number from `(batchId, row)`. Note that IDMP has a known limitation with AOF when
  `aof-use-rdb-preamble no`. Otherwise it is at-least-once, with a deterministic
  `(batchId, partition, seq)` field so readers can deduplicate. Resolves
  [#190](https://github.com/RedisLabs/spark-redis/issues/190).
- **DStreams** (`RedisInputDStream` and the list and stream receivers) are **removed** in 4.x. Spark has
  deprecated DStreams since 3.4 and calls them legacy, and they cannot be used through Spark Connect. They
  remain available in the 3.x maintenance branch.
- **Streams are the only streaming source and sink.** There is no list (`BLPOP`), Pub/Sub or
  keyspace-notification source (§2.14).

### 2.6 SQL, catalog and functions

The catalog is enabled like this:

```properties
spark.sql.catalog.redis              = com.redis.sparkredis.RedisCatalog
spark.sql.catalog.redis.url          = rediss://default:${secret}@redis-12000.example.com:12000
spark.sql.catalog.redis.read.from    = replica-preferred
```

- **Namespaces** are connection profiles. Numbered logical databases (`SELECT`) are not supported (§2.14).
  This replaces the hand-built multi-cluster setup
  in `doc/cluster.md`.
- **Tables** are catalog entries plus discovered Query Engine indexes.
- **`CREATE TABLE redis.app.users (…) USING redis TBLPROPERTIES (model='json', prefix='user:', index='true')`**
  runs `FT.CREATE` with a schema derived from the Spark schema. `DROP TABLE … PURGE` drops the index and
  its data.
  `ALTER TABLE … ADD COLUMN` updates the catalog entry and runs `FT.ALTER … SCHEMA ADD`. Automatic
  schema evolution on write (`SupportsSchemaEvolution`) is available in the Spark 4.2+ artifacts.
- **Table metadata** moves from a Java-serialized `StructType` stored in `_spark:<table>:schema` to a JSON
  document at `_spark:catalog:<ns>:<table>`. The document holds the schema JSON, model, key column, index
  name and format version.
- **Functions** (`FunctionCatalog`):
  - `redis_slot(key)`, which is also used for write distribution.
  - `redis_vector(array<float>, 'FLOAT32')`, which returns a binary blob.
  - `redis_bf_exists(filter, item)`.
  - KNN as a table-valued function is planned as a Python UDTF until DSv2 exposes TVFs.

### 2.7 Python and Spark Connect

- **Every feature is reachable through options and SQL.** That makes it work the same way from PySpark,
  Spark SQL, and the Spark Connect Python and Scala clients, with no Python-side JVM bridging. 4.x has no
  RDD API, and Spark Connect could not reach one anyway. The DataFrame and SQL API is the only one
  (resolves [#379](https://github.com/RedisLabs/spark-redis/issues/379),
  [#352](https://github.com/RedisLabs/spark-redis/issues/352),
  [#278](https://github.com/RedisLabs/spark-redis/issues/278) and
  [#324](https://github.com/RedisLabs/spark-redis/issues/324)).
- **`pip install spark-redis`:**
  - Typed helpers, for example `spark_redis.read_json(spark, prefix="user:", index="idx:user")`.
  - Docstrings.
  - A **pure-Python `DataSource`** (PySpark Python Data Source API, built on redis-py with `protocol=3`). It is meant for
    serverless environments that do not allow custom JARs, and supports only hash and JSON batch
    reads and writes and stream reads. Filter pushdown works on
    Spark 4.1. Streaming admission control and `AvailableNow` need Spark 4.2.
- **CI** runs the Python test suite against a local Spark Connect server that has the JAR loaded.

### 2.8 Types and semantics

| Spark type | `hash` encoding | `json` encoding |
|---|---|---|
| string / numeric / boolean | UTF-8 text (unchanged from 3.x) | native JSON |
| date / timestamp / timestamp_ntz | ISO-8601 by default; `timestamp.format = iso \| epoch_millis` | same |
| binary | raw bytes | base64 |
| struct / array / map | not supported; use the `json` model | native |
| `VARIANT` | not supported; use the `json` model | native document (round-trips losslessly) |
| `array<float>` tagged as a vector | FLOAT32 blob | JSON array |

Related behaviour:

- **ANSI mode.** Spark 4 turns ANSI mode on by default. Parse failures follow the new option
  `on.parse.error = fail | null`, whose default is `fail` when ANSI mode is on. Today a parse failure
  throws `NumberFormatException` and kills the task with no context (resolves
  [#358](https://github.com/RedisLabs/spark-redis/issues/358)).
- **Collations.** Predicates on columns with a non-binary collation are not pushed down.
- **Schema inference.** The schema comes from `FT.INFO` when an index exists, with real types: `NUMERIC`
  becomes double, `TAG` becomes string, and `VECTOR` becomes array. Otherwise it is inferred from a
  configurable sample of N keys, not from a single random key (resolves
  [#350](https://github.com/RedisLabs/spark-redis/issues/350)).

### 2.9 Configuration and security

- **One URL option.** `spark.redis.url`, or `url` on a single source, accepts `redis://`, `rediss://` and
  `redis-sentinel://`, and rejects a database number in the path (§2.14) (resolves
  [#349](https://github.com/RedisLabs/spark-redis/issues/349)). Named
  profiles use `spark.redis.<profile>.*`. A source-level option overrides **only that setting**: today,
  setting `host` silently drops the session's auth settings (resolves
  [#213](https://github.com/RedisLabs/spark-redis/issues/213) and
  [#249](https://github.com/RedisLabs/spark-redis/issues/249)).
- **Secrets.** `spark.redis.auth` is renamed `spark.redis.password`, and `auth` is no longer accepted; it fails with a message naming `password`. Spark's
  default `spark.redact.regex` does not match `auth`, so **today the password is shown in the Spark UI
  Environment tab**. Passwords, tokens and URLs containing credentials are also redacted in our own logs.
  Today `maskPassword` masks only `auth`, not URLs.
- **Authentication uses ACL users only.** Connections send `HELLO 3 AUTH <user> <password>`, and `user`
  defaults to `default`. The docs publish least-privilege ACL rules for each role (batch reader, batch
  writer, stream consumer, catalog admin), so nobody needs to run the connector as an `+@all` user
  (§2.14).
- **TLS** is configured per connection (truststore, keystore, mTLS, SNI) rather than through JVM-wide
  `javax.net.ssl.*` properties (resolves [#305](https://github.com/RedisLabs/spark-redis/issues/305)).
- **Token-based authentication** with automatic re-authentication, for example Entra ID for Azure Managed
  Redis, using Jedis `redis-authx` providers (Jedis 5.3+).
- **Client name** is set with `client.name`, sent in the `HELLO 3 … SETNAME` handshake (resolves
  [#398](https://github.com/RedisLabs/spark-redis/issues/398)). `CLIENT SETINFO lib-name` stays as it is.

### 2.10 Observability

- **Spark custom metrics** (`CustomMetric` / `CustomTaskMetric`) appear in the Spark UI SQL tab: keys
  scanned, rows read and written, bytes, round trips, retries, `MOVED`/`ASK` redirects, which pushdown
  strategy was used, and pool wait time. Metrics that are not simple sums, such as maximum pool wait,
  override `CustomTaskMetric.mergeWith`, which Spark 4.2 requires.
- **Spark 4 structured logging** adds MDC fields for table, model, shard and slot range.
- **An `EXPLAIN` view of the chosen strategy**, for example `RedisScan[strategy=QueryEngine, index=idx:user,
  pushed=[age > 30], limit=100]`.

### 2.11 Compatibility and migration from 3.x

| 3.x behaviour | 4.x behaviour |
|---|---|
| `format("org.apache.spark.sql.redis")` | Fails at planning with a message to use `format("redis")` |
| 3.x option names | Names whose meaning is unchanged keep working. Renamed or removed names fail at planning with the replacement (see *Renamed and removed options* below). There are no aliases |
| `hash` model layout `<table>:<id>`, values stored as strings | **Unchanged**, and guarded by golden-layout tests |
| `hash` as the default model | The default is now `json`. Tables migrated with `spark-redis-migrate` keep `model = hash` in their catalog entry. A 3.x job that relied on the default must set `model = hash`. If a table has no catalog entry and no `model`, and its prefix already holds hashes, the job fails at planning and says so |
| Random UUID keys when `key.column` is not set | Fails at planning: set `key.column` or `key.generate = content-hash` (§2.4) |
| `_spark:<table>:schema` (Java-serialized `StructType`) | Read once and rewritten as a JSON catalog entry by `spark-redis-migrate`. Java-serialized Scala 2.12 objects cannot be read reliably on 2.13, so the tool runs on the 3.x classpath |
| `binary` model (Java-serialized rows) | Not readable by the connector. `spark-redis-migrate --to json|hash` moves the data |
| RDD API (`sc.fromRedisKV`, `toRedisHASH`, …) | **Removed.** Jobs move to `format("redis")`; the migration guide maps every function to its table model. The existing 3.x releases still have it |
| DStreams | Removed; the existing 3.x releases still have them |
| RESP2 wire protocol (Jedis 3's default) | RESP3 only. Stored data is unchanged, because the protocol only affects the wire. Servers and proxies that cannot negotiate `HELLO 3` are not supported |
| Legacy commands (`HMSET` + `EXPIRE`, `SETEX`, `SMEMBERS`, `BLPOP`, `INFO` parsing) | Their modern replacements (§2.14). The stored data and its TTLs are the same; only the commands sent differ |
| Structured Streaming source offsets | A one-time restart from the stored `stream.offsets` is required. Checkpoints from 3.x are **not** compatible |

#### Renamed and removed options

4.x has **no aliases**. Using any name in the left column fails at planning time with the message in the
right column, and nothing is silently remapped.

| 3.x name | What to use in 4.x |
|---|---|
| `host`, `port`, `ssl` (and `spark.redis.host`, `.port`, `.ssl`) | `url` / `spark.redis.url`, for example `rediss://redis.example.com:12000` |
| `dbNum` / `spark.redis.db` | Removed. Numbered logical databases are not supported; use a separate Redis database and connection profile |
| `auth` / `spark.redis.auth` | `password` / `spark.redis.password` |
| `max.pipeline.size` / `spark.redis.max.pipeline.size` | `pipeline.max.commands`, alongside the new `pipeline.max.bytes` |
| `partitions.number` | Removed. Partitions follow slot statistics; tune them with `read.keys.per.partition` |
| `infer.schema` | Removed. When no schema is given and no catalog entry exists, the schema is inferred automatically (§2.8) |
| `filter.keys.by.type` | Removed. Key scans always filter by type with `SCAN … TYPE` |
| `iterator.grouping.size`, `spark.redis.rdd.write.iterator.grouping.size` | Removed. Nothing in DSv2 needs them |
| `model = binary` | Removed. Move the data to `json` or `hash` with `spark-redis-migrate` |
| `stream.parallelism` | `stream.partitions`: the number of ID-range splits per stream |

Everything else (`table`, `keys.pattern`, `key.column`, `model = hash`, `ttl`, `scan.count`, `user`, `timeout`,
and the other `stream.*` options) keeps its name and meaning. Note that the *default* model is now `json`
(see the table above).

**`branch-3.x`** hosts only `spark-redis-migrate`, built against Spark 3.5 and Scala 2.12. Reading 3.x's
Java-serialized schemas and `binary` rows needs the 3.x classpath, so the tool cannot live in the 4.x build.
There is **no bridge release** during the spike. Users who cannot move to Spark 4 yet stay on the existing
3.x releases. If the spike becomes the official line, a bridge release on Spark 3.5 can be reconsidered
before Spark 3.5's extended LTS ends in November 2027.

### 2.12 Verification

- **One command.** `./mvnw verify -Pit` is the agent feedback loop and the CI gate. It exits non-zero on any
  failure.
- **Renamed-option tests.** Every 3.x option name in §2.11 fails with its documented message.
- **Deterministic-key tests.** Retrying a write task or replaying a micro-batch leaves exactly the same set
  of keys.
- **Protocol tests.** Connecting to a server that refuses `HELLO 3` fails with a clear RESP3 error. A
  separate test asserts that every connection the connector opens reports `resp=3` in `CLIENT INFO`.
- **Testcontainers matrix** across every supported Redis line, in these topologies:
  - standalone
  - 3-primary/3-replica cluster
  - TLS with ACL
  - Sentinel
  - nightly: a Redis Software container
- **Spark matrix** across every supported Spark line, plus a Spark Connect suite driven from PySpark.
- **Golden-layout tests.** These are characterization tests, written in Phase 0 against the *current*
  connector, that fix the exact Redis keys and values each 3.x write produces. 4.x must reproduce them
  byte for byte.
- **Fault injection:** resharding and slot migration during a scan, primary failover during a write,
  `maxclients` exhaustion, and `OOM` replies.
- **Benchmarks** run nightly with recorded baselines: 1M-row hash write, full scan, point lookup by key
  list, a Query Engine filtered read, and stream throughput. Any regression over 10% fails the run.

### 2.13 Release engineering

- **GitHub Actions** builds, signs and publishes every release from the fork. The jars go to **GitHub
  Packages** (Maven) and are attached to a **GitHub Release**, together with a CycloneDX SBOM. OSSRH
  (`oss.sonatype.org`), which the current workflow targets, has been shut down.
- Resolving from GitHub Packages needs a GitHub token, even for public packages. The GitHub Release assets
  are a download that needs no token.
- **Maven Central** (through the Central Portal) and **PyPI** wait for an official release, after the
  spike. Both can be added to the same GitHub Actions workflow, with PyPI using trusted publishing (OIDC).
- `4.0.0` is the first release for Spark 4. Release Drafter, which the repo already uses, writes release
  notes from PR labels.


### 2.14 Modern only: legacy patterns and APIs we do not keep

Redis keeps legacy commands working for backward compatibility, even after it has shipped strictly better
replacements. The 4.x connector uses **only the replacements**. It never sends the legacy form, and no option
brings one back. A hook and a CI check enforce this (`roadmap.md`, Phase 0).

| Legacy pattern | Where 3.x uses it | What 4.x uses instead |
|---|---|---|
| `KEYS`, and whole-collection reads such as `SMEMBERS`, `HGETALL` and `LRANGE 0 -1` | `RedisRDD.scala` (`smembers`, `hgetAll`, `lrange`) and schema inference | `SCAN … TYPE` for keys. `SSCAN`, `ZSCAN` and `HSCAN` for collections. `LRANGE` in bounded chunks. `HMGET` of the schema's fields for row-shaped hashes |
| Password-only `AUTH <password>` with `requirepass` | Test setup (`Makefile`) | ACL users. Every connection authenticates with `HELLO 3 AUTH <user> <password>`. `user` defaults to `default`, so a server configured only with `requirepass` still works, because that password belongs to the `default` user. The docs ship least-privilege ACL rules for reader, writer and stream-consumer roles. Tests use named ACL users |
| Lua `EVAL` / `EVALSHA` | Not used today | Not used. Native atomic commands cover every atomic step we need: `HSETEX`, `SET IFEQ`, `DELEX`, and `MULTI`/`EXEC` on a single key. If a later feature needs server-side logic, it uses Redis Functions (`FUNCTION LOAD` / `FCALL`) |
| Lists (`BLPOP`) and Pub/Sub used as queues | `RedisInputDStream` (`blpop`) | Streams are the only streaming source and sink. Lists remain a batch table model. There is no list, Pub/Sub or keyspace-notification streaming |
| Separate commands where one atomic command exists: `SET` then `EXPIRE`, `HMSET` then `EXPIRE`, `SETEX`, `SETNX`, `GETSET` | `HashRedisPersistence`, `BinaryRedisPersistence`, `redisFunctions.scala` (`hmset`, `setex`, `expire`) | One atomic command where Redis has one: `SET … EX/PX/NX/XX/GET`, `MSETEX`, and `HSETEX` for field TTLs. Where Redis has no single command, `MULTI`/`EXEC` on the one key: `HSET` + `EXPIRE` for a row TTL, or `JSON.SET` + `EXPIRE`. A value and its TTL are never written in separate, non-atomic steps |
| Older command forms that have modern replacements: `HMSET`, `ZRANGEBYSCORE`, `DEL` on large keys, `CLUSTER SLOTS`, parsing `INFO` text for topology | `RedisRDD.scala`, `RedisSourceRelation.scala`, `RedisConfig.scala` | `HSET`; `ZRANGE … BYSCORE` or `ZSCAN`; `UNLINK`; `CLUSTER SHARDS`; `ROLE` and Sentinel |
| Numbered logical databases (`SELECT`, `dbNum`) | `RedisConfig.scala` (`dbNum`), `spark.redis.db` | One Redis database per connection profile. Redis Cluster supports only database 0, and on Redis Software and Redis Cloud each database is its own endpoint |
| JSON or serialized blobs stored in string keys or hash fields | Not used for rows today, but the `binary` model stores rows the same way using Java serialization | The `json` model. No mode writes JSON or a serialized blob into a string or a hash field. Only `spark-redis-migrate` reads the `binary` model, to move data off it |

#### Legacy Spark and connector APIs we do not keep

| Legacy API | What 4.x offers instead |
|---|---|
| RDD API (`sc.fromRedis*`, `rdd.toRedis*`, `RedisContext`) | The DataFrame and SQL table models (§2.2). The migration guide maps every RDD function to its replacement. The RDD API cannot be reached from Spark Connect or PySpark anyway |
| DStreams (`RedisInputDStream`, `RedisStreamingContext`) | Structured Streaming on DSv2 (§2.5) |
| DataSource V1 (`RelationProvider`, `PrunedFilteredScan`, the internal V1 streaming `Source`) | DataSource V2 throughout |
| 3.x option names and `spark.redis.*` keys that changed | Planning-time errors that name the replacement (§2.11). No aliases |
| `format("org.apache.spark.sql.redis")` | `format("redis")`. A stub class at the old name fails with a message that says so |
| The `binary` model (Java-serialized rows) | `json` or `hash`, through `spark-redis-migrate` |

The existing 3.x releases still have all of these, for jobs that cannot move yet.

---

## 3. Risks

| Risk | Mitigation |
|---|---|
| Query Engine coverage differs between OSS Cluster and Redis Software/Cloud | Use capability probing. Pushdown is an optimisation, and key scan is always a correct fallback |
| Spark-internal API drift between 4.x lines | Use only public DSv2 and Connector APIs. A CI check fails on new imports from `org.apache.spark.sql.catalyst.*` / `execution.*` outside an allowlist |
| Upstream marks Real-Time Mode or another API experimental or changes it | Put it behind a feature flag in its own module, outside the GA support promise |
| A proxy or managed endpoint in the path does not support RESP3 | Fail fast at connect with a RESP3-specific error. Phase 0 checks Redis Software 8.2 and Redis Cloud databases with `HELLO 3`, and the docs list any unsupported proxies |
| Some Query Engine replies may cost more to build in RESP3 than in RESP2 | Benchmark Query Engine reads under RESP3 against the Phase 0 baseline in Phases 2 and 4b. Raise any regression upstream rather than bringing RESP2 back |
| Jobs built on the RDD API cannot upgrade without code changes | A migration guide that maps every RDD function and old option to its 4.x replacement, golden-layout tests showing the replacement writes the same data, 4.0.0-M1 still shipping the RDD API (deprecated) so jobs can move to Spark 4 first, and the existing 3.x releases for jobs that cannot move yet |
| A 3.x job that relied on `hash` being the default model would now write JSON | A table's catalog entry sets its model, and migrated tables keep `hash`. A table with no catalog entry and no `model` whose prefix already holds hashes fails at planning. The migration guide lists this change first |
| This connector and the Redis Connector for Spark are installed on the same cluster | Both register `redis`, so `format("redis")` fails for both. Separate packages avoid class clashes. The docs say to install one, or to use our full class name, and the troubleshooting guide covers the error |
| Data written by 3.x is unreadable after upgrade | Golden-layout tests, the migration tool, and a documented one-time migration step |
| Maintainer bandwidth (see [#393](https://github.com/RedisLabs/spark-redis/issues/393), [#384](https://github.com/RedisLabs/spark-redis/issues/384)) | Name code owners and a release manager (intent Q4). Automate version-matrix bumps |

## 4. Out of scope (revisit after 4.2)

- A Redis-backed Structured Streaming `StateStoreProvider` (research spike only).
- Graph (RedisGraph is end-of-life; see [#281](https://github.com/RedisLabs/spark-redis/issues/281)).
- Custom-command passthrough for writes ([#342](https://github.com/RedisLabs/spark-redis/issues/342)).
  Revisit once row-level operations ship.
