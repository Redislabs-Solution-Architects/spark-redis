# spark-redis: agent guide

The Spark ↔ Redis connector, being modernized from 3.x (Spark 3.2, Scala 2.12, Jedis 3.9) to 4.x (Spark 4.1+,
Redis 8.2+). We are in **Phase 0**: make the 3.x code safe to change before anything is ported.

## Build and test

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./mvnw -B verify -Dredis.image=redis:8.2
```

- **JDK 8 only** for 3.x. A newer default `java` will not work, so always set `JAVA_HOME`.
- **Docker must be running.** Testcontainers starts the Redis servers each test JVM needs, on free ports, so runs can
  overlap. No local `redis-server` (`-Dredis.containers=false` uses external servers on the old fixed ports).
- **One suite:** add `-Dsuites=com.redislabs.provider.redis.df.standalone.HashDataframeStandaloneSuite`.
- **Other Redis lines:** `-Dredis.image=redis:8.6`, `redis:8.10`. To run several, use the `verifier` agent.
- **Shorthand:** `make test REDIS_IMAGE=redis:8.6 SUITES=<fqcn>,…` runs the same command (`JAVA_HOME` still applies).
- `verify` is the gate. It exits non-zero on any failure, and CI runs the same command.

## Layout

| Path | What it is |
|---|---|
| `src/main/scala/com/redislabs/provider/redis/` | RDD API (`redisFunctions.scala`, `rdd/`), config and pools, partitioner, DStreams (`streaming/`) |
| `src/main/scala/org/apache/spark/sql/redis/` | DataFrame source (DSv1, `hash` and `binary` models) and the Structured Streaming source (`stream/`) |
| `src/test/scala/**/env/` | Testcontainers harness. Suites are split by topology: `standalone/`, `cluster/`, `acl/` |
| `src/test/scala/**/golden/`, `src/test/resources/golden/` | Golden-layout tests and fixtures: the exact Redis data 3.x writes and reads |
| `src/test/resources/{tls,legacy,containers}/`, `quarantine.txt` | Test certificates, Java-serialized 3.x blobs, container configs, tests skipped on Redis 8 |
| `scripts/check-*.sh` | Guardrail checks; CI runs them in `.github/workflows/guardrails.yml` |
| `.claude/` | Hooks, the `verifier` agent, skills. `evals/` holds the agent eval tasks |

## Rules

1. **`src/main` does not change in Phase 0.** Phase 0 changes only tests, the build, CI, docs and agent config.
2. **New code goes in `com.redis.sparkredis`** from Phase 2. **Nothing new under `org.apache.spark.*`**, and no new
   imports from `org.apache.spark.sql.catalyst` or `.execution` outside `scripts/spark-internal-allowlist.txt`.
3. **No legacy Redis commands** (spec §2.14): no `KEYS`, `SMEMBERS`, `HMSET`, `SETEX`, `SETNX`, `GETSET`, `EVAL`,
   `EVALSHA`, `ZRANGEBYSCORE`, `BLPOP`/`BRPOP`, `SUBSCRIBE`, and never a value and its TTL in separate steps. Use
   the `SCAN` family, `HSET`, `SET … EX`, `ZRANGE … BYSCORE`, `MULTI`/`EXEC` on one key, and Streams.
4. **Gate on capabilities, not versions.** Probe with `COMMAND INFO` / `MODULE LIST`, keep a fallback, test both.
5. **If a test fails, fix the code, not the test.** Never weaken an assertion, skip a test or widen a tolerance to
   get green. A bug's test is committed failing before its fix and listed in `.claude/failing-first.txt`.
6. **Golden fixtures and quarantine entries change only with the owner's approval.** Agents never edit
   `src/test/resources/{golden,legacy,tls}/` or `quarantine.txt`, and never run `-Dgolden.update=true`. A hook
   blocks it; propose the change and stop. See the `golden-layout` skill.
7. **Format only what you edit.** A hook runs scalafmt on each Scala file you change. Never reformat other files.
8. **Credentials:** the test passwords (`passwd`, `p1pp0`) are the only ones allowed in the repo. Never log or
   print a password, token or credentialed URL.
9. **Never hard-code a Redis port in a test.** Ports are chosen at run time: use `redisPort` from the env traits, or
   `RedisContainers.standalonePort`, `tlsPort` and `clusterPort`.

## Working on this repo

- **PRs** go to the `spike/phase-0` branch of `Redislabs-Solution-Architects/spark-redis`, and the owner approves
  every merge. Each PR cites the plan step or spec section it implements and lists its proof.
- **Upstream (`RedisLabs/spark-redis`) is read-only:** no comments or labels, and don't link its issues from PR
  bodies. Inside the repo, write issue numbers as plain `#123`.
- **Review rubric:** [`REVIEW.md`](REVIEW.md). **Skills:** [`golden-layout`](.claude/skills/golden-layout/SKILL.md),
  and drafts [`redis-capabilities`](.claude/skills/redis-capabilities/SKILL.md) and
  [`dsv2-connector`](.claude/skills/dsv2-connector/SKILL.md).
- **Docs:** [intent](doc/modernization/intent.md), [spec](doc/modernization/spec.md),
  [roadmap](doc/modernization/roadmap.md), [Phase 0 plan](doc/modernization/plans/phase-0.md),
  [backlog](doc/modernization/backlog.md).
