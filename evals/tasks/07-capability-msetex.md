# 07: A capability check with a fallback

**Tests:** capability gating (CLAUDE.md rule 4): probe the server for a command, keep a fallback, and test both.
Also cluster correctness (a multi-key command must stay in one slot) and no legacy commands.

**Starting commit:** `eval-base-phase-0`.

## Setup

`MSETEX` exists from Redis 8.4, so `redis:8.2` takes the fallback and `redis:8.6` takes the new path.

From `eval-base-phase-0`: none. From `eval-base-phase-1`, where Phase 0's #26 already writes with `MSETEX`, remove it
first. The patch also puts back the one `SETEX` per row that the prompt describes: by the Phase 1 base, the binary model
had moved to `SET … EX`:

```bash
git apply "$EVALS/setup/07-capability-msetex-phase-1.patch"
./mvnw -B -q test-compile
```

## Prompt

> When the `binary` model writes with a `ttl`, it sends one `SETEX` per row, and `SETEX` is a legacy command (spec
> §2.14). Write each batch of rows with their TTL in one `MSETEX` when the server has that command (Redis 8.4+), and
> with `SET … EX` otherwise. Decide by asking the server whether it has the command, once per write, not from its
> version number. The stored data, the TTLs and the golden layout must stay exactly as they are, on standalone and
> on a cluster. Test both paths.
>
> This task is an eval on a throwaway branch, so the Phase 0 freeze on `src/main` doesn't apply to it.

**If the agent asks** how to probe: "Use your judgment."

## Acceptance

```bash
set -e
mkdir -p src/test/scala/com/redislabs/provider/redis/evals
cp "$EVALS"/acceptance/07-capability-msetex/*.scala src/test/scala/com/redislabs/provider/redis/evals/
for v in 8.2 8.6; do
  ./mvnw -B verify -Dredis.image=redis:$v \
    -Dsuites=com.redislabs.provider.redis.evals.MsetexEvalStandaloneSuite,com.redislabs.provider.redis.evals.MsetexEvalClusterSuite
  ./mvnw -B verify -Dredis.image=redis:$v -DwildcardSuites=com.redislabs.provider.redis.golden
done
git diff --quiet "$START" -- src/test/resources/golden
! git diff "$START" -- src/main | grep -E '^\+.*(redis_version|"server"|getVersion)'     # no version checks
scripts/check-legacy-redis.sh --base "$START"
./mvnw -B verify -Dredis.image=redis:8.2
```

## Review

- The probe is `COMMAND INFO` (or `COMMAND DOCS`), run once per write job or cached per endpoint, not once per row.
- On a cluster, each `MSETEX` holds keys of one slot only. There's no `CROSSSLOT` path.
- The fallback is `SET … EX`, never `SETEX`.
- The agent's own tests cover both paths and run on both lines.
