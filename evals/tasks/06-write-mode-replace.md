# 06: `write.mode=replace` (#351), without changing the `merge` golden layout

**Tests:** a write-path feature that must leave the 3.x data layout byte for byte as it is (spec §2.4, §2.11), and
an atomic per-key write.

**Starting commit:** `eval-base-phase-0`.

## Setup

From `eval-base-phase-0`: none. From `eval-base-phase-1`, where Phase 0's #23 already added `write.mode`, remove it
first:

```bash
git apply "$EVALS/setup/06-write-mode-replace-phase-1.patch"
./mvnw -B -q test-compile
```

## Prompt

> Implement #351: add a `write.mode` option to DataFrame writes with the `hash` model. `merge`, the default, keeps
> today's behaviour: fields the new row doesn't have stay in Redis. `replace` makes the stored hash exactly the new
> row, so fields that are missing from it or null in it are removed. Replace each row atomically, in one
> `MULTI`/`EXEC` per key that also sets its TTL when `ttl` is given. Any other value fails with a message that names
> the option. The golden layout for `merge` must not change.
>
> This task is an eval on a throwaway branch, so the Phase 0 freeze on `src/main` doesn't apply to it.

**If the agent asks** about the `binary` model: "Only `hash` in this task; `binary` rows are always whole."

## Acceptance

```bash
set -e
mkdir -p src/test/scala/com/redislabs/provider/redis/evals
cp "$EVALS"/acceptance/06-write-mode-replace/*.scala src/test/scala/com/redislabs/provider/redis/evals/
./mvnw -B verify -Dredis.image=redis:8.2 -Dsuites=com.redislabs.provider.redis.evals.WriteModeEvalSuite
git diff --quiet "$START" -- src/test/resources/golden                 # fixtures untouched
./mvnw -B verify -Dredis.image=redis:8.2 -DwildcardSuites=com.redislabs.provider.redis.golden
./mvnw -B verify -Dredis.image=redis:8.2
scripts/check-legacy-redis.sh --base "$START"
```

## Review

- The replace path is one `MULTI`/`EXEC` per key: remove (`UNLINK`, spec §2.14), `HSET`, then `EXPIRE` if there is a
  TTL. Nothing is written in separate, non-atomic steps.
- The agent's tests run in both `standalone/` and `cluster/`.
- `doc/dataframe.md` documents the option and its default.
