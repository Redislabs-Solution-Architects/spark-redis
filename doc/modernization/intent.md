# Intent: spark-redis for Spark 4 and Redis 8

> Stage: **Plan** (AI-native SDLC playbook). Status: **draft, awaiting product-owner review**.
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
| Existing spark-redis 3.x users | Upgrade without losing data or rewriting every job |

## Constraints

- **Support the Spark and Redis versions that are in active development today, and no others.** As of
  2026-11-21 that means Spark 4.1, 4.2 and 4.3 (with 4.4 at RC and the 4.5 LTS coming) and Redis 8.2
  through 8.10 (with 8.12 at RC). Spark now releases quarterly, so lines are added and dropped as upstream
  moves. The exact matrix and policy are in `spec.md` §1.
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
- Full parity for the RDD API across every data type. We keep a thin RDD layer for compatibility only.
- Redis Stack 6.x/7.x module versions on servers that are past end of life.

## Open questions for the product owner

1. **Coordinates:** keep the `com.redislabs` group, or move to `com.redis`? The spec proposes one artifact
   per Spark minor, for example `com.redis:spark-redis-spark-4.2_2.13`. This affects the migration story and
   who owns the Maven Central namespace.
2. **Client library:** Jedis 8 or Lettuce 7? `spec.md` recommends Jedis 8 behind an internal interface.
3. **Spark 3.5:** Spark 3.5 is in extended LTS, which means security fixes only, until November 2027.
   `roadmap.md` proposes one *bridge* release on `branch-3.x` (Spark 3.5, Jedis 8, CVE fixes and the
   migration tool) and then security fixes only. Is that enough, given that large platforms such as
   Databricks LTS runtimes still ship Spark 3.5?
4. **Ownership:** who are the code owners and release manager after this lands? The playbook needs named
   humans at the review and release gates.
5. **Redis Software and Cloud:** which versions do we certify against, given that they lag Redis Open
   Source? The proposal is databases at 8.2 or later on Redis Software 8.2 and on Redis Cloud.
6. **Redis 7.2 and 7.4:** both are still patched, with security fixes only, until 2029, and Redis Cloud still
   offers them. Under the "active development" rule they are out of scope. Do we accept that, or keep
   best-effort, untested support for the `hash`, `string` and `stream` models?
7. **Building on existing work:** [RedisLabs/spark-redis#394](https://github.com/RedisLabs/spark-redis/pull/394)
   ("Prepare for Spark 4") already moves the build to Spark 4, Scala 2.13, JDK 17 and `CLUSTER SHARDS`. We
   would like Phase 1 to build on it, with credit to its author. Would its author like to continue that
   work with us?
