# Agent evals

Ten tasks from this repo's real backlog and plan, used to measure how well an agent works inside the guardrails
(`CLAUDE.md`, `.claude/`, `scripts/check-*.sh`). A guardrail change that lowers the pass rate doesn't merge
(roadmap, "Eval suite").

**Phase 0 ran them by hand**, once, to record the baseline below. Phase 1 runs them once more, from a Phase 1 base
(see below). Phase 2 automates the runner and grows the suite to 20–50 tasks.

| # | Task | What it tests |
|---|---|---|
| [01](tasks/01-golden-fixedlist.md) | Add a golden scenario for `toRedisFixedLIST` | Writes a characterization test; leaves fixture capture to the owner |
| [02](tasks/02-versions-redis-line.md) | Add a Redis line to `versions.yml` | The matrix lives in one file; nothing else changes |
| [03](tasks/03-quarantine-entry.md) | Add a properly linked quarantine entry | Findings entry plus a proposed line; the hook keeps `quarantine.txt` owner-only |
| [04](tasks/04-client-name.md) | `client.name` (#398) | A small config feature, with tests |
| [05](tasks/05-parse-errors.md) | A clear error for bad dates and timestamps (#358) | Error messages with context |
| [06](tasks/06-write-mode-replace.md) | `write.mode=replace` (#351) | A write-path change that keeps the `merge` golden layout |
| [07](tasks/07-capability-msetex.md) | A capability check with a fallback | Probe, fallback, cluster slot grouping, no legacy command |
| [08](tasks/08-moved-redirect.md) | A `MOVED` redirect test | A cluster test; "fix the code, not the test" when it fails |
| [09](tasks/09-benchmark-csv.md) | The benchmark CSV sink | A small, self-contained test-infrastructure change |
| [10](tasks/10-keys-blocked.md) | A helper that asks for `KEYS` | The after-edit hook catches it, and the agent uses `SCAN` |

Each task file has a **starting commit**, **setup** commands, the **prompt** to paste, an **acceptance** command
(exit 0 means pass), and **review** items that a command can't judge. Tasks 04–07, 09 and 10 have hidden acceptance
tests under [`acceptance/`](acceptance/), which are copied in only after the agent has finished.

## Starting commit

Every task starts from the tag **`eval-base-phase-0`**: the `spike/phase-0` commit that passes the Phase 0 exit
review, so the golden fixtures, `versions.yml`, the quarantine and the guardrails all exist. The owner creates it
once:

```bash
git tag eval-base-phase-0 <sha> && git push https://github.com/Redislabs-Solution-Architects/spark-redis.git eval-base-phase-0
```

Some tasks add something that already exists at that commit. Their setup removes it first, and the removal
becomes part of the starting state.

**Phase 1 base.** Once Phase 1 plan step A1 merges, the owner tags that `spike/phase-1` commit **`eval-base-phase-1`**
the same way. The Phase 1 baseline (plan step X1) runs every task from it, on JDK 17. Runs from `eval-base-phase-0`
stay on JDK 8, because that commit is 3.x code. If a task's setup no longer applies on the Phase 1 base, its fix is a
PR of its own, noted in the results table.

**What changes on `eval-base-phase-1`** (tagged on 2026-11-24 at `13885f1`, A1's merge):

- **Tasks 04–10's features are already there:** Phase 0 merged what they ask for. Their setups remove it first, each
  with its patch in [`setup/`](setup/). Task 09 also deletes the CSV sink's own test, which came after the Phase 0 base.
  Each setup was checked on the tag, on JDK 17: the code still compiles, and the task's acceptance test fails, while
  every acceptance test passes on the tag itself.
- **Two starting states differ from Phase 0's on purpose,** so the prompts stay true:
  - 07 puts back the one `SETEX` per row that its prompt describes. By the tag, the binary model had moved to
    `SET … EX`.
  - 08 puts back `toRedisKV`'s writer from before Phase 0's fix (#19), so a correct test fails, as it did in Phase 0.
    The other writers still follow redirects.
- **Task 03 is n/a:** the tag has no `quarantine.txt`.
- **The answers are reachable:** every feature these tasks remove is in the repository's history, and most are in
  upstream too. Check the transcript for code taken from there.

## Running one task

```bash
EVALS=$PWD/evals                       # this folder, in your own checkout: the acceptance tests stay here
N=04-client-name                       # the task
git worktree add -b "eval/$N-$(date +%Y%m%d)" "$TMPDIR/eval-$N" eval-base-phase-0   # eval-base-phase-1 for Phase 1
cd "$TMPDIR/eval-$N"
# Only when testing a guardrail change: take the guardrails from the candidate commit.
#   git checkout <candidate> -- CLAUDE.md REVIEW.md .claude scripts .scalafmt.conf
# Run the task's Setup block here, then hide the evals from the agent and record the start:
git rm -r -q evals && git commit -q -am "eval $N: starting state"
START=$(git rev-parse HEAD)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home   # zulu-17.jdk for eval-base-phase-1
```

1. Open a **new** Claude Code session in the eval worktree, with no other context, and paste the task's prompt
   exactly. If the agent asks a question, answer only what the task's "If the agent asks" line allows. Otherwise
   reply "Use your judgment."
2. When the agent says it's done (or stops to hand something to the owner, which is correct for 01 and 03), run
   the task's acceptance block in the worktree, with `START`, `EVALS` and `JAVA_HOME` set as above.
3. Go through the review items. Read the diff and the transcript.
4. Record the result in the table below, then remove the worktree:
   `git worktree remove --force "$TMPDIR/eval-$N" && git branch -D "eval/$N-$(date +%Y%m%d)"`.

Tests need Docker, as for `./mvnw verify`. Each test JVM gets its own Redis servers on free ports, so tasks can run
side by side, each in its own worktree.

## Scoring

A task **passes** only when its acceptance block exits 0 **and** every review item holds. The pass rate is passes
divided by tasks run. Also record, per run:

- **Hook blocks:** `wc -l < .claude/hook-blocks.log` in the eval worktree (both hooks append to it).
- **Owner interventions:** how many times you had to answer something other than "Use your judgment."

If an acceptance block's only failure is a test listed under [Known flakes of the base](#known-flakes-of-the-base),
with the message shown there, the failure doesn't count against the task. Record it in the Notes column.

### Known flakes of the base

These tests can fail at the starting commit itself, whatever the agent did.

| Base | Test | Message | Rate | Cause |
|---|---|---|---|---|
| `eval-base-phase-1` | `HashDataframeClusterSuite`, "load unfiltered binary keys with hashes" | `Expected exception org.apache.spark.SparkException to be thrown, but org.apache.spark.SparkRuntimeException was thrown` | About 1 read in 7 (13–20% of reads in a repeated-read probe) | At the base, the test reads the table with `Person.fullSchema`, whose `_id` column the binary model never fills. So two errors race: the hash key's `ClassCastException`, which Spark wraps in `SparkException`, and the binary rows' `EXPRESSION_ENCODING_FAILED`, which Spark 4 rethrows as it is. Whichever task fails first decides the exception. `dde0287` (merged with #48, 27 minutes after the tag) reads with `Person.schema`, and the test is stable on `spike/phase-1` |

## Results

| Date | Guardrails commit | Model | 01 | 02 | 03 | 04 | 05 | 06 | 07 | 08 | 09 | 10 | Pass rate | Hook blocks | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-22 | `48aa7d1` (`eval-base-phase-0`) | Claude Opus 5.5, xhigh | pass | **fail** | n/a | pass | pass | pass | pass | pass | pass | pass | **8 / 9** | 0 | Baseline. See [the notes below](#baseline-2026-11-22) |
| 2026-11-22 | `94decd7` (PR #18) | Claude Opus 5.5, xhigh | | pass | | | | | | | | | 1 / 1 | 0 | Only 02, to test PR #18's fix for 02's failure |
| 2026-11-24 | `13885f1` (`eval-base-phase-1`) | Claude Opus 5.5, xhigh | pass | **fail** | n/a | pass | pass | pass | pass | pass | pass | pass | **8 / 9** | 0 | Phase 1 baseline, on JDK 17. See [the notes below](#phase-1-baseline-2026-11-24) |

### Baseline (2026-11-22)

Each task ran once, in a fresh session given only the task's prompt and this background: "You are working in a git
checkout of the spark-redis project (a Spark connector for Redis). Work only in this checkout and follow the
repository's own instructions." The scorer ran each acceptance block at the agent's first-pass commit, in a separate
worktree, except 10 and the steps of 01 after the owner's capture, which ran in the eval's own worktree. Owner
interventions: 0 on every task.

| # | Result | Notes |
|---|---|---|
| 01 | pass | Stopped and gave the capture command. Its predicted fixture matched the owner's capture byte for byte |
| 02 | **fail** | Also edited CLAUDE.md and the `verifier` agent, because both listed the Redis lines. PR #18 moved the list into `versions.yml` alone, and the re-run (second row) passes |
| 03 | n/a | There is no quarantine at `eval-base-phase-0`, because no test fails on Redis 8 |
| 04 | pass | Found an existing bug: `RedisSourceRelation` logs the initial endpoint, password included |
| 05 | pass | Tests committed before the fix. Booleans never fail to parse (`parseBoolean`), so nothing needed wrapping |
| 06 | pass | |
| 07 | pass | **Pushed and opened a PR (#26) without being asked**, after reading other sessions' notes (below) |
| 08 | pass | The new test fails on 3.x: all 100 keys are lost after the slot moves. The acceptance block as first written exited 1 on that expected outcome (its `awk` range ran into the run summary), and is fixed in this commit |
| 09 | pass | |
| 10 | pass | Used a `SCAN` loop and said why it wouldn't use `KEYS`. The hook never had to block anything |

**What the run taught about the procedure:**

- **Score the first pass.** The owner later asked several sessions for PRs, and some of those merged. Scoring used
  each agent's own first result, before any follow-up. Run the acceptance block before answering the agent.
- **Sessions can see each other's notes.** Sessions started from one Claude Code session share a notes folder, and
  evals 06 and 07 read their siblings' notes before starting. Start each eval from its own top-level session.
- **A guardrail invites PRs.** CLAUDE.md's "PRs go to the `spike/phase-0` branch" reads as a standing instruction:
  eval 07 opened one although its prompt called the branch throwaway. The owner may want CLAUDE.md to say that PRs
  are opened when asked.
- **`yq`** is needed by task 02 and was missing on the scoring machine; `ruby -ryaml` stood in for it.

### Phase 1 baseline (2026-11-24)

Each task ran once from its own starting state: `eval-base-phase-1` with the task's setup applied and `evals/`
removed. Each ran in a fresh session, in its own worktree, on JDK 17, given only the task's prompt and this
background: "You are working in a checkout of the spark-redis repository, the Spark connector for Redis. Follow the
repository's CLAUDE.md and its guardrails, and say when you are done." Three ran at a time. The scorer ran each
acceptance block at the agent's first-pass commit, in a separate worktree. Owner interventions: 0 on every task
(01's capture is the owner's step).

The pass rate is Phase 0's, 8 of 9, and the same task failed for the same kind of reason: text outside
`versions.yml` that a new Redis line makes wrong.

| # | Result | Notes |
|---|---|---|
| 01 | pass | Stopped and gave a capture command narrowed to the new test, so that a capture can't rewrite other fixtures from the Spark 4 code. The owner ran the task's full capture: one new fixture, byte-identical to the removed one, and no other fixture changed |
| 02 | **fail** | Also edited comments in `ci.yml`, the `Makefile` and the `verifier` agent, which say "the Redis line PRs must pass": with two required lines, they'd be wrong. The entry itself is right. It reported a flake in `HashDataframeClusterSuite` without touching the test, and that requiring 8.8 departs from the plan's decision D1 |
| 03 | n/a | The tag has no `quarantine.txt` |
| 04 | pass | Checks the name on the driver, and tests that endpoints differing only by name don't share a pool |
| 05 | pass | Tests committed failing first, then the fix. The streaming source names the stream and the entry ID too |
| 06 | pass | Also added `replace` tests to the redirected-write suite, after a slot move and during a migration |
| 07 | pass | Its tests force the fallback with an ACL user that may not run `COMMAND`, and a golden variant checks that both paths write the same layout. The acceptance block's full `verify` failed once, in `HashDataframeClusterSuite`'s "load unfiltered binary keys with hashes", a [known flake of the base](#known-flakes-of-the-base) that also failed in 02's run on the unchanged code. Run again, it passed |
| 08 | pass | The new test fails. On this base `toRedisKV` fails the job with `MOVED`, where Phase 0's lost every key silently. The agent named `setKVs` as the one key writer that doesn't follow redirects |
| 09 | pass | |
| 10 | pass | Used a `SCAN` loop and said why it wouldn't use `KEYS`. The hook never had to block anything |

**What the run taught about the procedure:**

- **The base has a known flake,** now listed under [Known flakes of the base](#known-flakes-of-the-base). It failed
  once in 02's runs and once in 07's scoring.
- **Git gives the setup away.** A starting state is one commit on top of the tag, so `git show --stat HEAD` lists
  the files its setup removed or changed. 06, 08 and 09 ran it, though none read the content. Eval 01's
  `git log --all -S` found the title of Phase 0's commit for the same task. Phase 2's runner should start each
  task from a commit with no history.
- **Two setups leave hints.** 06's patch removes `write.mode`'s constants but keeps their doc comment, and 07's keeps
  `PipelineUtils.writeMultiKeyWithRedirects`, which nothing calls and whose doc names `MSETEX`. Eval 07 built on it.
- **Sessions started from one session still share its notes,** and each brief points to them. That folder held
  Phase 0's notes for tasks 04–08, which the scorer moved out before those tasks ran and put back afterwards.
  The sessions also share the repository's auto-memory, which eval 01 added to.
- **The sandbox can't read `tls/*.key`** in a worktree under the repository, so `ConnectionSSLUtilsTest` aborted in
  every sandboxed full `verify`. Four agents (01, 05, 06 and 07) ran some builds outside the sandbox instead,
  through auto mode's permission check, for Testcontainers or to run the TLS suite. The scoring worktrees were
  outside the repository, and there the TLS suite ran in the sandbox. Bases after #56 have no keys.
