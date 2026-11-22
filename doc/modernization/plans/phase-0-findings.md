# Phase 0 findings

Results from running the **unchanged 3.x code** against Redis 8, recorded by step A3 of
[`phase-0.md`](phase-0.md), and from the RESP3 checks on managed Redis in step A5.

## A3: the existing suite on Redis 8

Each line was run with `./mvnw -B verify -Dredis.image=redis:<line>` on JDK 8 (Zulu 1.8.0_514), macOS arm64, Docker
Desktop, on 2026-11-22. The machine was not idle: other Docker workloads were running.

| Redis image | Server version | Suites | Tests | Result | Run time |
|---|---|---|---|---|---|
| `redis:8.2` | 8.2.11 | 42 | 215 | **215 passed**, 0 failed, 0 canceled | 2 min 35 s |
| `redis:8.6` | 8.6.8 | 42 | 215 | **215 passed**, 0 failed, 0 canceled | 2 min 41 s |
| `redis:8.10` | 8.10.3 | 42 | 215 | **215 passed**, 0 failed, 0 canceled | 2 min 23 s |

**How we know each run used the version it asked for:** for each line, a short run (`ConnectionPoolTest`, 4/4)
was checked with `docker events`, which showed a container created from the requested image. `redis-server --version`
in each image reported the version above. The full runs pass the image the same way (the `redis.image` forwarding
added in PR #3).

### What this means

- **No Redis 8 incompatibilities in the tested paths.** spark-redis 3.x (Jedis 3.9, RESP2) works unchanged on 8.2,
  8.6 and 8.10. That includes the legacy commands it still sends, which Redis 8 keeps for compatibility: `HMSET` +
  `EXPIRE`, `SETEX`, `SMEMBERS`, `CLUSTER SLOTS`, `INFO` parsing, and `XREADGROUP … NOACK`.
- **Nothing to quarantine.** `src/test/resources/quarantine.txt` and the tag exclusion described in the plan were
  not created, because no test fails. If a future Redis line breaks a test, add both then.
- **No upstream issues about Redis 8 compatibility** are needed.

### Coverage of the 3.x code by these tests

Measured with JaCoCo on the `redis:8.2` run, after fixing coverage collection (next section):

| Package | Line coverage |
|---|---|
| **Overall** | **87.4%** of 1,337 lines; branches 38.3% of 947 |
| `com.redislabs.provider.redis` (config, `RedisContext` functions) | 83% of 447 |
| `com.redislabs.provider.redis.rdd` | 94% of 225 |
| `com.redislabs.provider.redis.streaming` (DStreams) | 67% of 127 |
| `com.redislabs.provider.redis.util` | 91% of 138 |
| `org.apache.spark.sql.redis` (DataFrame source) | 95% of 228 |
| `org.apache.spark.sql.redis.stream` (Structured Streaming source) | 93% of 168 |

The least covered classes with 15 or more lines are `RedisContext` (66%), `RedisConfig` (81%) and `RedisEndpoint` (82%).
The low branch figure is typical for compiled Scala, where pattern matches and generated code add many branches.

## A5: RESP3 on managed Redis

4.x will speak only RESP3 (spec §2.1), so step A5 checks that the managed Redis it certifies against accepts
`HELLO 3`. The check is in two scripts:

- **`scripts/check-resp3.sh <redis-url> [docker-network]`** sends `HELLO 3` from a throwaway `redis:8.10` container
  and passes only if the reply says `proto 3`. It was tested against:
  - Redis 8.2.11 with the right password: passes, `proto=3`
  - the same server with a wrong password: fails, showing `WRONGPASS`
  - Redis 5, which has no RESP3: fails with `ERR unknown command HELLO`
- **`scripts/redis-software-resp3.sh`** starts a throwaway one-node Redis Software cluster on its own Docker
  network, creates one database with default settings, runs the check, and removes everything afterwards.
  - It publishes no host ports.
  - The admin and database passwords are random, used only inside the run, and never printed.

| Target | Version | Result |
|---|---|---|
| Redis Software, latest (`redislabs/redis:latest`, built 2026-11-18) | Redis Software 8.2.6-58. The new database defaulted to **Redis 8.8**, with `resp3: true` | **RESP3 accepted**: `HELLO 3` returned `proto=3`, server version 8.8.0, mode standalone (through Redis Software's proxy) |
| Redis Cloud, latest | — | **Not checked**: no test database has been provided. To run it, store the database's URL as the fork's `REDIS_CLOUD_URL` secret, or run `scripts/check-resp3.sh <url>` locally |

The 2026-11-22 run took 23 seconds with the image already pulled, and it left no containers or networks behind.

### What this means

- **The latest Redis Software is RESP3 by default.** A new database has `resp3: true`, so 4.x's RESP3-only rule
  needs no configuration there.
- **Databases can switch RESP3 off.** A database created on an older Redis Software release, or one with `resp3`
  set to false, would make 4.x fail at connect, which is what spec §2.1 intends. The docs should tell Redis Software
  users how to check and enable the setting.
- **Redis Software 8.2.6 creates 8.8 databases by default,** which is newer than its 8.6 default noted in spec §1.
  The spec's certification line ("latest Redis Software release") still holds.

### Script bugs found while running the check

Both were fixed before the results above:

1. **Password generation.** The passwords were generated with `tr … </dev/urandom | head`. Under `set -o pipefail`,
   `tr` exits with SIGPIPE (code 141) when `head` closes the pipe, so the script stopped silently on its first
   line. It now uses `openssl rand`.
2. **Database status.** The status was read from the REST JSON with `grep`, which picked up a nested shard status
   (`idle`) instead of the database's top-level `status`. It now parses the JSON with Python and prints single
   fields only, because the database object includes its password.

## Other findings

### 1. Test coverage was never recorded (fixed in PR #9; reported upstream as RedisLabs/spark-redis#404)

**Symptom.** Every build logged "Skipping JaCoCo execution due to missing execution data file." There were two causes:

- **The agent never loaded.** `jacoco:prepare-agent` puts its Java agent in the `argLine` property, but the scalatest
  plugin sets its own `<argLine>`, which replaces that property. Making the plugin's `-DargLine` explicit on the
  command line, as the old `make test` did, has the same effect.
- **The report ran too early.** The `report` goal was bound to the `test` phase and declared before the scalatest
  plugin, so it ran before the tests had produced any data.

**Fix.** `prepare-agent` now writes the agent to `jacocoArgLine`, which the scalatest `argLine` references. `report`
now runs at `verify`.

**Caveat.** `-Djacoco.skip=true` would now leave a literal `${jacocoArgLine}` on the test JVM's command line, so
don't skip JaCoCo when running tests.

**Upstream.** RedisLabs/spark-redis has the same `pom.xml` and uploads to Codecov in CI, so its coverage reports
have been empty.

### 2. A test's failure mode depends on key order

`HashDataframeSuite` "load unfiltered binary keys with hashes" expects reading binary-model rows mixed with a plain
hash to fail. It passes on every line. On the 8.6 and 8.10 runs, Spark logged the expected failure as
`Error while encoding: ArrayIndexOutOfBoundsException`; on 8.2 it surfaced differently. Which key `SCAN` returns first
decides the path, so this is not a Redis 8 change.

### 3. The test TLS certificate has expired

`src/test/resources/tls/redis.crt` expired on 2021-05-17; the CA certificate is valid until 2030. The TLS test still
passes, so the client's truststore evidently trusts the server certificate directly. The certificate should be
regenerated before anything starts checking expiry, for example Jedis 8's default hostname verification in Phase 1.
`tls/` is a protected path, so regenerating it needs the owner's approval.

### 4. The Redis password is not redacted in the Spark UI or logs

Spark's default `spark.redaction.regex` does not match `spark.redis.auth`. See spec §2.9.

### 5. Ignorable warnings

The only recurring warnings are Spark's own: "spark.sql.adaptive.enabled is not supported in streaming" and notes
about temporary checkpoint folders. There were no Redis error replies (`ERR`, `WRONGTYPE`, `NOAUTH`, `NOPERM`,
`MOVED`, `ASK`) in any log.
