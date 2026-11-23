# Thin wrapper around Maven. The tests start their own Redis servers in Docker (see
# src/test/scala/com/redislabs/provider/redis/env/RedisContainers.scala), so no local redis-server is needed.
#
#   make test                                  # every suite, on the Redis line PRs must pass (versions.yml)
#   make test REDIS_IMAGE=redis:<line>         # another Redis line
#   make test SUITES=com.example.FooSuite      # one suite, or a comma-separated list
#   make benchmark                             # the benchmark suites (long)
#
# Requirements: Docker, and JAVA_HOME pointing at a JDK 8 for this 3.x build.

MVN ?= $(if $(wildcard ./mvnw),./mvnw,mvn)
# By default, the first line PRs must pass, read from versions.yml, the only list of Redis lines.
REQUIRED_LINES := $(shell scripts/redis-lines.sh required)
REDIS_IMAGE ?= redis:$(or $(firstword $(REQUIRED_LINES)),$(error scripts/redis-lines.sh could not read versions.yml))
SUITES ?=

MAVEN_ARGS = -B -Dredis.image=$(REDIS_IMAGE) $(if $(SUITES),-Dsuites=$(SUITES))

.PHONY: test benchmark help

test:
	$(MVN) $(MAVEN_ARGS) verify

benchmark:
	$(MVN) $(MAVEN_ARGS) -Pbenchmark verify

help:
	@sed -n '1,9p' $(firstword $(MAKEFILE_LIST))
