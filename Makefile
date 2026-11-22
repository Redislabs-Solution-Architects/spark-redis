# Thin wrapper around Maven. The tests start their own Redis servers in Docker (see
# src/test/scala/com/redislabs/provider/redis/env/RedisContainers.scala), so no local redis-server is needed.
#
#   make test                                  # every suite, against redis:8.2
#   make test REDIS_IMAGE=redis:8.6            # another Redis version
#   make test SUITES=com.example.FooSuite      # one suite, or a comma-separated list
#   make benchmark                             # the benchmark suites (long)
#
# Requirements: Docker, and JAVA_HOME pointing at a JDK 8 for this 3.x build.

MVN ?= $(if $(wildcard ./mvnw),./mvnw,mvn)
REDIS_IMAGE ?= redis:8.2
SUITES ?=

MAVEN_ARGS = -B -Dredis.image=$(REDIS_IMAGE) $(if $(SUITES),-Dsuites=$(SUITES))

.PHONY: test benchmark help

test:
	$(MVN) $(MAVEN_ARGS) verify

benchmark:
	$(MVN) $(MAVEN_ARGS) -Pbenchmark verify

help:
	@sed -n '1,9p' $(firstword $(MAKEFILE_LIST))
