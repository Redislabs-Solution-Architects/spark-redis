# 09: The benchmark CSV sink

**Tests:** a small, self-contained change to test infrastructure, kept to the files it needs.

**Starting commit:** `eval-base-phase-0`. Phase 0 step B5 already added this sink, so the setup puts the benchmark
code back to its state before B5 (`37c855d`): `RedisBenchmarks.scala`, and the suites under `df/benchmark/`, whose B5
versions call the two-argument `time(tag, name)`. The suites B5 added are removed.

## Setup

```bash
bench=src/test/scala/com/redislabs/provider/redis/df/benchmark
git checkout 37c855d -- src/test/scala/com/redislabs/provider/redis/RedisBenchmarks.scala "$bench"
added=$(git diff --name-only --diff-filter=A 37c855d -- "$bench")
[ -z "$added" ] || git rm -q $added
./mvnw -B -q test-compile
rm -f target/benchmarks.csv
```

## Prompt

> Make the benchmark suites record their timings as CSV as well. Every call to `RedisBenchmarks.time` should append
> a `<name>,<millis>` row to `target/benchmarks.csv`, with the milliseconds as a whole number, and write a
> `name,millis` header when the file is new. Keep the existing text report.

**If the agent asks** about concurrency: "The benchmark suites run one at a time."

## Acceptance

```bash
set -e
mkdir -p src/test/scala/com/redislabs/provider/redis/evals
cp "$EVALS"/acceptance/09-benchmark-csv/*.scala src/test/scala/com/redislabs/provider/redis/evals/
./mvnw -B verify -Dsuites=com.redislabs.provider.redis.evals.BenchmarkCsvEvalSuite
git diff --quiet "$START" -- src/main
test -z "$(git diff --name-only "$START" | grep -v '^src/test/scala/')"     # test code only
```

## Review

- The file is opened, appended and closed per call (or through one writer that is flushed), so a crash mid-run
  keeps the rows already written.
- Names containing a comma are quoted or rejected, not written as a broken row.
