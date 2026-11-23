# Thin wrapper around Maven. The tests start their own Redis servers in Docker (see
# src/test/scala/com/redislabs/provider/redis/env/RedisContainers.scala), so no local redis-server is needed.
#
#   make test                                  # every suite, on the Redis line PRs must pass (versions.yml)
#   make test REDIS_IMAGE=redis:<line>         # another Redis line
#   make test SUITES=com.example.FooSuite      # one suite, or a comma-separated list
#   make test MVN_ARGS="-Dspark.version=3.3.3 -Dscala.complete.version=2.12.15"   # another Spark version
#   make benchmark                             # the benchmark suites (long)
#
# Requirements: Docker, and JAVA_HOME pointing at a JDK 17 for this 4.x build (the test JVM runs on it too).

MVN ?= $(if $(wildcard ./mvnw),./mvnw,mvn)
# By default, the first line PRs must pass, read from versions.yml, the only list of Redis lines.
REQUIRED_LINES := $(shell scripts/redis-lines.sh required)
REDIS_IMAGE ?= redis:$(or $(firstword $(REQUIRED_LINES)),$(error scripts/redis-lines.sh could not read versions.yml))
SUITES ?=
# extra Maven arguments, for example another Spark version (see the header)
MVN_ARGS ?=

MAVEN_ARGS = -B -Dredis.image=$(REDIS_IMAGE) $(if $(SUITES),-Dsuites=$(SUITES)) $(MVN_ARGS)

.PHONY: test benchmark help

test:
	$(MVN) $(MAVEN_ARGS) verify

benchmark:
	$(MVN) $(MAVEN_ARGS) -Pbenchmark verify

help:
	@sed -n '1,11p' $(firstword $(MAKEFILE_LIST))
