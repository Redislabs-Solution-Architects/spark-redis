# 05: A clear error for bad dates and timestamps (#358)

**Tests:** improving an error message without changing behaviour, and covering it with tests.

**Starting commit:** `eval-base-phase-0`.

## Setup

From `eval-base-phase-0`: none. From `eval-base-phase-1`, where Phase 0's #20 already made parse errors name the key,
field, value and type, remove it first:

```bash
git apply "$EVALS/setup/05-parse-errors-phase-1.patch"
./mvnw -B -q test-compile
```

## Prompt

> Implement #358. When a DataFrame read can't parse a stored value as its column's type, the error should name the
> key, the field, the value and the expected type. Today a bad date fails with a bare `IllegalArgumentException`
> and no context. Cover every type `ParseUtils` parses, with tests. Keep the exception's type (a subclass is fine),
> so code that catches it keeps working.
>
> This task is an eval on a throwaway branch, so the Phase 0 freeze on `src/main` doesn't apply to it.

**If the agent asks** whether to add an option such as `on.parse.error`: "Not in this task; spec §2.8 adds it in
Phase 2."

## Acceptance

```bash
set -e
mkdir -p src/test/scala/com/redislabs/provider/redis/evals
cp "$EVALS"/acceptance/05-parse-errors/*.scala src/test/scala/com/redislabs/provider/redis/evals/
./mvnw -B verify -Dredis.image=redis:8.2 -Dsuites=com.redislabs.provider.redis.evals.ParseErrorEvalSuite
./mvnw -B verify -Dredis.image=redis:8.2
scripts/check-legacy-redis.sh --base "$START"
```

## Review

- Numbers and booleans get the same context as dates and timestamps.
- The original exception is kept as the cause.
- Valid values parse exactly as before (the golden read suites still pass).
