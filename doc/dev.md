### Development Environment

Spark-Redis is built with [Apache Maven](https://maven.apache.org/). A small [GNU Make](https://www.gnu.org/software/make/)
file wraps the common commands.

You need the following. Building with JDK 11 or newer is known to fail with the Scala and plugin versions the project
uses (for example with `NoClassDefFoundError: javax/tools/ToolProvider`), so check the Java version that `mvn -version`
reports.

- **A JDK 8** for this 3.x build, with `JAVA_HOME` pointing at it. The tests run on the same JDK, whatever `java`
  comes first on your `PATH`.
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
request. It runs the `redis_secondary` lines after each merge and nightly, and those jobs may fail without failing
the build. `scripts/redis-lines.sh supported` prints them all, the required lines first. Without `-Dredis.image`, as
in an IDE, the tests use the floor, `redis:8.2`.

Running tests from your IDE works the same way: the first suite that needs Redis starts it. To use Redis servers you
started yourself instead, on the old fixed ports (6379 standalone, 6380 TLS, 7379-7381 cluster), pass
`-Dredis.containers=false`.

To build Spark-Redis without running tests:

```
mvn clean package -DskipTests
```

### Scala and Spark versions

The build defaults to Scala 2.12 and the latest Spark 3.x (see `pom.xml`). To build and test for Scala 2.13, switch the
build with the script below. It changes the artifact name (`spark-redis_2.13`) and the Scala version in `pom.xml`, and
`dev/change-scala-version.sh 2.12` switches it back:

```
dev/change-scala-version.sh 2.13
make test
```

To test against another Spark version, pass it to Maven together with the Scala version that Spark release is built with,
for example:

```
make test MVN_ARGS="-Dspark.version=3.3.3 -Dscala.complete.version=2.12.15"
```

Code that is shared by both Scala versions must compile with both: use `scala.collection.JavaConverters._` with explicit
`.asScala` / `.asJava` conversions (`scala.collection.JavaConversions` doesn't exist in Scala 2.13), and convert Java
collections with `.asScala.toSeq` where a `Seq` is expected.

