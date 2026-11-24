### Development Environment

Spark-Redis is built with [Apache Maven](https://maven.apache.org/). A small [GNU Make](https://www.gnu.org/software/make/)
file wraps the common commands.

You need the following. Check the Java version that `mvn -version` reports.

- **A JDK 17** for this 4.x build, which Spark 4 needs, with `JAVA_HOME` pointing at it. The tests run on the same
  JDK, whatever `java` comes first on your `PATH`. The 3.x build, upstream's `master` branch, needs a JDK 8.
- **Docker.** The tests start their own Redis servers in containers through Testcontainers, on free ports chosen at
  run time, so no local `redis-server` is needed, and several test runs can happen at once.

To run every test against the Redis line that pull requests must pass (the first, if there are several), run this
before you open one:

```
make test
```

To pick another Redis line, or to run only some suites:

```
make test REDIS_IMAGE=redis:<line>
make test SUITES=com.redislabs.provider.redis.df.standalone.HashDataframeStandaloneSuite
```

[`versions.yml`](../versions.yml) is the only list of Redis lines. CI requires its `redis` lines on every pull
request. It runs the `redis_secondary` lines after each merge, and those jobs may fail without failing the build. `scripts/redis-lines.sh supported` prints them all, the required lines first. Without `-Dredis.image`, as
in an IDE, the tests use the floor, `redis:8.2`.

Running tests from your IDE works the same way: the first suite that needs Redis starts it. To use Redis servers you
started yourself instead, on the old fixed ports (6379 standalone, 6380 TLS, 7379-7381 cluster), pass
`-Dredis.containers=false`.

The TLS suites generate a CA and a server certificate in memory when they run, for their own TLS container, so the
repository holds no private keys. With `-Dredis.containers=false`, your TLS server on port 6380 needs a certificate of
its own: pass a truststore for it to the test JVM with
`-Dtest.jvm.options="-Djavax.net.ssl.trustStore=/path/to/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"`.

To build Spark-Redis without running tests:

```
mvn clean package -DskipTests
```

### Scala and Spark versions

The build is Scala 2.13, which is all Spark 4 supports, with one module per Spark line in `versions.yml`:
`spark-4.<n>/` builds the shared `src/` against that line's latest patch release. `make test` builds and tests every
line, one after the other. To test one line:

```
make test SPARK=4.2
```

To test another patch release of a line, pass it to Maven together with the Scala version that Spark release is
built with (`<scala.version>` in its `spark-parent_2.13` pom), for example:

```
make test SPARK=4.2 MVN_ARGS="-Dspark.version=4.2.0 -Dscala.complete.version=2.13.18"
```

The 3.x build, for Spark 3.5, is upstream's `master` branch.

Convert between Java and Scala collections with `scala.jdk.CollectionConverters._` and explicit `.asScala` / `.asJava`,
and use `.asScala.toSeq` where a `Seq` is expected.

