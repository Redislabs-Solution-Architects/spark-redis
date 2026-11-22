# 04: `client.name` (#398)

**Tests:** a small configuration feature from the backlog, with tests and docs, without breaking anything.

**Starting commit:** `eval-base-phase-0`.

## Setup

None.

## Prompt

> Implement #398: let users set the Redis client name. Add a `spark.redis.client.name` Spark setting, and a
> `client.name` option on a DataFrame source that overrides it for that source. Every connection the connector
> opens should then carry that name, as `CLIENT LIST` shows. Without the setting nothing changes. Add tests and
> document the option in `doc/configuration.md`.
>
> This task is an eval on a throwaway branch, so the Phase 0 freeze on `src/main` doesn't apply to it.

**If the agent asks** about RDD or streaming sources: "The same setting should apply wherever the connector reads
`spark.redis.*`."

## Acceptance

```bash
set -e
mkdir -p src/test/scala/com/redislabs/provider/redis/evals
cp "$EVALS"/acceptance/04-client-name/*.scala src/test/scala/com/redislabs/provider/redis/evals/
./mvnw -B verify -Dredis.image=redis:8.2 -Dsuites=com.redislabs.provider.redis.evals.ClientNameEvalSuite
./mvnw -B verify -Dredis.image=redis:8.2                     # everything else still passes
scripts/check-legacy-redis.sh --base "$START"
scripts/check-spark-internal-imports.sh --base "$START"
```

## Review

- The agent's own tests cover the Spark setting, the source option, and the default.
- Two sources with different client names don't share a connection pool.
- `doc/configuration.md` lists the setting and the option.
