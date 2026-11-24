# Phase 1 exit review

> Reviews [`phase-1.md`](phase-1.md)'s exit criteria against what is merged on `spike/phase-1` at `5e7a6a0` (PR #53)
> and what CI and the GitHub API report. Written by an agent on 2026-11-24. **The owner signs off below.**
>
> **Updated the same day** after the owner's decisions on open items 2, 3, 5 and 6: the snapshot is republished, the
> legacy README wording is approved, GitHub's generated notes replace Release Drafter, and CI runs nightly and by hand.

## Verdict

**Phase 1 can't exit yet: 4 of its 5 criteria are met.**

- **Met:** the full matrix passes in one run after a merge, the golden and legacy fixtures pass unchanged (the legacy ones on standalone only), snapshots
  publish with their SBOMs, and DStreams are gone.
- **Not met:** the Guardrails criterion. `CLAUDE.md`, `REVIEW.md`, the verifier and the evals' instructions are
  updated for Phase 1, but the Phase 1 eval baseline hasn't run, so no pass rate is recorded. It needs the
  `eval-base-phase-1` tag first (open item 1).

The rest of the roadmap's Phase 1, as Amendment 1 narrowed it, is built:

- one artifact per Spark minor (4.1, 4.2, 4.3) on Scala 2.13 and JDK 17;
- the CI matrix, on JDK 17 and 21 and Redis 8.6;
- the release workflow, which publishes only snapshots in Phase 1.

The roadmap's two exit criteria are met: the golden-layout and existing tests pass across the full matrix, and
snapshots of every per-Spark artifact are published.

