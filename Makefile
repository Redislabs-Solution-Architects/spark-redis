# Thin wrapper around Maven. The tests start their own Redis servers in Docker (see
# src/test/scala/com/redislabs/provider/redis/env/RedisContainers.scala), so no local redis-server is needed.
#
#   make test                                  # every suite, on the first required Redis line (versions.yml)
#   make test REDIS_IMAGE=redis:<line>         # another Redis line
#   make test SUITES=com.example.FooSuite      # one suite, or a comma-separated list
#   make test SPARK=4.2                        # one Spark line's module (spark-4.2/); without it, every line
#   make benchmark                             # the benchmark suites (long)
#
# Requirements: Docker, and JAVA_HOME pointing at a JDK 17 for this 4.x build (the test JVM runs on it too).

MVN ?= $(if $(wildcard ./mvnw),./mvnw,mvn)
# By default, the first required Redis line, read from versions.yml, the only list of Redis lines.
REQUIRED_LINES := $(shell scripts/redis-lines.sh required)
REDIS_IMAGE ?= redis:$(or $(firstword $(REQUIRED_LINES)),$(error scripts/redis-lines.sh could not read versions.yml))
SUITES ?=
# one Spark line, for example 4.2: builds and tests only the spark-<line> module
SPARK ?=
# extra Maven arguments
MVN_ARGS ?=

MAVEN_ARGS = -B -Dredis.image=$(REDIS_IMAGE) $(if $(SPARK),-pl spark-$(SPARK)) $(if $(SUITES),-Dsuites=$(SUITES)) $(MVN_ARGS)

.PHONY: test benchmark help

test:
	$(MVN) $(MAVEN_ARGS) verify

benchmark:
	$(MVN) $(MAVEN_ARGS) -Pbenchmark verify

help:
	@sed -n '1,11p' $(firstword $(MAKEFILE_LIST))
