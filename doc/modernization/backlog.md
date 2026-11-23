# Backlog triage

> Phase 0 plan, step C10. Every open issue in `RedisLabs/spark-redis` on 2026-11-22, from
> `gh issue list -R RedisLabs/spark-redis --state open --limit 200`. Each row names the phase that resolves the
> issue (from the roadmap's "Issues closed" lists and the spec) or "Declined" with the reason, and the issue it
> duplicates. Rows marked **Proposed** aren't in the roadmap's lists: an agent drafted them for the owner to confirm.
>
> Upstream is read-only during the spike: nothing here has been labelled, commented on or closed there. Issue numbers
> are plain text on purpose.

## Summary

| Resolved by | Issues |
|---|---|
| Phase 0 | 3 |
| Phase 1 | 11 |
| Phase 2 | 74 |
| Phase 3 | 9 |
| Phase 4 | 6 |
| Phase 5 | 6 |
| Declined | 8 |
| **Total** | **117** |

28 issues are marked as duplicates. 33 rows are proposals that the roadmap doesn't list yet.

Six rows (#33, #267, #347, #351, #358, #398) also note a fix that merged into the 3.x code on `spike/phase-0`
during Phase 0. Their phase is still the one that brings the behaviour to the 4.x connector.

## Issues

| Issue | Title | Resolved by | Duplicate of | Notes |
|---|---|---|---|---|
| #1 | Reference to a more complete java read-only class for clusters | Phase 2 | #76 | Proposed: replica reads (spec §2.1) |
| #24 | Running the spark cluster with yarn | Phase 2 |  | Proposed: cluster detection fails on an endpoint that refuses `CLUSTER`; topology from `ROLE`/`CLUSTER SHARDS` and proxy support (spec §2.1) |
| #26 | how to push spark streamint data into redis by spark-redis? | Phase 3 |  | Proposed: a streaming sink for every model; DStreams are removed (spec §2.5) |
| #28 | Is there a tuning guide when using spark-redis? | Phase 5 |  | Proposed: a tuning guide in the rebuilt docs site (roadmap Phase 5) |
| #33 | Fails silently when redis instance is full | Phase 2 |  | Proposed: write errors (OOM) must fail the job (spec §2.4, §2.12 fault injection). **3.x, Phase 0:** every writer now fails on an error reply (PRs #22, #29); not tested against a full instance |
| #36 | Support for HINCRBY, LTRIM and LRANGE | Phase 2 |  | Proposed: `LRANGE` and `LTRIM` through the `list` model (spec §2.2). The `HINCRBY` part is declined: counters aren't a table write (see #342) |
| #40 | data from redis all in one partition | Phase 2 |  | Proposed: list reads in bounded chunks across partitions (spec §2.2, §2.3) |
| #44 | Remove/Update elements of an existing list in Redis | Declined |  | Proposed: in-place list edits (`LSET`, `LREM`) aren't a table operation; the `list` model appends or replaces (spec §2.2) |
| #45 | The value of poolConfig.setMaxTotal is too small | Phase 2 |  | Proposed: pool sizing and lifecycle, one client per executor (spec §2.1) |
| #48 | Support HLL functions on Redis | Phase 4 |  | Proposed: an HLL (`PFADD`) accumulator next to the probabilistic ones (lane 4d). Needs a line in spec §2.2 |
| #49 | How to implement toRedisHASH(keyMaps: RDD[(String, Map[String, String])], ttl: Int = 0) | Phase 2 |  | Proposed: RDD API question; `toRedisHASHes` today, the `hash` model in 4.x (spec §2.11) |
| #51 | sc.toRedis throws NotSerializableException for the stateful streaming job | Phase 3 | #236 | Proposed: superseded; DStreams are removed, and the Structured Streaming sink has no `SparkContext` in its closure (spec §2.5) |
| #52 | Using fromRedisZSetWithScore in Zeppelin Fails | Declined |  | Proposed: environment problem on a 2017 release (spark-redis 0.3.2, Spark 2.1, Zeppelin); the RDD API is removed in 4.x |
| #54 | Ability to make 'The keys RDD' from any RDD[String] | Phase 2 | #180 | Proposed: read by an explicit key list, `_key IN (…)` (spec §2.3) |
| #56 | How to use spark to access Redis which running on sentinel mode? | Phase 2 | #207 | Proposed: Sentinel (spec §2.1) |
| #68 | How to load 4000 images into redis using spark? | Phase 2 |  | Proposed: writing binary data such as images (spec §2.4) |
| #69 | Why loading 4000 images into redis using spark-submit takes time (9 Minutes) longer than loading the same images into HBase (2.5 Minutes)? | Phase 2 | #68 | Proposed: write throughput for binary data (spec §2.4) |
| #76 | consider supporting a read from Redis slave nodes | Phase 2 |  | Replica reads, `read.from` (spec §2.1) |
| #85 | Add tests for streaming functionality | Phase 3 |  | Structured Streaming on DSv2 (spec §2.5) |
| #86 | Reading total records in Redis cache is very slow | Phase 2 |  | Key scan with `SCAN … TYPE`, slot-grouped `MGET` (spec §2.3) |
| #90 | bug: ConnectionPool is not threadsafe | Phase 2 |  | Connection pools (spec §2.1) |
| #98 | Read timeout for getHash function | Phase 2 | #300 | Proposed: read timeouts on large reads (spec §2.3) |
| #120 | Add fromRedisByteHash Method | Phase 2 |  | Raw binary columns (spec §2.4) |
| #127 | Does spark-redis support sentinel mode in python? | Phase 2 | #207 | Sentinel (spec §2.1) |
| #131 | Dataframe write command very slow when database is already populated | Phase 2 |  | Slot-clustered write distribution (spec §2.4) |
| #137 | does spark-redis support in CDH? | Phase 2 | #213 | Proposed: settings silently ignored (`redis.host`, `spark.redis.*` as a source option); one `url` option (spec §2.9) |
| #138 | Ability to specify partition count for inserts | Phase 2 |  | Slot-clustered write distribution (spec §2.4) |
| #153 | Sparkcontext with redis sentinel mode | Phase 2 | #207 | Sentinel (spec §2.1) |
| #161 | test RedisRDD.scaleHostsWithPartitionNum() method | Phase 2 |  | Proposed: superseded; Phase 2 replaces this partitioning logic (spec §2.3) |
| #178 | [Structured Steaming] Limit the number of processed items per trigger (batch) | Phase 3 |  | Structured Streaming on DSv2 (spec §2.5) |
| #180 | spark read few keys from hash | Phase 2 |  | Point lookups and runtime filtering (spec §2.3) |
| #185 | Support GEO on Redis | Phase 4 |  | GEO sub-mode of `zset` (spec §2.2, lane 4d) |
| #187 | "Keys" command is super slow | Phase 2 |  | Key scan with `SCAN … TYPE`, slot-grouped `MGET` (spec §2.3) |
| #188 | Fix build with openjdk 11 | Phase 1 |  | JDK 17 build (roadmap Phase 1) |
| #190 | Spark-Redis XADD API written to Redis is slow and data is missing | Phase 3 |  | Structured Streaming on DSv2 (spec §2.5) |
| #197 | How to ignore exception during save | Phase 2 |  | Loud failures; no silent partial writes (spec §2.4) |
| #201 | Seeing an issue with Spark-Redis not calling shutdown hook after the job completes. | Phase 2 |  | Proposed: pool sizing and lifecycle, one client per executor (spec §2.1) |
| #202 | spark-redis read/write data set very slow after writing 1 million entries | Phase 2 |  | Key scan with `SCAN … TYPE`, slot-grouped `MGET` (spec §2.3) |
| #204 | Spark redis does not support SETNX | Phase 4 | #260 | `write.condition = nx` (spec §2.4, lane 4e) |
| #205 | How to write a byte array column to Redis? | Phase 2 |  | Raw binary columns (spec §2.4) |
| #207 | Sentinel support | Phase 2 |  | Sentinel (spec §2.1) |
| #211 | how to get List name of listRDD? | Phase 2 |  | RDD API question, answered by the table model that replaces the function (spec §2.11) |
| #213 | Jedis not honoring "spark.redis.host" configured in SparkSession builder and uses "localhost" | Phase 2 |  | Configuration and security (spec §2.9) |
| #214 | Loosing key context when using 'fromRedisHash' | Phase 2 |  | RDD API question, answered by the table model that replaces the function (spec §2.11) |
| #224 | Partition number control of streaming and structured streaming | Phase 3 |  | Structured Streaming on DSv2 (spec §2.5) |
| #233 | how to read/write from/to codis( redis-cluster ) ? | Declined |  | Proposed: Codis is a third-party proxy; supported endpoints are standalone, Sentinel, OSS Cluster and the Redis Software or Cloud proxy (spec §2.1) |
| #236 | sparkStreaming checkpoint error | Phase 3 |  | Superseded: DStreams are removed; Structured Streaming replaces them (roadmap Phase 3) |
| #237 | JedisConnectionException: Could not get a resource from the pool | Phase 2 |  | Connection pools (spec §2.1) |
| #240 | Connect to redis cluster enabled cache | Declined |  | Proposed: usage question; Redis Cluster is supported today (`doc/cluster.md`) |
| #249 | Setting options 'table' | Phase 2 |  | Configuration and security (spec §2.9) |
| #253 | Spark 3.0 support | Phase 1 | #373 | Spark 4 support (roadmap Phase 1) |
| #260 | Conditional update | Phase 4 |  | Conditional writes and row-level operations (spec §2.4, lane 4e) |
| #261 | Reading redis sets as Key Value Pair RDD | Phase 2 |  | RDD API question, answered by the table model that replaces the function (spec §2.11) |
| #262 | New API Request: Write certain column of DataFrame as Strings in Redis | Phase 2 |  | Proposed: the `string` model writes `_key`, `value` (spec §2.2) |
| #264 | Use mget instead of pipeline get to read kv from redis | Phase 2 |  | Key scan with `SCAN … TYPE`, slot-grouped `MGET` (spec §2.3) |
| #267 | Can we get an error if an RDD insert fails ? | Phase 2 |  | Loud failures; no silent partial writes (spec §2.4). **3.x, Phase 0:** the RDD writers fail on an error reply (PRs #22, #29) |
| #268 | How Can I tunning the config in changing table? | Declined |  | Proposed: re-reading a changing table is a Spark caching choice; for a change feed, use a stream (spec §2.5) |
| #270 | Need help with redis sentinel configuration | Phase 2 | #207 | Sentinel (spec §2.1) |
| #271 | Dataframe write for a wide table with millions of records is much slower than expected | Phase 2 |  | Slot-clustered write distribution (spec §2.4) |
| #278 | is there a Pyspark example for writing rdd to redis, i'm getting error while importing the import com.redislabs.provider.redis._ | Phase 5 | #379 | Python and Spark Connect (spec §2.7) |
| #280 | ERR data connection don't process admin command. | Phase 2 |  | Proposed: `INFO` refused by a proxy during cluster detection; the connector stops parsing `INFO` (spec §2.1). Same cause as #24 |
| #281 | Executing graph query on redis server | Declined |  | RedisGraph is end-of-life (spec §4) |
| #283 | Spark Dataset Write is too Slow - Java | Phase 2 | #271 | Slot-clustered write distribution (spec §2.4) |
| #284 | Migrate tests from Travis CI to another platform. | Phase 0 |  | Proposed: CI moves to GitHub Actions (`ci.yml`, plan step A4) |
| #285 | DataFrame write to redis, set to overwrite might cause error | Phase 2 |  | Write semantics: `write.mode`, overwrite strategies (spec §2.4) |
| #293 | Clean JavaDoc warnings | Phase 1 |  | Proposed: doc warnings go with the Scala 2.13 build (roadmap Phase 1) |
| #294 | Cannot build form source with AdoptOpenJDK 11. | Phase 1 | #188 | JDK 17 build (roadmap Phase 1) |
| #295 | Pyspark error loading data into Redis - java.lang.NoSuchMethodError: scala.Predef$.refArrayOps([Ljava/lang/Object;)Lscala/collection/mutable/ArrayOps; | Phase 1 |  | Scala version mismatch; per-Spark-minor artifacts (roadmap Phase 1) |
| #296 | Does spark-redis support hash value compression on read/write ? | Declined |  | Proposed: values are stored uncompressed for compatibility; compress in Spark before writing if needed |
| #297 | Reading key value pairs from redis without providing a keys.pattern or table name | Phase 2 |  | Point lookups and runtime filtering (spec §2.3) |
| #298 | More example with pyspark streaming | Phase 3 |  | Structured Streaming on DSv2 (spec §2.5) |
| #300 | Read timeout while reading data from redis in batch | Phase 2 |  | Partitions from `CLUSTER SLOT-STATS` (spec §2.3) |
| #302 | mvn clean package -DskipTests error | Phase 1 |  | Build on the Maven Wrapper and current toolchain (roadmap Phase 1) |
| #303 | How to load faster from redis to spark? | Phase 2 |  | Key scan with `SCAN … TYPE`, slot-grouped `MGET` (spec §2.3) |
| #304 | Writing Byte Array to Hash | Phase 2 | #205 | Raw binary columns (spec §2.4) |
| #305 | SSLSocketFactory sslSocketFactory, SSLParameters sslParameters JedisPool constructor support | Phase 2 |  | Configuration and security (spec §2.9) |
| #308 | Does spark redis provide read stream on redis 4.0 | Phase 3 |  | Structured Streaming on DSv2 (spec §2.5) |
| #313 | Failed connecting to host localhost:6379 | Phase 2 | #213 | Proposed: settings silently ignored (`redis.host`, `spark.redis.*` as a source option); one `url` option (spec §2.9) |
| #319 | Getting several list names and list values | Phase 2 |  | RDD API question, answered by the table model that replaces the function (spec §2.11) |
| #320 | Upload Byte Array or non UTF-8 encoding strings to Key Value pairs | Phase 2 |  | Raw binary columns (spec §2.4) |
| #323 | Provide Method to Delete Keys from Redis based on Key Pattern | Phase 4 |  | Conditional writes and row-level operations (spec §2.4, lane 4e) |
| #324 | string type in python | Phase 5 |  | Python and Spark Connect (spec §2.7) |
| #325 | Consistently getting `JedisConnectionException` | Phase 2 |  | Connection pools (spec §2.1) |
| #329 | 什么时候支持 Sentinel support  模式 | Phase 2 | #207 | Sentinel (spec §2.1) |
| #332 | Feature Request: improve table deletion speed | Phase 2 |  | Write semantics: `write.mode`, overwrite strategies (spec §2.4) |
| #340 | How to flush table using spark-redis? | Phase 4 | #323 | `DELETE FROM` / overwrite (spec §2.4, lane 4e) |
| #341 | redis.clients.jedis.exceptions.JedisConnectionException: Could not get a resource from the pool | Phase 2 | #237 | Connection pools (spec §2.1) |
| #342 | Need to use custom command for save | Declined |  | custom-command passthrough is out of scope until row-level operations ship; revisit then (spec §4) |
| #347 | Not all the data from DataFrame got write to Redis | Phase 2 |  | Loud failures; no silent partial writes (spec §2.4). **3.x, Phase 0:** writes follow `MOVED` and `ASK` (PRs #19, #22, #29), except `write.mode = replace` and binary rows with a TTL, which fail instead |
| #348 | RedisJSON support | Phase 2 |  | The `json` model (spec §2.2) |
| #349 | add support to spark.redis.url | Phase 2 |  | Configuration and security (spec §2.9) |
| #350 | pyspark.sql.utils.IllegalArgumentException when using infer.schema | Phase 2 |  | Types, parse errors and schema inference (spec §2.8) |
| #351 | Append mode doesn't replace entire row if key collision happens | Phase 2 |  | Write semantics: `write.mode`, overwrite strategies (spec §2.4). **3.x, Phase 0:** `write.mode = replace` for the `hash` model (PR #23) |
| #352 | How to use Spark redis RDD with PySpark | Phase 5 | #379 | Python and Spark Connect (spec §2.7) |
| #357 | Cannot use in Databricks JedisConnectionException: Could not get a resource from the pool | Phase 2 | #237 | Connection pools (spec §2.1) |
| #358 | Date & Timestamp parsing fails | Phase 2 |  | Types, parse errors and schema inference (spec §2.8). **3.x, Phase 0:** the error names the key, field, value and type (PR #20) |
| #359 | Data missing after reload | Phase 2 |  | Loud failures; no silent partial writes (spec §2.4) |
| #360 | toRedisByteLISTs is not over writing the existing key in redis | Phase 2 |  | RDD API question, answered by the table model that replaces the function (spec §2.11) |
| #362 | NOAUTH Authentication required PYSPARK/ SPARK | Phase 5 |  | Python and Spark Connect (spec §2.7) |
| #366 | Read getting stuck at stage 0 | Phase 2 |  | Partitions from `CLUSTER SLOT-STATS` (spec §2.3) |
| #368 | support spark 3.3 | Phase 1 | #373 | Spark 4 support (roadmap Phase 1) |
| #373 | Upgrade spark-redis for newer version | Phase 1 |  | Spark 4 support (roadmap Phase 1) |
| #375 | Some columns are not written to Redis | Phase 2 |  | Loud failures; no silent partial writes (spec §2.4) |
| #376 | NullPointerException  save to redis cluster | Phase 2 |  | Proposed: NPE in Spark's option redaction from a null option value; option handling and redaction (spec §2.9) |
| #377 | mvn clean package -DskipTests ERROR | Phase 1 | #302 | Build on the Maven Wrapper and current toolchain (roadmap Phase 1) |
| #379 | Python support for RDD | Phase 5 |  | Python and Spark Connect (spec §2.7) |
| #381 | WRONGPASS invalid username-password pair or user is disabled. | Phase 2 |  | Proposed: empty `user`/`auth` sent as credentials; ACL users with `user` defaulting to `default` (spec §2.9) |
| #382 | redis.clients.jedis.exceptions.JedisConnectionException: Could not get a resource from the pool | Phase 2 | #237 | Connection pools (spec §2.1) |
| #384 | Question: Is this repo still active? | Phase 0 |  | Answered by naming a code owner and release manager (intent Q4, spec §3) |
| #385 | Seeking for usage clarity on3 dataframe options | Phase 2 |  | Proposed: these options are renamed or removed, with replacements in the migration guide (spec §2.11) |
| #386 | Add support for Spark 3.4.x | Phase 1 | #373 | Spark 4 support (roadmap Phase 1) |
| #389 | Overwrite deletes table and then adds new values en block | Phase 2 |  | Write semantics: `write.mode`, overwrite strategies (spec §2.4) |
| #390 | Add support for Spark 3.5.x | Phase 1 | #373 | Spark 4 support (roadmap Phase 1) |
| #393 | Will this repo be maintained anymore? | Phase 0 | #384 | Answered by naming a code owner and release manager (intent Q4, spec §3) |
| #396 | Adding function toRedisByteZSET | Phase 2 |  | RDD API question, answered by the table model that replaces the function (spec §2.11) |
| #397 | Read keys from List | Phase 2 |  | RDD API question, answered by the table model that replaces the function (spec §2.11) |
| #398 | i cannot define clientName in configuration | Phase 2 |  | Configuration and security (spec §2.9). **3.x, Phase 0:** `spark.redis.client.name` and the `client.name` option (PR #21) |

## Sign-off

- [ ] I have confirmed every row: the phase, the decline reasons and the duplicates.
  Owner: ____________________  Date: ____________
