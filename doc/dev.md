### Development Environment

Spark-Redis is built with [Apache Maven](https://maven.apache.org/). A small [GNU Make](https://www.gnu.org/software/make/)
file wraps the common commands.

You need:

- **A JDK 8** for this 3.x build, with `JAVA_HOME` pointing at it.
- **Docker.** The tests start their own Redis servers in containers through Testcontainers, on free ports chosen at
  run time, so no local `redis-server` is needed, and several test runs can happen at once.

To run every test against Redis 8.2:

```
make test
```

To pick another Redis version, or to run only some suites:

```
make test REDIS_IMAGE=redis:8.6
make test SUITES=com.redislabs.provider.redis.df.standalone.HashDataframeStandaloneSuite
```

CI requires Redis 8.6 on every pull request, so run `make test REDIS_IMAGE=redis:8.6` before you open one. It runs
the other supported lines (8.2, 8.8 and 8.10) after each merge and nightly, and those jobs may fail without failing
the build. [`versions.yml`](../versions.yml) lists the lines.

Running tests from your IDE works the same way: the first suite that needs Redis starts it. To use Redis servers you
started yourself instead, on the old fixed ports (6379 standalone, 6380 TLS, 7379-7381 cluster), pass
`-Dredis.containers=false`.

To build Spark-Redis without running tests:

```
mvn clean package -DskipTests
```
