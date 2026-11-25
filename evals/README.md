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
| 2026-11-24 | `d701e64` (PR #65) | Claude Opus 5.5, xhigh | | **fail** | | | | | | | | | 0 / 1 | 1 | Only 02, from `eval-base-phase-1`, to test PR #65's fix for 02's failure in #57. See [the notes below](#02-re-run-on-the-phase-1-base-2026-11-24) |
| 2026-11-24 | `a495462` (the records rule) | Claude Opus 5.5, xhigh | | pass | | | | | | | | | 1 / 1 | 0 | Only 02, from `eval-base-phase-1`, to test `CLAUDE.md`'s new line on `doc/modernization/`. See [the notes below](#02-with-the-records-rule-2026-11-24) |
| 2026-11-24 | `a495462` (end of Phase 1) | Claude Opus 5.5, xhigh | pass | pass | n/a | pass | pass | pass | pass | pass | pass | pass | **9 / 9** | 0 | End of Phase 1: every task from `eval-base-phase-1`, with the guardrails Phase 1 ends with. See [the notes below](#end-of-phase-1-2026-11-24) |

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

### 02 re-run on the Phase 1 base (2026-11-24)

In the [Phase 1 baseline](#phase-1-baseline-2026-11-24) (#57), 02 failed because the agent also reworded comments
that spoke of the required Redis line in the singular: in `ci.yml`, the `Makefile` and the `verifier` agent. PR #65
rewords them, and this run tests that fix.

- **Setup.** The run started from `eval-start-phase-1/02-versions-redis-line`.
  - **From `d701e64`:** `CLAUDE.md REVIEW.md .claude scripts .scalafmt.conf`, plus `versions.yml Makefile .github`,
    because the fix is in those files too.
  - **The session** was headless and top level (`claude -p --permission-mode auto --strict-mcp-config`). No other
    session started it, so it saw no shared notes, and it had no MCP servers. It could still read the repository's
    auto-memory, and read two entries: the sandbox Maven notes and the base's known flake.
  - **Input:** the task's prompt, and the Phase 0 background (see [Baseline](#baseline-2026-11-22)).

  The scorer ran the acceptance block on the first pass. Owner interventions: 0.
- **Fail.** The agent left `ci.yml`, `matrix.jq`, the `Makefile` and the `verifier` agent alone. But it added dated
  notes to two records:
  - decision D1 in `plans/phase-1.md`, which says PRs run Redis 8.6;
  - open item 8 in `plans/phase-0-exit-review.md`, which says the redirected-write fix isn't confirmed on 8.8. The
    agent had run those suites 20 times on 8.8.

  The other acceptance steps pass, and every review item holds. It also dropped "8.2 to 8.10" from
  `versions.yml`'s header, and PR #65 now does the same.
- **The comments no longer cause the failure: the records that name a Redis line do.** The owner decides whether
  02 allows a note on D1, or whether the records stop naming lines. **Since then:** neither. A `CLAUDE.md` line
  says the records stay as they are, and 02 passes with it ([below](#02-with-the-records-rule-2026-11-24)).

### 02 with the records rule (2026-11-24)

The [re-run above](#02-re-run-on-the-phase-1-base-2026-11-24) failed on the dated notes the agent added to decision
D1 and to Phase 0's open item 8. Nothing told it those documents record what was true when they were written. So
`a495462` adds one line to `CLAUDE.md`: `doc/modernization/` is the owner's record, edited only when the task is to
write or amend one of its documents, or when the owner asks. A change that makes something there out of date is
reported instead. Task 02's acceptance block and review items are unchanged.

- **Setup.** As in the re-run above, from `eval-start-phase-1/02-versions-redis-line`, with the same paths taken from
  `a495462` instead of `d701e64` (starting state `be19f06`). The starting state differs from that run's only by #65's
  last changes to `ci.yml` and `versions.yml`, and by the new line. The session was headless and top level, with the
  same flags, model and input, and it could read the repository's auto-memory. Owner interventions: 0.
- **Pass.** The agent changed one line: `redis: ["8.6", "8.8"]`. The acceptance block exited 0:
  `HashDataframeStandaloneSuite` ran 21 tests on `redis:8.8`, and none failed. Every review item holds. Hook blocks: 0.
- **It reported the records instead of editing them.** Its report lists two places in `plans/phase-1.md` as out of
  date, "left alone, per repo rules": B2's "the required Redis line", in the singular, and decision D1's PR matrix.
- **Also in its report:** the full suite on 8.8 in the sandbox (610 passed, 1 canceled, by its count), and
  `ConnectionSSLUtilsTest` run on its own outside the sandbox, because this base still has `tls/*.key`. It asked
  before committing or opening a PR.

**What the run taught about the procedure:**

- **The guardrail overlay doesn't fit this base.** `ci.yml`, `release.yml`, the `Makefile`'s `SPARK=` and the
  `verifier` agent come from the candidate, and they select a module with `-pl spark-<line>`. But `eval-base-phase-1`
  is still one Maven project: B1 (#48) added the modules after the tag. So every `-pl` run fails with "Could not find
  the selected project in the reactor", and the verifier returned no results. The agent ran Maven without `-pl`,
  which tests Spark 4.1 only. 02's acceptance block doesn't use `-pl`, so the score is unaffected. Phase 2's runner
  should start from a base that has the modules.
- **The session wrote to the shared auto-memory:** a new note on previewing the CI matrices without `yq`, and a
  paragraph on the missing modules in the base's flake note. The owner installed `yq` during the run, so the new note
  was already wrong, and it was deleted afterwards. A later eval session could read anything left there, so Phase 2's
  runner should give each session an empty memory.

### End of Phase 1 (2026-11-24)

The exit review asked for the pass rate at the end of Phase 1: the same tasks as the
[Phase 1 baseline](#phase-1-baseline-2026-11-24), from the same base, with the guardrails Phase 1 ends with. **All 9
pass, up from 8 of 9 at the start.** 02 failed at the start; #65 and #66 fixed it. The other 8 still pass with the
newer guardrails.

- **Setup.** Each task started from its `eval-start-phase-1/<task>` tag, on branch `eval/<task>-end-phase-1`.
  - **The guardrails** came from `8e4ba5f`, whose files are `a495462`'s: `CLAUDE.md REVIEW.md .claude scripts
    .scalafmt.conf`, plus `versions.yml Makefile .github`. 02's re-runs took the same set. Here it also gives
    `scripts/spark-lines.sh`, which `CLAUDE.md`'s build command calls, the `spark` keys it reads.
  - **The sessions** were headless and top level (`claude -p --permission-mode auto --strict-mcp-config`), on JDK 17,
    three at a time. Each got the Phase 1 baseline's background and the task's prompt.
  - **One change from the baseline:** `git push`, `gh pr create` and `gh pr merge` were disallowed, because eval 07
    opened a PR unasked in Phase 0. Only 02 and 08 asked anything, both whether to commit, which no task's answer
    covers. Owner interventions: 0. Hook blocks: 0 on every task.
- **Scoring.** Each acceptance block ran unchanged in its eval worktree after the session ended, with every review
  item checked against the diff and the transcript. Two steps needed the agent's work committed or read in full:
  - **08:** its agent didn't commit, and its block finds the new suite with `git diff --diff-filter=A`, which doesn't
    see an untracked file. The baseline scored first-pass commits, so the scorer committed 08's first pass.
  - **07:** its no-version-check grep reads `git diff`, which skips the agent's untracked `RedisCapabilities.scala`.
    The scorer ran the grep over that file too, and it found no version checks.

| # | Result | Time | Cost | Notes |
|---|---|---|---|---|
| 01 | pass | 3 min | $0.95 | Stopped and gave the capture command, and predicted the fixture: two lists, no TTL, `5, 4, 3` and `3, 2, 1`. The owner's capture step, run by the scorer in the eval worktree, matched the removed fixture byte for byte. The after-edit hook also reformatted the scenario above the new one (rule 7, as in Phase 0) |
| 02 | pass | 18 min | $1.80 | Changed only `versions.yml`. Reported decision D1 as out of date, "owner's record, not edited" |
| 04 | pass | 16 min | $4.57 | Its tests cover the setting, the option and the default, and "endpoints that differ only in their client name are different pool keys" |
| 05 | pass | 26 min | $6.09 | Tests committed first, then the fix. A bad number throws `FieldNumberFormatException`, a `NumberFormatException`, with the original as its cause |
| 06 | pass | 25 min | $7.18 | Each replaced row is `MULTI`, `UNLINK`, `HSET`, `EXPIRE` with a `ttl`, `EXEC`. The full `verify` had one failure, "load unfiltered binary keys with hashes", a [known flake of the base](#known-flakes-of-the-base) with its listed message: 615 passed. It moved a test helper into `MonitoredCommands` unchanged |
| 07 | pass | 26 min | $8.21 | Asks each primary once per write with `COMMAND INFO`. Groups `MSETEX` keys by slot. Falls back to `SET … EX`, and its tests force the fallback with an ACL user that can't run `COMMAND`. Reported spec §2.14's table and the `redis-capabilities` skill as out of date, and left them alone |
| 08 | pass | 7 min | $1.28 | The new test fails, so it's listed in `failing-first.txt` with #347. The report names the cause: `setKVs` sends pipelined writes and fails the task on `MOVED` instead of resending. The cluster suite that runs next passes, so the slot was put back |
| 09 | pass | 13 min | $1.98 | Opens, appends and closes the file on each call, under a file lock, and quotes a name with a comma (RFC 4180). Ran its tests outside the sandbox (below) |
| 10 | pass | 2 min | $0.68 | A `SCAN … MATCH` loop, and it said why it wouldn't use `KEYS`. The hook never fired |

The 9 sessions came to $32.74 at API prices, and ran from 22:34 to 23:24.

**What the run taught about the procedure:**

- **The guardrails still don't fit this base.** As in [02's re-run](#02-with-the-records-rule-2026-11-24), the
  `-pl spark-<line>` commands fail on a base without the per-Spark modules. 02 and 08 each tried `-pl` once, saw it
  fail and reported it. The other 7 never used it. Every run without `-pl` tests Spark 4.1 only.
- **Replacing `JAVA_TOOL_OPTIONS` breaks Testcontainers in the sandbox.** The sandbox sets
  `-Djava.net.preferIPv4Stack=true`. Without it, Java tries `localhost` as `::1`, which the sandbox refuses, and
  Testcontainers stops with "Could not connect to Ryuk". 09 replaced the variable, then ran its tests outside the
  sandbox. The other 8 agents appended to it, as the repository's auto-memory says. The scorer's first script
  replaced it too, so 8 acceptance blocks ran outside the sandbox. With the variable appended, 08's ran inside it.
- **Sandboxed sessions share one `$TMPDIR`.** 06 wrote its Maven log to a generic name there, 08's session overwrote
  it, and 06 nearly reported 08's failure as its own. Phase 2's runner should give each session its own temporary
  directory.
- **The sessions wrote to the shared auto-memory again,** so they didn't all start with the same memory:
  - 02 wrote a note on the module mismatch. The owner deleted it at 22:54. 07, which had it in its index, wrote it
    again at 23:13.
  - 05 corrected the test-selection syntax in the Maven note, and the owner kept the correction.
  - 06 wrote a note on the shared `$TMPDIR`, and 07 one on Redis applying no ACL to `HELLO`.
  - At 22:41 the owner removed the paragraph that 02's re-run had added to the flake note. 01, 02, 04 and 05 started
    before that.
  - So 07 started with the module note in its index, 09 with 06's note, and 10 with 06's note and both of 07's.

  Phase 2's runner should give each session an empty memory.
