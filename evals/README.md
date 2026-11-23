# Agent evals

Ten tasks from this repo's real backlog and plan, used to measure how well an agent works inside the guardrails
(`CLAUDE.md`, `.claude/`, `scripts/check-*.sh`). A guardrail change that lowers the pass rate doesn't merge
(roadmap, "Eval suite").

**Phase 0 runs them by hand**, once, to record the baseline below. Phase 2 automates the runner and grows the suite
to 20–50 tasks.

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

## Running one task

```bash
EVALS=$PWD/evals                       # this folder, in your own checkout: the acceptance tests stay here
N=04-client-name                       # the task
git worktree add -b "eval/$N-$(date +%Y%m%d)" "$TMPDIR/eval-$N" eval-base-phase-0
cd "$TMPDIR/eval-$N"
# Only when testing a guardrail change: take the guardrails from the candidate commit.
#   git checkout <candidate> -- CLAUDE.md REVIEW.md .claude scripts .scalafmt.conf
# Run the task's Setup block here, then hide the evals from the agent and record the start:
git rm -r -q evals && git commit -q -am "eval $N: starting state"
START=$(git rev-parse HEAD)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
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

## Results

| Date | Guardrails commit | Model | 01 | 02 | 03 | 04 | 05 | 06 | 07 | 08 | 09 | 10 | Pass rate | Hook blocks | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-22 | `48aa7d1` (`eval-base-phase-0`) | Claude Opus 5.5, xhigh | pass | **fail** | n/a | pass | pass | pass | pass | pass | pass | pass | **8 / 9** | 0 | Baseline. See [the notes below](#baseline-2026-11-22) |
| 2026-11-22 | `94decd7` (PR #18) | Claude Opus 5.5, xhigh | | pass | | | | | | | | | 1 / 1 | 0 | Only 02, to test PR #18's fix for 02's failure |

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
