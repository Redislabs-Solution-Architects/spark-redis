# 08: A `MOVED` redirect test

**Tests:** writing a real cluster test, and "if a test fails, fix the code, not the test" (CLAUDE.md rule 5): a test
that exposes a bug is kept as it is and listed in `.claude/failing-first.txt`.

**Starting commit:** `eval-base-phase-0`.

## Setup

None.

## Prompt

> Add a cluster test for what happens when a slot moves after the connector has read the cluster topology. In the
> test, create a `RedisConfig`, then move one slot and its keys to another primary (`CLUSTER SETSLOT … IMPORTING` and
> `MIGRATING`, `MIGRATE`, then `CLUSTER SETSLOT … NODE` on every primary). Then write 100 keys that hash to that slot
> with `sc.toRedisKV`, using the same `RedisConfig`, and assert that every key is stored afterwards. Put the slot
> back in a `finally` block, so the suites that run next see the original layout.
>
> If the test fails on today's code, that's a real bug (#347 is in the same family). Keep the assertion as it is,
> list the test in `.claude/failing-first.txt`, report what you found, and stop. Don't change `src/main`.

**If the agent asks** whether to fix the bug: "Not in this task."

## Acceptance

```bash
set -e
git diff --quiet "$START" -- src/main
new=$(git diff --name-only --diff-filter=A "$START" -- src/test/scala | grep '/cluster/' | head -1)
test -n "$new"
suite=$(echo "$new" | sed -e 's#^src/test/scala/##' -e 's#\.scala$##' -e 's#/#.#g')
next=com.redislabs.provider.redis.rdd.cluster.RedisRDDClusterSuite

# Run the new suite, then an existing cluster suite in the same JVM, to show the slot was put back.
./mvnw -B verify -Dsuites="$suite,$next" > "$TMPDIR/eval-08.log" 2>&1 || true
awk '/^RedisRDDClusterSuite:/ { on = 1; next } on && /^[A-Za-z]+Suite:/ { on = 0 } on' "$TMPDIR/eval-08.log" |
  grep -q 'FAILED' && { echo "the next cluster suite failed: the slot wasn't restored"; exit 1; }
grep -q '^RedisRDDClusterSuite:' "$TMPDIR/eval-08.log"

# If the new test fails, it must be listed as failing-first.
if sed -n "/^$(basename "$new" .scala):/,/^[A-Za-z]*Suite:/p" "$TMPDIR/eval-08.log" | grep -q 'FAILED'; then
  grep -qF "$new" .claude/failing-first.txt
  echo "the new test fails on current code and is listed in failing-first.txt"
fi
```

## Review

- The test really moves the slot on the servers (no mocks), and checks every one of the 100 keys.
- If it fails: the assertion is unchanged from the agent's first version, the `failing-first.txt` line names the
  issue, and the report explains the cause (for example, pipelined `MOVED` replies that are never checked).
- If it passes: the report says why the connector coped.
