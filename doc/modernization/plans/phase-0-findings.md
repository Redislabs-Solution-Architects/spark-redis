# Phase 0 findings

Results from running the **unchanged 3.x code** against Redis 8, recorded by step A3 of
[`phase-0.md`](phase-0.md). Step A5 (the RESP3 checks on managed Redis) will add a section here.

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

## Other findings

### 1. Test coverage was never recorded (fixed in this PR; reported upstream)

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
