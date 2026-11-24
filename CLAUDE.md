# spark-redis: agent guide

The Spark ↔ Redis connector, being modernized from 3.x (Spark 3.5, Scala 2.12, Jedis 3.9) to 4.x (Spark 4.1+,
Redis 8.2+). We are in **Phase 1**: today's features, unchanged, on Spark 4, Scala 2.13, JDK 17, Jedis 8 and RESP3
([plan](doc/modernization/plans/phase-1.md)).

## Build and test

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./mvnw -B verify -Dredis.image=redis:$(scripts/redis-lines.sh required | head -n 1)
```

- **JDK 17** for `spike/phase-1`. `branch-3.x` and upstream 3.x PRs build on JDK 8
  (`/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home`). Always set `JAVA_HOME`: `java` on `PATH` is a jenv
  shim, and Maven and the test JVM both run on `JAVA_HOME`.
- **Docker must be running.** Testcontainers starts the Redis servers each test JVM needs, on free ports, so runs can
  overlap. No local `redis-server` (`-Dredis.containers=false` uses external servers on the old fixed ports).
- **One artifact per Spark minor:** `spark-4.<n>/` builds the shared `src/` against that Spark line, plus its overlay
  `src/spark-4.<n>/scala` (skill `new-spark-line`). The command above builds and tests every line, one after the other;
  add `-pl spark-4.2` (the reference line) for one. The test JVM runs in the repository root.
- **One suite:** add `-Dsuites=com.redislabs.provider.redis.df.standalone.HashDataframeStandaloneSuite`.
- **Redis lines:** `versions.yml` is the only list of them, so don't copy that list into docs, commands or agents.
  `scripts/redis-lines.sh required` prints the lines every PR must pass, and the command above runs the first.
  `supported` adds the other supported lines, which CI runs after each merge, allowed to fail. To run
  several, use the `verifier` agent. Without `-Dredis.image`, the tests use `redis:8.2`, the floor.
- **Shorthand:** `make test SUITES=<fqcn>,…` runs the same command (`JAVA_HOME` still applies).
  `REDIS_IMAGE=redis:<line>` picks another Redis line, and `SPARK=4.2` one Spark line.
- `verify` is the gate. It exits non-zero on any failure, and CI runs the same command.

## Layout

| Path | What it is |
|---|---|
| `src/main/scala/com/redislabs/provider/redis/` | RDD API (`redisFunctions.scala`, `rdd/`; deprecated), config and pools, partitioner |
| `src/main/scala/org/apache/spark/sql/redis/` | DataFrame source (DSv1, `hash` and `binary` models) and the Structured Streaming source (`stream/`) |
| `src/test/scala/**/env/` | Testcontainers harness. Suites are split by topology: `standalone/`, `cluster/`, `acl/` |
| `src/test/scala/**/golden/`, `src/test/resources/golden/` | Golden-layout tests and fixtures: the exact Redis data 3.x writes and reads |
| `src/test/resources/{tls,legacy,containers}/`, `quarantine.txt` | Test certificates, Java-serialized 3.x blobs, container configs, tests skipped on Redis 8 |
| `scripts/check-*.sh` | Guardrail checks; CI runs them in `.github/workflows/guardrails.yml` |
| `.claude/` | Hooks, the `verifier` agent, skills. `evals/` holds the agent eval tasks |

## Rules

1. **In Phase 1, the 3.x code in `src/main` changes only to compile and pass on each Spark line, or to fix a bug.**
   It's the golden-layout baseline, it stays on Jedis 3.9, and Phase 2e deletes it, so no ports, features or options
   go into it (roadmap Amendment 1). A bug fix comes with its failing-first test (rule 5). The golden layouts pass
   unchanged. 4.x follows the spec and Spark 4 practice. Upstream issues and PRs were written for 3.x, so they
   aren't requirements.
2. **New code goes in `com.redis.sparkredis`** from Phase 2. **Nothing new under `org.apache.spark.*`**, and no new
   imports from `org.apache.spark.sql.catalyst` or `.execution` outside `scripts/spark-internal-allowlist.txt`.
3. **No legacy Redis commands** (spec §2.14): no `KEYS`, `SMEMBERS`, `HMSET`, `SETEX`, `SETNX`, `GETSET`, `EVAL`,
   `EVALSHA`, `ZRANGEBYSCORE`, `BLPOP`/`BRPOP`, `SUBSCRIBE`, and never a value and its TTL in separate steps. Use
   the `SCAN` family, `HSET`, `SET … EX`, `ZRANGE … BYSCORE`, `MULTI`/`EXEC` on one key, and Streams.
4. **Gate on capabilities, not versions.** Probe with `COMMAND INFO` / `MODULE LIST`, keep a fallback, test both.
5. **If a test fails, fix the code, not the test.** Never weaken an assertion, skip a test or widen a tolerance to
   get green. A bug's test is committed failing before its fix and listed in `.claude/failing-first.txt`. The PR
   with the fix removes that line and leaves the test unchanged; CI checks both. Never merge failing tests on their
   own: the test and its fix land together.
6. **Golden fixtures and quarantine entries change only with the owner's approval.** Agents never edit
   `src/test/resources/{golden,legacy,tls}/` or `quarantine.txt`, and never run `-Dgolden.update=true`. A hook
   blocks it; propose the change and stop. See the `golden-layout` skill.
7. **Format only what you edit.** A hook runs scalafmt on each Scala file you change. Never reformat other files.
8. **Credentials:** the test passwords (`passwd`, `p1pp0`) are the only ones allowed in the repo. Never log or
   print a password, token or credentialed URL.
9. **Never hard-code a Redis port in a test.** Ports are chosen at run time: use `redisPort` from the env traits, or
   `RedisContainers.standalonePort`, `tlsPort` and `clusterPort`.
10. **A new `-D` test property** goes in both `systemProperties` blocks of the pom, or it never reaches the test JVM.

## Working on this repo

- **PRs** go to `spike/phase-1` (lanes X, A and B) or `branch-3.x` (lane C) of `Redislabs-Solution-Architects/spark-redis`,
  and the owner approves every merge. Each PR cites the plan step or spec section it implements and lists its proof.
  **Wait for CI to finish before merging,** and check that a PR is still open before pushing to its branch.
- **Upstream (`RedisLabs/spark-redis`, remote `origin`):** a fix or improvement from this work that also helps 3.x
  goes upstream as a PR of its own, at the owner's request, and the owner merges it. An upstream PR carries none of
  the fork's files (`.claude/`, `doc/modernization/`, `evals/`), no Phase labels and no links to fork PRs. Upstream
  changes come into `branch-3.x` by merging upstream `master`, never by rebasing; after each merge, look for tests
  that both sides added. `spike/phase-1` doesn't merge upstream (plan, D5). Don't comment on or label upstream
  issues. Inside the fork, write issue numbers as plain `#123`.
- **Review rubric:** [`REVIEW.md`](REVIEW.md). **Skills:** [`golden-layout`](.claude/skills/golden-layout/SKILL.md),
  and drafts [`new-spark-line`](.claude/skills/new-spark-line/SKILL.md),
  [`redis-capabilities`](.claude/skills/redis-capabilities/SKILL.md) and
  [`dsv2-connector`](.claude/skills/dsv2-connector/SKILL.md).
- **Docs:** [intent](doc/modernization/intent.md), [spec](doc/modernization/spec.md),
  [roadmap](doc/modernization/roadmap.md), [Phase 1 plan](doc/modernization/plans/phase-1.md),
  [Phase 0 exit review](doc/modernization/plans/phase-0-exit-review.md), [backlog](doc/modernization/backlog.md).
