# Intent: spark-redis for Spark 4 and Redis 8

> Stage: **Plan** (AI-native SDLC playbook). Status: **development spike** in the `Redislabs-Solution-Architects/spark-redis` fork. Decisions are
> recorded at the end.
> Next artifact: [`spec.md`](spec.md) → [`roadmap.md`](roadmap.md) → one `plan.md` per phase.

## Problem

spark-redis was last brought up to date for Spark 3.2.1 (2022). The build is still Scala 2.12 on Java 8 with
Jedis 3.9, and the connector talks to Redis the way Redis 5 and 6 did. As a result:

- **It cannot run on any current Spark.** Spark 4 is Scala 2.13 only and needs JDK 17 or later. The
  connector also depends on internal APIs (`org.apache.spark.sql.execution.streaming.Source`,
  `SQLContext.internalCreateDataFrame`) and on Java-serialized `StructType` blobs. Neither can be relied on
  across Spark and Scala versions.
- **It ignores most of Redis 8.** JSON, the Query Engine, Vector Sets, TimeSeries and the probabilistic types
  are now part of core Redis. The connector sees only strings, hashes, lists, sets, sorted sets and streams.
  Every DataFrame read is a full `SCAN MATCH` followed by per-key fetches, and filters are never pushed down.
- **Its streaming support is only partial.** The Structured Streaming source uses the V1 API, reads with
  `NOACK`, and has a no-op `commit`. There is no streaming sink, and the DStream API it also offers has been
  deprecated in Spark since 3.4.
- **It is hard to operate safely.** Cluster topology is read once with `CLUSTER SLOTS` and never refreshed.
  Connection pools have fixed sizes. There are no metrics. Authentication is password-only.
- **It cannot be released.** The publishing workflow targets OSSRH (`oss.sonatype.org`), which Sonatype has
  retired.

## Outcome we want

A Spark connector built for today's platforms, the one we would design if we started now:

1. **It runs on every Spark and Redis line still in active maintenance.** The exact matrix is set in
   `spec.md` and enforced in CI.
2. **Redis 8 data models are first-class Spark tables.** That covers Hash, JSON, Stream, TimeSeries and
   Vector Set, with the Query Engine used for filter, projection, limit and aggregate pushdown.
3. **It is built on DataSource V2 throughout**, for batch reads and writes and for streaming sources and
   sinks with exactly-once or idempotent guarantees. A catalog lets `SELECT * FROM redis.db.table` work in SQL.
4. **PySpark and Spark Connect are first-class clients**, not something only reachable through Scala.
5. **It is production-grade:** it is cluster-topology aware, observable through Spark custom metrics, and
   supports modern auth (ACL, TLS, token-based auth).
6. **Existing users have a documented migration path.** That includes reading data written by spark-redis
   3.x.

## Users and systems

| Who | What they need |
|---|---|
| Data engineers (Scala, PySpark, SQL) | Bulk load and export, CDC-style upserts, SQL access |
| ML and AI engineers | Feature-store serving (Spark → Redis), embedding and vector ingestion, hybrid search export |
| Streaming engineers | Redis Streams as a reliable source and sink, low-latency pipelines |
| Platform teams | Databricks, EMR, Dataproc, Spark on Kubernetes; Redis Open Source, Redis Software, Redis Cloud |
| Existing spark-redis 3.x users | Upgrade without losing data, with a clear guide to the code changes |

## Constraints

- **Support the Spark and Redis versions that are in active development today, and no others.** As of
  2026-11-21 that means Spark 4.1, 4.2 and 4.3 (with 4.4 at RC and the 4.5 LTS coming) and Redis 8.2
  through 8.10 (with 8.12 at RC). Spark now releases quarterly, so lines are added and dropped as upstream
  moves. The exact matrix and policy are in `spec.md` §1.
- **RESP3 only.** The connector speaks RESP3 and nothing else, so there is no RESP2 mode to build, test
  or support. Every supported Redis line has spoken RESP3 since Redis 6.0.
- **No legacy Redis patterns.** Redis keeps old commands working after it ships better replacements. Where
  a replacement exists, the connector uses only the replacement and offers no option to bring the old form
  back:
  - `SCAN` over `KEYS`
  - ACL users over `requirepass`
  - Streams over list and Pub/Sub queues
  - atomic command flags over two-step writes
  - native JSON over JSON stored in a string
  - Functions over Lua `EVAL`
  - one Redis database per connection over numbered logical databases (`SELECT`)

  The full list is in `spec.md` §2.14.
- Keep the **BSD-3-Clause** license. Redis 8's AGPLv3/RSAL/SSPL server licensing does not change the client
  licence, but this needs confirming with legal.
- Do not silently corrupt or orphan data written by 3.x: the `_spark:<table>:schema` keys and the binary
  model.
- Never log credentials (the current `maskPassword` only masks `auth`), and never put secrets in test
  fixtures.
- Changes are made by humans and agents working together, following the AI-native SDLC playbook. Agents may
  act up to the release gate, and a code owner approves every merge.

## Non-goals for 4.0

- A DStream API. Spark deprecated DStreams, so we will drop them and point users to Structured Streaming.
- The RDD API (`sc.fromRedis*` / `toRedis*`) and aliases for 3.x option names. 4.x is DataFrame and SQL only.
  An old option name fails with a message naming its replacement, and a migration guide maps every RDD
  function to the table model that replaces it. Data written by 3.x stays readable.
- Redis Stack 6.x/7.x module versions on servers that are past end of life.

## Decisions (2026-11-22)

| Question | Decision |
|---|---|
| Where the work happens | A development spike in the `Redislabs-Solution-Architects/spark-redis` fork. RedisLabs/spark-redis#394 is a reference only, not the starting point |
| Ownership | The fork's owner is the code owner and the release manager for the spike |
| Maven coordinates | The `com.redis` group, with one artifact per Spark minor, for example `com.redis:spark-redis-spark-4.2_2.13` |
| Client library | Jedis 8 |
| Release pipeline | GitHub Actions publishes from the fork to GitHub Packages and GitHub Releases. Maven Central and PyPI wait for an official release (spec §2.13) |
| Redis Software and Redis Cloud | Certify against the latest Redis Software release and the latest Redis Cloud database version only |
| Redis 7.2 and 7.4 | Out of scope, under the active-development rule |
| Default `model` | `json` |
| Keys on write | Every write has a deterministic key, so retries and replays never duplicate rows (spec §2.4) |
| Logical databases | Not supported. Namespaces are connection profiles only |
| New option names | Accepted as proposed in spec §2.11 |
| Spark 3.5 and `branch-3.x` | No bridge release during the spike. `branch-3.x` hosts only `spark-redis-migrate`, because it needs the 3.x classpath. Users who cannot move to Spark 4 yet stay on the existing 3.x releases. A bridge release can be reconsidered if the spike becomes the official line |
| Java package and format name | The `com.redis.sparkredis` package, so classes never clash with the Redis Connector for Spark (`com.redis.spark`). The format name stays `redis`. Install only one of the two connectors per cluster, or use our full class name (spec §2) |

## Open questions

None for the spike.
