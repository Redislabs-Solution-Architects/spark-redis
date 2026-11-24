# Phase 1 plan: Spark 4 build and CI

> Stage: **Build**, the `plan.md` for Phase 1 (AI-native SDLC playbook). Inputs:
> - [`roadmap.md`](../roadmap.md), Phase 1;
> - [`spec.md`](../spec.md): §1 supported versions, §2.1 client layer, §2.9 security, §2.11 migration,
>   §2.12 verification, §2.13 release engineering and §2.14 legacy patterns;
> - the signed [Phase 0 exit review](phase-0-exit-review.md).
>
> Status: **approved by the owner on 2026-11-23**, with the seven [decisions](#decisions-for-the-owner) accepted as
> recommended. **Amended the same day by roadmap Amendment 1**, approved with PR #47: Phase 1
> releases nothing, and the first 4.x release, 4.0.0-M1, is Phase 2's DataSource V2 core.
>
> - **What the amendment does to Phase 1's steps:**
>   - A3 and A4 are dropped;
>   - B3 moves to Phase 2;
>   - B4 builds the release workflow but publishes snapshots only.
> - **What stays as originally planned:** the goal and steps below, with each change marked where it applies.
>
> Work happens on `spike/phase-1` in the `Redislabs-Solution-Architects/spark-redis` fork. It is cut from
> `spike/phase-0` once this plan merges there; Phase 0 was signed off at `987aba1`.

## Goal

> **Amended (roadmap Amendment 1).** The goal is now: the codebase builds and is tested on Spark 4.1, 4.2 and 4.3,
> Scala 2.13 and JDK 17, with one artifact per Spark minor and the CI matrix, so that Phase 2 starts on the Spark 4
> platform.
> - **The 3.x code** comes along as the golden-layout baseline only. It changes only to compile and pass on each
>   Spark line, stays on Jedis 3.9, and is deleted in Phase 2e.
> - **Nothing is released.** 4.0.0-M1 is Phase 2's release.
>
> The original goal follows.

Run **today's feature set unchanged** on Spark 4.1, 4.2 and 4.3, Scala 2.13, JDK 17, Jedis 8 and RESP3, against
Redis 8.2 and later. Publish it as **4.0.0-M1**.

"Unchanged" means:
- the same API: the DataFrame source, the Structured Streaming source and the RDD API, which is now deprecated;
- the same options and the same defaults (`hash`, UUID keys);
- the same Redis data, byte for byte, as the golden fixtures check.

Only two user-visible things change, and both are required by the platform:
- **DStreams are removed** (roadmap Phase 1, item 5).
- **TLS verifies hostnames** by default, with `ssl.verify.hostname = false` as the escape hatch (spec §2.1).

**Out of scope for Phase 1**, because Phase 2 does them:
- DataSource V2;
- the `com.redis.sparkredis` package, and the `core`, `sql` and `it` modules;
- topology refresh;
- Sentinel and replica reads;
- the `json` model;
- renamed options;
- removing the RDD API.

## Where Phase 1 starts

Phase 0 and the upstream fixes already did part of the roadmap's Phase 1 scope. This is the state at `987aba1`:

| Roadmap Phase 1 item | State at `987aba1` | Step |
|---|---|---|
| 1. Start from the fork's code | `spike/phase-0`, which includes upstream `master` up to #434 | — |
| 2. Scala 2.13 and JDK 17 | Scala 2.13 builds and passes in CI next to 2.12, and `JavaConversions` is gone (upstream). Still JDK 8, 2.12 is the default, and `JavaConverters` is used in 12 files | A1 |
| 3. Spark 4.1 compile | Spark 3.5.10 | A1 |
| 4. Jedis 8, and the legacy commands (spec §2.14) | **Done in 3.x:** `HSET`, `SET … EX`, `SSCAN` (#434), `ZRANGE … BYSCORE` (#434), a value and its TTL in one `MULTI`/`EXEC` (#433), `UNLINK` in `write.mode = replace`, `CLIENT SETNAME`/`SETINFO`. **Still to do:** Jedis 3.9 → 8; `HELLO 3` (today `HELLO` is only a capability probe); TLS hostname verification; `LRANGE 0 -1` and `HGETALL`; `DEL` in truncate; `INFO` parsing; `CLUSTER SLOTS`; the hand-rolled `XINFO` decoder | Dropped (Amendment 1): Phase 2a |
| 5. Remove DStreams; deprecate the RDD API | Both present | A2 |
| 6. One artifact per Spark minor; the CI matrix | One artifact, `com.redislabs:spark-redis_2.12`. CI runs Redis 8.6 × JDK 8 × Scala {2.12, 2.13} on PRs, and Redis 8.2 after merge | B1, B2 |
| 7. `branch-3.x` | None. The schema migration (`SchemaMigration`, #429) exists in the 3.x code | C1–C3 |
| 8. Publish 4.0.0-M1 | The snapshot pipeline to GitHub Packages works (Phase 0 C9) | B4, snapshots only (Amendment 1) |

**What the port touches.** This comes from a read of the code at `987aba1`, checked against the Spark 4.1.4 and
Jedis 8.0.2 jars:

- **Spark internals in the Structured Streaming source** (`org/apache/spark/sql/redis/stream/`):
  - `sqlContext.internalCreateDataFrame` (`RedisSource.scala:115`). In 4.1 it exists only on
    `org.apache.spark.sql.classic.SQLContext` and `classic.SparkSession`.
  - `Source` and `Offset` are still in `org.apache.spark.sql.execution.streaming`.
  - `SerializedOffset` and `HDFSMetadataLog` are in both that package and a new subpackage (`runtime`,
    `checkpointing`), and `LongOffset` moved to `runtime`.
  - `CatalystTypeConverters` and `HDFSMetadataLog` are used but missing from
    `scripts/spark-internal-allowlist.txt`.
- **Spark classes that moved jars:** `GenericRow`, `GenericRowWithSchema` and the abstract `SQLContext` now live in
  `spark-sql-api`, under the same package names, so their imports still compile.
- **Jedis 8 API breaks:**
  - `Pipeline` has no `multi()`/`exec()`. Transactions use `Transaction(Connection)`, so the per-key transaction
    writer from #430 and #433 (`PipelineUtils.writeTransactionsWithRedirects`) needs a new transport.
  - `PipelineBase` is gone (`HashRedisPersistence`, `RedisSourceRelation`).
  - `BuilderFactory.STRING_ZSET` is gone, and `Keyword.raw` became `getRaw()` (`RedisRDD`).
  - `xinfoGroup` became `xinfoGroups` (`RedisSource`).
  - The `StreamEntry` classes moved package.
- **Hand-parsed replies:**
  - `RedisConfig.scala:324-459`: cluster detection from `INFO`'s `redis_version`, the replica walk from
    `INFO replication`, and raw `CLUSTER SLOTS` casts.
  - `ConnectionUtils.scala:32-79`: the `XINFO` decoder.
  - The raw `sendCommand` replies in `RedisCapabilities` and `BinaryRedisPersistence` (`MSETEX`), whose shape
    changes under RESP3.
- **TLS:** `ConnectionPool.scala:35` passes `null` for the SSL socket factory, the SSL parameters and the hostname
  verifier, so trust comes from JVM-wide `javax.net.ssl` settings and no hostname is checked.
- **Tests:**
  - 93 suites, which ran 590 tests at `987aba1`.
  - `env/Env.scala` holds a `StreamingContext` that every environment-based suite inherits, so removing
    `spark-streaming` touches the whole test tree.
  - The suites use ScalaTest 3.0.8 (`org.scalatest.Matchers`).

## Environment

| Where | Setup |
|---|---|
| Owner's Mac (arm64) | **Zulu 17** (17.0.21.1) at `/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home` builds and tests 4.x. Homebrew's `openjdk@17` (the same version, jenv's global default) and `openjdk@21` are also installed, the latter for local JDK 21 runs. **Zulu 8** stays for `branch-3.x`. As in Phase 0, always set `JAVA_HOME`, because `java` on `PATH` comes from jenv. Docker Desktop for Testcontainers; the Maven Wrapper |
| CI | GitHub Actions, `ubuntu-24.04`, Temurin 17, plus 21 on every Spark line (the connector supports JDK 17 and 21, spec §1). Docker on the runners. `branch-3.x` keeps Temurin 8 |
| Claude's sandbox | Reaches the Docker socket. It can't read `src/test/resources/tls/*.key`, so suites that start the TLS container run outside the sandbox. It can't write `.claude/hooks/` or `.claude/skills/`, so the owner writes or approves those edits (X1) |

## Branches, lanes and file ownership

| Lane | Branch | Owns these files | Starts |
|---|---|---|---|
| **X: guardrails for Phase 1** | `spike/phase-1-x` | `CLAUDE.md`, `REVIEW.md`, `.claude/`, `evals/`, `scripts/check-*.sh` | First, together with A1 |
| **A: the code port** | `spike/phase-1-a` | `src/main/`, `src/test/scala/`, `scripts/spark-internal-allowlist.txt`, `dev/`; plus `pom.xml`, `versions.yml` and `ci.yml` **during A1 only** | First. A1 lands before anything else in A and B |
| **B: build, CI and release** | `spike/phase-1-b` | `pom.xml` and the module poms (after A1), `versions.yml`, `.github/workflows/`, `Makefile`, `doc/modernization/benchmarks/`, the release docs in `README.md` | After A1 merges |
| **C: `branch-3.x`** | `branch-3.x`, with PRs from `branch-3.x-*` | Everything on `branch-3.x` | Right away. It doesn't depend on A or B |

Each lane merges into `spike/phase-1` (or, for lane C, into `branch-3.x`) through a PR in the fork, which the owner
approves. Three rules prevent conflicts:

- **After A1, the build belongs to lane B.** Lane A's later `pom.xml` edits are only dependency versions and
  declarations: removing `spark-streaming` in A2, and Jedis 8 in A3. Each goes in its own commit, so a merge
  conflict with lane B stays small.
- **Lane A never edits `.github/workflows/` after A1.**
- **`spike/phase-1` stops merging upstream `master` after A1** (decision [D5](#decisions-for-the-owner)). Upstream
  is 3.x, so its changes would conflict with the port. `branch-3.x` follows upstream instead (C3).

## Steps

Each step names the files it touches, what changes, and the **proof** that it's done. The proof is a command or
an artifact the owner can check.

Every step keeps three things unchanged:
- the golden write and read fixtures;
- the legacy fixtures;
- the test assertions.

**If a test fails, the code is fixed, not the test** (`CLAUDE.md` rule 5). The exceptions are the DStream tests
that A2 deletes ([D3](#decisions-for-the-owner)) and the two mechanical changes A1 makes to every suite. A
ScalaTest assertion that can't compile on the new versions is rewritten to check the same thing, and the PR lists
each one.

### Lane X: guardrails for Phase 1

**X1. `CLAUDE.md`, `REVIEW.md`, evals and checks for Phase 1**

- **Files:**
  - `CLAUDE.md`, `REVIEW.md`, `evals/README.md` and `evals/tasks/*.md`;
  - new: `.claude/skills/new-spark-line/SKILL.md`. Phase 0 C6 deferred it until there were Spark lines to add;
  - `.claude/hooks/after-edit.sh`, if it names JDK 8.

  The owner writes or approves the `.claude/` edits.
- **Change:**
  - **`CLAUDE.md`:**
    - "We are in **Phase 1**".
    - The verify command on JDK 17, with `-pl spark-<line>` once B1 lands.
    - Rule 1 becomes: "In Phase 1, `src/main` changes only to port today's features to Spark 4, Jedis 8 and RESP3,
      or to fix a bug. No new features or options except `ssl.verify.hostname`. The golden layouts pass
      unchanged."
    - The Phase 0 lessons become rules (exit review, open item 5):
      - wait for CI to finish before merging;
      - a failing-first test merges with its fix;
      - check that a PR is still open before pushing to its branch;
      - after merging upstream, look for tests that both sides added;
      - a new `-D` property goes in both `systemProperties` blocks of the pom.
  - **`REVIEW.md`:** a new pass, "Does this PR add behaviour that 3.x doesn't have?", with a finding for anything
    beyond the port.
  - **Evals:** the tasks' commands move to JDK 17. The 10 evals run once at `spike/phase-1` after A1, to record a
    Phase 1 baseline.
  - **`new-spark-line` skill:** the checklist for adding or dropping a Spark minor: the module, the overlay,
    `versions.yml`, the CI jobs, the README table and the release notes.
- **Proof:**
  - `CLAUDE.md` stays under one page.
  - The skill is linked from it.
  - The Phase 1 eval baseline is in `evals/README.md`.

### Lane A: the code port

Lane A is one session working in sequence, because the port touches every file (roadmap, parallelism rules).

**A1. Spark 4.1, Scala 2.13 and JDK 17, still on Jedis 3.9**

- **Files:**
  - `pom.xml`, `versions.yml` and `.github/workflows/ci.yml`, which only move CI to JDK 17 and Scala 2.13;
  - delete `dev/change-scala-version.sh`;
  - `src/main/**`, `src/test/scala/**` and `scripts/spark-internal-allowlist.txt`.
- **Change:**
  - **CI:** `ci.yml` runs on JDK 17 and Scala 2.13 only, and its push trigger adds `spike/phase-1`. Lane B
    reshapes the matrix in B2.
  - **Versions:**
    - `spark.version` 4.1.4 and Scala 2.13.17, the same as Spark 4.1.4. Scala 2.12 goes, and with it the
      artifactId suffix switch.
    - javac and scalac use `release 17`.
  - **Plugins:**
    - JaCoCo 0.8.5 → 0.8.16, because 0.8.5 can't read Java 17 class files.
    - `scala-maven-plugin` 3.4.6 → 4.10.0.
    - The other plugins only if the build needs it.
  - **The test JVM:** the scalatest `argLine` gets the `--add-opens` list that Spark 4 needs on JDK 17, the same
    one `spark-submit` adds through `JavaModuleOptions`.
  - **Spark 4 source changes:**
    - `RedisSource` casts to `org.apache.spark.sql.classic.SQLContext` for `internalCreateDataFrame`. DSv2 removes
      that in Phase 3.
    - Imports follow the moved classes (`LongOffset`, `SerializedOffset`, `HDFSMetadataLog`), preferring the
      new packages.
    - `CatalystTypeConverters` and `HDFSMetadataLog` go into the allowlist, which then matches the code.
  - **Scala 2.13 cleanup:**
    - `JavaConverters` → `scala.jdk.CollectionConverters`, and `TraversableOnce` → `IterableOnce`.
    - The 2.12 `distinctBy` shim goes.
  - **ANSI mode,** which Spark 4 turns on by default:
    - The connector parses values with JVM parsers (`ParseUtils`), not Spark casts, so its results shouldn't
      change.
    - Any test that behaves differently is recorded in a new `phase-1-findings.md`, and the code is fixed so that
      3.x results come back.
  - **The two mechanical test changes:**
    - **ScalaTest 3.0.8** stays. It works on any Scala 2.13 patch release, and moving to 3.2 would rewrite every
      suite's imports, which can wait for the Phase 2a `it` module. If 3.0.8 clashes with Spark 4's test
      classpath, A1 moves to 3.2 instead and lists the import changes.
    - **Testcontainers 1.21.4** stays too, since it runs on JDK 17.
- **Proof:**
  - `JAVA_HOME=<zulu-17> ./mvnw -B verify -Dredis.image=redis:8.6` passes, with every golden and legacy test
    passing unchanged.
  - CI passes on JDK 17.
  - The PR lists any test it had to rewrite, and why.

**A2. Remove DStreams, and deprecate the RDD API**

- **Files:**
  - delete `src/main/scala/com/redislabs/provider/redis/streaming/` (4 files) and the `spark-streaming`
    dependency;
  - `env/Env.scala` loses its `StreamingContext`;
  - delete the DStream tests ([D3](#decisions-for-the-owner)): the four suites
    `RedisXStream{Standalone,Cluster}Suite` and `RedisReceiverConnectionLeak{Standalone,Cluster}Suite`, and the
    traits they extend, `RedisXStreamSuite`, `RedisReceiverConnectionLeakSuite` and `SparkStreamingRedisSuite`;
  - `redisFunctions.scala`, `rdd/RedisRDD.scala` and the `package` object;
  - the DStream sections of `doc/`.
- **Change:**
  - **The DStreams go,** and with them `BLPOP`, the last list-as-queue pattern (spec §2.14).
  - **Every public RDD API member gets `@deprecated`,** with the message "The RDD API is removed in 4.0.0-M2; use
    format(\"redis\")" and since-version `4.0.0-M1`. That covers:
    - `RedisContext`'s 24 `fromRedis*`/`toRedis*` methods;
    - `RedisKeysRDD`'s `get*` methods;
    - the `toRedisContext` implicit;
    - the RDD classes.
  - **Behaviour stays the same.** The RDD suites suppress the warning with `@nowarn("cat=deprecation")`, so the
    build doesn't fill up with warnings.
- **Proof:**
  - `grep -rn 'org.apache.spark.streaming' src/` finds nothing.
  - `./mvnw verify` passes.
  - The suite count drops by exactly the four DStream suites, and the PR names them.

**A3. Jedis 8 and RESP3**

> **Dropped by roadmap Amendment 1.** Phase 2a's `RedisExecutor` brings Jedis 8, RESP3 only, TLS hostname
> verification and the protocol tests, so porting the 3.x client code first would be thrown away. The design notes
> below stay as input for Phase 2a.

- **Files:**
  - `pom.xml`, for Jedis 8.0.2;
  - `ConnectionPool.scala`, `RedisConfig.scala`, `util/PipelineUtils.scala`, `util/ConnectionUtils.scala`,
    `RedisCapabilities`, `rdd/RedisRDD.scala`, `redisFunctions.scala`;
  - `org/apache/spark/sql/redis/**`;
  - the tests that use the Jedis API directly.
- **Change:**
  - **Connections:**
    - Each endpoint gets one Jedis 8 client, built from a `DefaultJedisClientConfig` with the protocol set
      explicitly to **RESP3**, plus the user, password, client name, timeouts and TLS settings. That way every
      connection opens with `HELLO 3 AUTH <user> <password> SETNAME <name>`.
    - Code that needs one connection borrows a `Connection` from it: the redirect-aware writers, `ASKING`, and
      the scan loops.
    - The pool's size and its retry on "max clients" stay as they are; configurable sizing is Phase 2a.
    - `CLIENT SETINFO` stays.
  - **Failing fast on RESP3:** a server or user that refuses `HELLO 3` fails at connect, with an error that names
    RESP3. The connector never falls back to RESP2.
  - **The redirect-aware writers** (`writeWithRedirects`, `writeTransactionsWithRedirects`,
    `writeMultiKeyWithRedirects`) move to `Pipeline(Connection)` and `Transaction(Connection)`:
    - the same rounds;
    - `ASKING` before `MULTI`;
    - a queued `MOVED` or `ASK` sends the whole transaction again;
    - any other error fails the write.

    The tests from #424, #430 and #433 are the guard.
  - **API breaks:** `PipelineBase`, `BuilderFactory.STRING_ZSET`, `Keyword.raw`, `xinfoGroup` and the moved
    `StreamEntry` classes. The by-score read uses Jedis 8's `zrange(key, ZRangeParams.zrangeByScoreParams(…))`
    instead of a raw command.
  - **RESP3 replies:** the raw `sendCommand` replies in `RedisCapabilities` and `MSETEX` are read as RESP3 types.
    Where Jedis 8 has a typed method, the code uses it instead.
  - **TLS:**
    - Hostname verification is on by default.
    - A new option, `ssl.verify.hostname` (and `spark.redis.ssl.verify.hostname`), defaults to `true`. It's the
      escape hatch spec §2.1 documents.
    - Trust still comes from the JVM settings. Per-connection truststores are Phase 2d.
- **Tests:**
  - **Protocol tests (spec §2.12):**
    - During a write and a read, `CLIENT LIST` shows `resp=3` for every connection the connector opened.
    - Connecting to a stub server that answers `HELLO` with an error fails, with the RESP3 error.
  - **TLS tests:**
    - The TLS suites pass with verification on. The certificate has the SANs `localhost`, `127.0.0.1` and `::1`.
    - Connecting by a name the certificate lacks fails, and passes with `ssl.verify.hostname = false`.
- **Proof:**
  - `./mvnw verify` passes, including the redirect, transaction, TTL-atomicity, golden and legacy suites.
  - The new protocol and TLS tests pass.

**A4. The remaining legacy commands, and topology without `INFO`**

> **Dropped by roadmap Amendment 1.** The 3.x code that sends these commands is deleted in Phase 2e. Phase 2a's
> topology (`CLUSTER SHARDS`, `ROLE`) and the 4.x table models use only the modern commands from the start. The
> legacy-command check's `--all` mode moves to Phase 2e, once the 3.x code is gone.

- **Files:** `RedisConfig.scala`, `rdd/RedisRDD.scala`, `util/ConnectionUtils.scala`,
  `org/apache/spark/sql/redis/RedisSourceRelation.scala` and `stream/RedisSource.scala`, plus
  `scripts/check-legacy-redis.sh` (a lane X file; lane A changes it here because the tightening depends on this
  step).
- **Change, one row of spec §2.14 at a time:**
  - **Truncate:** `UNLINK` instead of `DEL` (`RedisSourceRelation`).
  - **Whole-collection reads:**
    - `fromRedisList` reads with `LRANGE` in chunks of `scan.count`.
    - `fromRedisHash` and schema inference read with `HSCAN`.
    - `fromRedisZSet` reads with `ZRANGE` by index in chunks, which keeps score order.
  - **Cluster detection and node roles:**
    - Cluster mode comes from the `mode` field of a `HELLO 3` reply, instead of from `INFO`'s `redis_version`.
    - Node roles come from `ROLE`, and the replica walk uses `ROLE`'s replica list instead of `INFO replication`.
  - **Topology** comes from `CLUSTER SHARDS` (`Jedis.clusterShards()` in Jedis 8). It is still read once on the driver,
    because the writers already follow redirects. Refreshing it on `MOVED`/`ASK` is Phase 2a.
  - **`XINFO`:** the hand-rolled decoder goes, replaced by Jedis 8's `xinfoStream` and `xinfoGroups`.
  - **The legacy-command check** gets an `--all` mode that checks whole files, not only added lines. Phase 0
    checked added lines only "because the 3.x code still contains these calls until Phase 1". CI runs `--all` on
    `src/main` from here on.
- **Tests:** `MonitoredCommands` checks what the reads, the truncate and the topology code send:
  - they send `LRANGE` in chunks, `HSCAN`, chunked `ZRANGE`, `UNLINK`, `ROLE` and `CLUSTER SHARDS`;
  - they never send `HGETALL`, `LRANGE 0 -1`, `DEL`, `INFO` or `CLUSTER SLOTS`.

  The read results come back unchanged, as the golden read fixtures check.
- **Proof:**
  - `scripts/check-legacy-redis.sh --all src/main` exits 0.
  - The new tests pass, and the golden read results are unchanged.

### Lane B: build, CI and release

**B1. One artifact per Spark minor** ([D2](#decisions-for-the-owner), [D6](#decisions-for-the-owner))

- **Files:**
  - `pom.xml` becomes the parent (`packaging pom`);
  - new: `spark-4.1/pom.xml`, `spark-4.2/pom.xml`, `spark-4.3/pom.xml`;
  - new: empty overlay directories `src/spark-4.1/scala`, `src/spark-4.2/scala` and `src/spark-4.3/scala`, each
    with a one-line README;
  - `Makefile`.
- **Change:**
  - **Each module builds the shared `src/main/scala` and `src/test/scala`,** plus its overlay (build-helper
    `add-source`). Only its Spark and Scala versions differ:
    - 4.1.4 on Scala 2.13.17;
    - 4.2.1 on 2.13.18;
    - 4.3.0 on 2.13.18.
  - **Coordinates:** `com.redis:spark-redis-spark-<line>_2.13:4.0.0-M1-SNAPSHOT`, as `intent.md` decided.
    `com.redislabs:spark-redis_2.12` stays the 3.x artifact.
  - **Package names don't change in M1.** As in 3.x, batch jobs use `format("org.apache.spark.sql.redis")`, and
    streaming jobs use `format("redis")`. Phase 2a moves the code.
  - **Shared configuration:** the parent's `pluginManagement` holds the plugins, the test properties
    (`redis.image`, `redis.containers`, `golden.update`) and the `argLine`.
  - **Fixtures:** the golden and legacy fixtures stay in `src/test/resources`, which every module reads.
  - **`Makefile`:** `make test SPARK=4.2` maps to `-pl spark-4.2`.
- **Proof:**
  - `./mvnw -B verify -pl spark-4.1`, `-pl spark-4.2` and `-pl spark-4.3` each pass.
  - `./mvnw -B install -DskipTests` produces three jars with the expected coordinates, and `dependency:tree` shows
    each module's Spark version.

**B2. `versions.yml` and the CI matrix** ([D1](#decisions-for-the-owner))

- **Files:** `versions.yml` and `.github/workflows/ci.yml`.
- **Change:**
  - **`versions.yml` stays the only list of lines.** It gains:
    - `spark: ["4.1", "4.2", "4.3"]`;
    - `spark_reference: "4.2"`, the line with the longest maintenance window (roadmap, Phase 1 risk);
    - `jdk: ["17"]`, and `jdk_secondary: ["21"]`. The connector supports JDK 17 and 21 only (spec §1, owner
      decision on 2026-11-23); JDK 25, planned here at first, is dropped;
    - the Redis lines from D1. For now that's Redis 8.6 only, with `redis_secondary` empty (owner decision,
      2026-11-23); the other supported lines return before 4.0.0-M1.

    The `scala` key goes.
  - **Pull requests run** the reference Spark line × the required Redis line × JDK 17.
  - **After each merge, and on manual dispatch,** CI runs:
    - every Spark line × every supported Redis line × JDK 17;
    - every Spark line on JDK 21, with the required Redis line.
  - **Each job** runs `./mvnw -B verify -pl spark-<line> -Dredis.image=redis:<redis>`, so every job covers
    standalone, cluster, TLS and ACL. The suites start all four.
- **Proof:**
  - A PR runs only the PR jobs.
  - A push to `spike/phase-1` runs the full matrix.
  - Eval task 02 (no duplicated line lists) still passes.

**B3. Benchmarks against a fresh baseline** ([D7](#decisions-for-the-owner))

> **Moved to Phase 2 by roadmap Amendment 1.** It compares the first 4.x release with a 3.x baseline re-measured on
> an idle machine, next to Phase 2's 2× write-throughput gate.

- **Files:** `doc/modernization/benchmarks/baseline-3.x.csv` and `.md` (re-measured), new `phase-1-m1.csv` and
  `.md`; the nightly benchmark job in `ci.yml`.
- **Change:**
  - **Why re-measure:** the Phase 0 baseline was taken on a busy machine, and many steps varied by 20–90% (exit
    review, criterion 4). So the 3.x baseline (`987aba1`, JDK 8) and M1 (`spark-4.2`, JDK 17) run back to back on
    the owner's Mac, idle, three times each.
  - **The setup:** the median of the three runs, Redis 8.2, standalone and cluster, the same suites as Phase 0.
  - **Comparing:** `phase-1-m1.md` compares each benchmark with the baseline.
  - **The nightly job** moves to JDK 17. Its regressions stay warnings.
- **Proof:** both CSVs are committed, and the comparison shows each benchmark within 10% of the baseline, or names
  the one that isn't.

**B4. Release 4.0.0-M1** ([D4](#decisions-for-the-owner))

> **Narrowed by roadmap Amendment 1.** Phase 1 builds `release.yml` and moves `publish-snapshot.yml` to JDK 17 and
> the three modules, and publishes snapshots only, with the SBOM. The `v4.0.0-M1` release, the smoke job and the
> README section come with Phase 2's first release. **Status:** built in Phase 1 (the B4 PR), as below.

- **Files:**
  - new: `.github/workflows/release.yml`;
  - `.github/workflows/publish-snapshot.yml`, moved to JDK 17 and to the three modules;
  - `.github/matrix.jq`, which gains the `all` matrix: CI's pull-request and full matrices together;
  - the parent pom and the three module poms, which gain the CycloneDX plugin 2.9.3 and a sources jar;
  - Phase 2: `README.md`, a "4.0.0-M1" section covering the coordinates, the GitHub Packages token, the Release
    assets, the removed DStreams, the deprecated RDD API and `ssl.verify.hostname`.
- **Change:** pushing a tag `v<version>` runs `release.yml`. GitHub dispatches a workflow only when the default
  branch has it, and the fork's `master` doesn't have this one, so a tag starts the release instead of
  `workflow_dispatch`. The workflow:
  1. runs every combination of the full matrix (`matrix.jq`'s `all`), and stops unless every one passes;
  2. sets the version in every pom;
  3. deploys the parent pom and the three artifacts to GitHub Packages, with each one's CycloneDX SBOM;
  4. creates the GitHub Release, with the jars, sources and javadoc jars, the jars-with-dependencies, the SBOMs,
     SHA-256 checksums, and GitHub's generated notes. Release Drafter runs only on pushes to `master`, the 3.x line.
     Following D4, the assets are GPG-signed when the repository has a signing-key secret. Without one, M1 ships
     with the SBOMs and checksums only, and signing starts with M2.

  A pull request that changes the workflow, a pom, `versions.yml` or `matrix.jq` runs it as a dry run: it tests,
  builds the release its snapshot is heading for, signs it with a throwaway key, and uploads it as a workflow
  artifact. It publishes nothing and reads no secrets.

  A smoke job then runs `spark-submit` on Spark 4.1, 4.2 and 4.3 with each released jar. It writes and reads a
  `hash` table and compares the result with the golden scenario.
- **Proof:**
  - The release exists, with its assets.
  - Each artifact resolves from GitHub Packages with a token.
  - The smoke job passes.

### Lane C: `branch-3.x`

**C1. Cut `branch-3.x`**

- **Files:** the new branch, cut from `spike/phase-0` at `987aba1`: Spark 3.5.10, Scala 2.12, JDK 8.
- **Change:**
  - **CI:** `ci.yml` and `guardrails.yml` run on pushes to the branch and on PRs into it. The branch's copy of
    `ci.yml` adds `branch-3.x` to its push trigger.
  - **Protection:** none, like `spike/phase-0` and `spike/phase-1`, which the owner leaves unprotected.
  - **README:** a note at the top says the branch exists only for `spark-redis-migrate`, and links to the 3.x
    releases for jobs that stay on Spark 3.
- **Proof:** CI passes on `branch-3.x`, all 590 tests.

**C2. Package `spark-redis-migrate`**

- **Files:** on `branch-3.x`: a `migrate` profile or module in the pom, and a README section.
- **Change:**
  - **The jar:** a shaded `com.redis:spark-redis-migrate_2.12:4.0.0-M1`, whose main class runs
    `SchemaMigration.main` (#429). It rewrites Java-serialized `_spark:<table>:schema` keys as JSON, with
    `--dry-run`.
  - **Scope:** schemas only, as the owner chose in Phase 0. Rewriting `binary` rows is Phase 2e.
- **Proof:**
  - `spark-submit --class … spark-redis-migrate_2.12-4.0.0-M1.jar --dry-run` against a Redis loaded with the
    legacy fixtures reports each schema key as "would migrate".
  - A real run turns each one into JSON that the M1 build reads.
  - `LegacyFixturesSuite` passes on the branch.

**C3. Follow upstream on `branch-3.x`**

- **Change:**
  - **Upstream to `branch-3.x`:** the branch merges upstream `master`, never rebases, whenever upstream merges a
    PR.
  - **3.x fixes found during the port** go upstream first, as `CLAUDE.md` says. They reach `branch-3.x` through
    the merge, and they are ported to `spike/phase-1` by hand.
  - **Recording:** each upstream merge into `branch-3.x` is recorded in the PR that brings it in.
- **Proof:** at the exit review, `branch-3.x` contains upstream `master`.

## Order

0. **The owner creates `spike/phase-1`** (done; left unprotected, the owner's choice) from `spike/phase-0` after this plan merges, as for
   `spike/phase-0`.
1. **X1 and A1.** Everything else builds on A1.
2. **In parallel, up to three sessions:**
   - **lane A:** A2. A3 and A4 are dropped (Amendment 1);
   - **lane B:** B1, then B2. B1 starts first, so lane A's later pom edits land on the new layout;
   - **lane C:** C1–C3, at any time.
3. **B4,** narrowed to the release workflow and snapshots (Amendment 1). B3 moves to Phase 2.
4. **The exit review.**

## Exit criteria

The owner signs off on each item:

- [ ] For each Spark line, `./mvnw verify` passes on the full matrix (D1) in one run, after a merge or on manual dispatch, with nothing
      quarantined.
- [ ] The golden write and read fixtures and the legacy fixtures pass unchanged on every line, in standalone and
      cluster.
- [ ] **Snapshots:** `publish-snapshot.yml` publishes snapshots of the three per-Spark artifacts, with the SBOM, and
      `release.yml`'s dry run passes (B4, narrowed).
- [ ] **No DStreams:** nothing in `src/` imports `org.apache.spark.streaming`.

**Moved by roadmap Amendment 1:**
- the benchmark comparison (B3), to Phase 2;
- publishing 4.0.0-M1, to Phase 2;
- the RESP3-only and legacy-command criteria, to Phase 2a and 2e.
- [ ] **`branch-3.x`:** CI passes, and `spark-redis-migrate --dry-run` runs against the legacy fixtures.
- [ ] **Guardrails:** `CLAUDE.md`, `REVIEW.md` and the evals are updated for Phase 1, and the eval pass rate is
      recorded.

## Decisions for the owner

**All seven accepted by the owner on 2026-11-23, as recommended.** The steps above follow them. After roadmap
Amendment 1:
- D4 (signing) and D7 (the benchmark baseline) apply to Phase 2's 4.0.0-M1;
- D6 (keep the package names in M1) no longer applies, because no release carries the 3.x packages.

D1, as built in B2:
- **JDKs:** the connector supports JDK 17 and 21 only (owner decision, 2026-11-23), so JDK 25 is out of the matrix.
- **JDK 21 runs on every Spark line,** not just the newest.
- **Redis 8.6 only, for now** (owner decision, 2026-11-23). The other supported Redis lines (8.2–8.10) return to
  `redis_secondary` before 4.0.0-M1. Until then, a merge runs 6 jobs: every Spark line on JDK 17 and 21, all on 8.6.
- **There's no nightly run.** GitHub runs schedules only from the default branch, which doesn't have `ci.yml`, so
  the full matrix runs after each merge and on manual dispatch.

| # | Question | Decision |
|---|---|---|
| D1 | **Which matrix runs when?** The roadmap's Phase 1 matrix is Spark {4.1, 4.2, 4.3} × Redis {8.2, 8.4, 8.6, 8.8, 8.10} on JDK 17, with JDK 21 and 25 on the newest Spark: 17 jobs. In Phase 0, #33 cut CI down to 8.6 on PRs and 8.2 after merge | **PRs:** Spark 4.2 × Redis 8.6 × JDK 17. **After each merge and nightly:** the full 17 jobs, allowed to fail during Phase 1. The exit needs one nightly run with all of them green. Redis 8.4 drops out at its EOL (2027-01-01) |
| D2 | **How to build one artifact per Spark minor:** Maven modules or profiles? | **Modules** (B1). Each has a constant artifactId, they build in one reactor, and CI selects one with `-pl`. A profile would need an expression in the artifactId, which Maven only resolves with the flatten plugin |
| D3 | **Delete the DStream tests together with DStreams?** `CLAUDE.md` rule 5 doesn't allow deleting tests on an agent's say-so | **Yes**, the four suites and three traits in A2, because the code they test is removed |
| D4 | **Sign the M1 release?** Spec §2.13 says releases are signed | Sign with a GPG key stored as a repository secret if you have one. Otherwise ship M1 with the SBOM and SHA-256 checksums, and sign from M2 |
| D5 | **Stop merging upstream into `spike/phase-1` after A1?** | **Yes.** Upstream is 3.x. Its changes reach `branch-3.x` by merge, and 3.x fixes are ported to 4.x by hand (C3) |
| D6 | **Keep the package names in M1?** | **Yes.** `com.redislabs.provider.redis` and `org.apache.spark.sql.redis` stay until Phase 2a, so M1 is today's code on new platforms. Only the Maven coordinates change |
| D7 | **Re-measure the 3.x baseline before comparing?** | **Yes** (B3), on an idle machine. Otherwise the 10% gate measures the noise |

## Risks

| Risk | Mitigation |
|---|---|
| Spark 4 needs `--add-opens` flags on JDK 17 that the forked test JVM doesn't get | A1 adds Spark's own list to the scalatest `argLine`. A missing flag shows up as an `IllegalAccessError` in the first suite |
| The Jedis 8 transport changes how redirects inside transactions are seen (`Transaction(Connection)` instead of a pipelined `MULTI`) | The redirect, transaction and TTL-atomicity suites from #424, #430 and #433 must pass unchanged. A3 keeps the round-by-round logic and changes only the transport |
| RESP3 changes reply types: maps, doubles, booleans | A3 reads typed replies through Jedis's builders, and the golden read fixtures check every read path. Raw `sendCommand` replies are replaced or parsed as RESP3 |
| TLS hostname verification fails in the tests | The test certificate has the SANs `localhost`, `127.0.0.1` and `::1` (Phase 0, open item 6) |
| The Spark internals that the V1 streaming source uses (`Source`, `HDFSMetadataLog`, `internalCreateDataFrame`) change again in 4.2 or 4.3 | Each module compiles its own line, and an overlay can hold a per-line shim. Phase 3 replaces the source with DSv2 |
| Binary-model rows read differently on Spark 4: `RestrictedDeserialization`'s allowlist names Spark 3.2–3.5 classes | The golden binary fixtures and `LegacyFixturesSuite` run on every line. Allowlist entries for Spark 4 come only with a test that reads a 3.x row |
| ANSI mode changes a result | The connector parses with JVM parsers rather than Spark casts. Any change goes in the findings doc and is fixed back to the 3.x result |
| The 17-job matrix is slow or flaky | PRs run one job (D1). The full matrix runs after each merge and on manual dispatch. A flaky test is fixed, never quarantined, without the owner |
| Spark 4.1 reaches EOL (about June 2027) before 4.0.0 GA | The `spark-4.1` module and its CI jobs are deleted. GA ships with a 4.2 floor (roadmap) |
| Users on the 3.x artifact pick up M1 by accident | M1 uses new coordinates (`com.redis:spark-redis-spark-<line>_2.13`), so no 3.x build resolves to it |

## Measurement

From the roadmap's "How we measure it", recorded in the Phase 1 exit review:

- time from approving this plan to the exit sign-off
- how much the plan is reworked during the phase
- first-pass merge rate of the Phase 1 PRs
- PRs merged before their CI finished. Phase 0 had nine: #11, #12, #31, #38 and #40 in the fork, and upstream #424, #430, #433 and #434. The target is none
- repeat mistakes that needed a `CLAUDE.md` line
- concurrent sessions in flight
- the eval pass rate, at the start of Phase 1 and at its end
- each benchmark against the re-measured baseline
- 3.x fixes found during the port and sent upstream