## Exit criteria

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | For each Spark line, `./mvnw verify` passes on the full matrix (D1) in one run after a merge, with nothing quarantined | **Met** | CI run [45448428816](https://github.com/Redislabs-Solution-Architects/spark-redis/actions/runs/45448428816), after #53 merged at `5e7a6a0`: all 6 jobs passed, Spark 4.1, 4.2 and 4.3, each on JDK 17 and 21, with Redis 8.6. The run after #49 ([45319469308](https://github.com/Redislabs-Solution-Architects/spark-redis/actions/runs/45319469308)) also passed all 6. `quarantine.txt` has no entries. One test cancels itself on every line: `SchemaEncodingTest`'s Spark 3.1/3.2 case, which has no fixture written by Spark 4. It isn't quarantined. Local full runs of `./mvnw -B verify -pl spark-<line>` had 608 tests pass, 0 fail and 1 canceled on each line: `spark-4.1` and `spark-4.3` at `5e7a6a0` on JDK 17, and `spark-4.2` on JDK 21 with #53's change, which is the same code. Locally, `ConnectionSSLUtilsTest` doesn't load, because Claude's sandbox can't read `src/test/resources/tls/*.key`. CI runs it |
| 2 | The golden write and read fixtures and the legacy fixtures pass unchanged on every line, in standalone and cluster | **Met, with a caveat** | `git diff 987aba1 5e7a6a0 -- src/test/resources/golden src/test/resources/legacy` is empty: the 54 golden files and the 8 legacy files are the ones Phase 0 signed off. `WriteLayout` and `ReadResult` run in a standalone and a cluster suite each, in every job of the run above. **Caveat:** `LegacyFixturesSuite` runs on standalone only. The legacy blobs are values, so the topology doesn't change how they're read, but the criterion says both. The owner's call whether standalone is enough |
| 3 | `publish-snapshot.yml` publishes snapshots of the three per-Spark artifacts, with the SBOM, and `release.yml`'s dry run passes (B4, narrowed) | **Met** | The snapshot run [45323531030](https://github.com/Redislabs-Solution-Architects/spark-redis/actions/runs/45323531030) from `c2f913e` (#50's merge) succeeded. The public package pages list build `4.0.0-M1-20261124.040210-1` of `com.redis:spark-redis-spark-4.1_2.13`, `-4.2_2.13` and `-4.3_2.13`, each with the jar, sources jar, javadoc jar, jar-with-dependencies, pom and `-cyclonedx.json` SBOM, plus the parent pom. `release.yml`'s dry runs passed on both of #50's commits ([45322697211](https://github.com/Redislabs-Solution-Architects/spark-redis/actions/runs/45322697211), [45323184486](https://github.com/Redislabs-Solution-Architects/spark-redis/actions/runs/45323184486)): 6 test jobs, then the release job. The first uploaded the 9.8 MB artifact `release-4.0.0-M1-dry-run`. After this review, the snapshot was republished from `5e7a6a0` (open item 2) |
| 4 | Nothing in `src/` imports `org.apache.spark.streaming` | **Met** | `git grep org.apache.spark.streaming 5e7a6a0 -- src/` finds nothing. A2 (#45) removed DStreams |
| 5 | `CLAUDE.md`, `REVIEW.md` and the evals are updated for Phase 1, and the eval pass rate is recorded | **Not met** | X1 (#43) updated `CLAUDE.md` (86 lines), `REVIEW.md`, the `verifier` agent and `evals/README.md`, and added the `new-spark-line` skill. Later PRs kept them current: #45, #47, #48, #49 and #51 for `CLAUDE.md`, #47 for `REVIEW.md`'s Phase 1 passes, and #48 and #51 for the verifier. **Missing:** `evals/README.md`'s results table has only Phase 0's baseline (8 of 9). The `eval-base-phase-1` tag it calls for doesn't exist, so the Phase 1 baseline hasn't run |

**Moved by roadmap Amendment 1**, so not reviewed here: the benchmark comparison (B3) and 4.0.0-M1 move to Phase 2,
the RESP3-only and legacy-command criteria to Phase 2a and 2e, and `branch-3.x` with `spark-redis-migrate` (lane C)
to Phase 2e (decision A1).

## What Phase 1 built

| Roadmap item | Done in | Status |
|---|---|---|
| 1. Start from the fork's code | `spike/phase-1`, cut from `spike/phase-0` after the plan merged (#42) | Done |
| 2. Scala 2.13 and JDK 17 | A1 (#44) | Done |
| 3. Spark 4 compile | A1 (#44) for 4.1; B1 (#48) adds 4.2 and 4.3 | Done |
| 4. Jedis 8, RESP3 and the legacy commands | — | Dropped by Amendment 1 (#47): Phase 2a's `RedisExecutor` replaces that code |
| 5. Remove DStreams; deprecate the RDD API | A2 (#45) | Done. No 4.x release carries the RDD API (Amendment 1) |
| 6. One artifact per Spark minor; the CI matrix | B1 (#48), B2 (#49) | Done. JDK 17 and 21 on every line; Redis 8.6 only for now (owner decision) |
| 7. `branch-3.x` | — | Moved to Phase 2e (#51, decision A1) |
| 8. Publish 4.0.0-M1 | B4 (#50) | Narrowed to the release workflow and snapshots. 4.0.0-M1 is Phase 2's release |

Also merged:

- **#43:** X1, the guardrails for Phase 1.
- **#46:** the flaky "load unfiltered binary keys with hashes" test. It now reads with the schema the binary model
  fills (the owner's decision).
- **#52:** upstream #436, #438 and #439, ported to the 3.x baseline, at the owner's request.
- **#53:** #52's port failed on JDK 21, which reports a negative array length differently. This fixes it.

## Measurements

The plan's list, from the roadmap's "How we measure it":

| Measure | Value | Notes |
|---|---|---|
| Time from plan approval to exit sign-off | **Not signed yet.** 18 h 31 min from the plan's merge to the last PR | The plan merged with #42 at 2026-11-23 22:54 UTC; #53 merged at 2026-11-24 17:25 UTC. Phase 0 took 26 h 48 min |
| How much the plan was reworked | **4 merges** changed it after approval | **#47** (Amendment 1): dropped the Jedis 8 and RESP3 port (A3, A4), and moved the benchmarks (B3) and the M1 release to Phase 2. **#49:** the owner's decisions on JDKs (17 and 21 only) and Redis (8.6 only, for now), and no nightly run. **#50:** B4 as built, with a tag-triggered release and a dry run on PRs. **#51:** lane C moved to Phase 2e |
| First-pass merge rate | **10 of 11** PRs (#43–#53) had no commits after opening | Only #50 got a second commit: the manual-dispatch correction and setup-java's new input names |
| PRs merged before their CI finished | **7 of 11** in the fork; **3 of 4** upstream | Fork: #44, #45, #48, #49, #50, #52, #53. Upstream: #437, #438, #439. The target was none, and Phase 0 had nine. All of them later passed except one PR check: #45's failed after the merge, probably on the flaky test that #46 fixed (its log isn't readable here), and the run on #45's merge commit passed. #50 merged while the release dry run for its second commit was still running; it passed |
| Repeat mistakes that needed a `CLAUDE.md` line | **0** | `CLAUDE.md` already says "Wait for CI to finish before merging", and the merges above still happened, because the owner merges by hand (open item 4). The agent repeated two shell mistakes, both caught before anything merged. It committed the sandbox's phantom deletions of the `.key` files in a format-only commit, twice. And zsh read `$var:x` as a modifier, twice. They're sandbox and shell habits, so they went into the agent's own notes rather than `CLAUDE.md` |
| Concurrent sessions in flight | **1** | Every step ran in one session, one after another. The plan allowed three lanes at once |
| Eval pass rate, start and end of Phase 1 | **Not measured** | Criterion 5. Phase 0's baseline was 8 of 9 |
| Benchmarks against the re-measured baseline | **n/a** | B3 moved to Phase 2 (Amendment 1) |
| 3.x fixes found during the port and sent upstream | **1**: upstream #439 | Starting from a replica, the master lost the configured timeout. Found during the port, merged upstream on 2026-11-24, and ported back with #52. #53's JDK 21 fix doesn't go upstream: Spark 3.5 supports JDK 8, 11 and 17 |
| Quarantined tests | **0** | As in Phase 0 |

## Open items

1. **Record the Phase 1 eval baseline (blocks criterion 5).** The owner tags A1's merge commit, `13885f1` (#44), as
   `eval-base-phase-1`, as `evals/README.md` describes:

   ```bash
   git tag eval-base-phase-1 13885f1 && git push https://github.com/Redislabs-Solution-Architects/spark-redis.git eval-base-phase-1
   ```

   Then the 10 tasks run once from it on JDK 17 (X1), and the results go in `evals/README.md`.
   - The plan also asks for the pass rate at the end of Phase 1: the same tasks with the guardrails at `5e7a6a0`.
   - A task whose setup no longer applies on the Phase 1 base is fixed first, as the README says.
2. **Done: the snapshot is republished.** The first one was from `c2f913e`, before #52 and #53. Run
   [45454956915](https://github.com/Redislabs-Solution-Architects/spark-redis/actions/runs/45454956915) published
   build `4.0.0-M1-20261124.180147-2` from `5e7a6a0`, so the package matches the code Phase 1 ends with.
3. **Approved: the legacy fixtures README wording (rule 6).** The owner approved #51's wording for
   `src/test/resources/legacy/README.md` on 2026-11-24. Agents can't edit that folder, so the owner applies it, and
   this PR commits it.
4. **Merges before CI.** 7 of 11 fork PRs merged with checks still running. If merges should wait, the owner can:
   - require the `Test` check on `spike/phase-1` with branch protection (it's unprotected so far, the owner's choice);
   - or turn on auto-merge for each PR, so GitHub merges it when the checks pass.
5. **Decided: GitHub's generated release notes** (the owner, 2026-11-24). Release Drafter, which ran only on pushes
   to `master` and drafted 3.x releases, is removed: from `spike/phase-1` in this PR, and from the fork's `master` in
   `95a488f`. Spec §2.13, the roadmap and the plan say GitHub's notes. A `.github/release.yml` can group them by PR
   label once Phase 2's PRs carry labels. Release Drafter's one draft, "Version 0.0.1🌈" (`v0.0.1`, 2026-11-22),
   is still in the fork's releases.
6. **Done: CI runs nightly and by hand.** The fork's `master` has a small `ci.yml` (`f785604`). It has no push or
   pull-request trigger, so upstream syncs start nothing, and nightly at 03:00 UTC it dispatches `spike/phase-1`'s
   `ci.yml`. A manual dispatch on `spike/phase-1` runs that copy directly. Dispatching `master`'s copy by hand, as
   the schedule does, started run
   [45455720881](https://github.com/Redislabs-Solution-Architects/spark-redis/actions/runs/45455720881) on `5e7a6a0`:
   all 6 matrix jobs passed. **The benchmark job ran for the first time and failed** after 5 minutes. It may fail
   without failing the run. Its `benchmarks.csv` (584 bytes) uploaded, so some benchmarks ran first. The cause
   isn't known yet: CI's logs aren't readable here, and B3, which owns the benchmarks, is in Phase 2.
7. **Carried into Phase 2:**
   - the other supported Redis lines (8.2–8.10) return to `redis_secondary` before 4.0.0-M1;
   - D4 (a GPG signing key) and D7 (the benchmark baseline) apply to 4.0.0-M1.

## Sign-off

- [ ] I have reviewed each criterion and the open items, and Phase 1 exits.
  Owner: ______  Date: ______
