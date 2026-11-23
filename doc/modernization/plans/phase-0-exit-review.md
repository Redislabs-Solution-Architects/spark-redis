# Phase 0 exit review

> Reviews [`phase-0.md`](phase-0.md)'s exit criteria against what is merged on `spike/phase-0` at `88f9d67` and
> what CI and the GitHub API report. Written by an agent on 2026-11-22. **The owner signs off below.**
>
> **Updated the same day** with the eval baseline (criterion 5), and with the `src/main` changes merged since
> (open item 7).

## Verdict

**Phase 0 can't exit yet: 7 of 8 criteria are met, and 1 waits on the owner:** the `backlog.md` sign-off.

Criterion 8 counts as met because the owner decided Redis Cloud doesn't need its own RESP3 check (see the table).

Three non-blocking items also need follow-up:

- the 8.12-rc3 preview job runs much longer than it should
- the published package hasn't been seen directly
- `src/main` has changed in Phase 0, which the plan and CLAUDE.md rule 1 don't allow (open item 7)

## Exit criteria

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | `./mvnw verify` passes in CI on Redis 8.2, 8.6 and 8.10, and the quarantine list has been reviewed | **Met** | The required `Test (Redis 8.2 / 8.6 / 8.10, JDK 8)` jobs passed in CI runs 45080543075 (PR #11), 45080785634 (`spike/phase-0` after #11) and 45081856280 (PR #12), taking about 3½–4 minutes each. The run for `88f9d67` (after #12) was still in progress at review time. No test fails, so there is no quarantine list to review ([findings](phase-0-findings.md#a3-the-existing-suite-on-redis-8)) |
| 2 | Golden write and read fixtures are committed and pass on all three lines, standalone and cluster | **Met** | 54 files under `src/test/resources/golden/`: 27 write scenarios, 21 read results and 5 datasets. Captured on 8.2.11 and passing unchanged on 8.6.8 and 8.10.3 in both standalone and cluster (PR #8). They run in every CI test job. The owner approved them as captured |
| 3 | Legacy fixtures and their README are committed | **Met** | 8 files in `src/test/resources/legacy/`. The README names the producing build: spark-redis `3.1.0-SNAPSHOT` at `37c855d`, Scala 2.12, Spark 3.2.1, Java 8 |
| 4 | The benchmark baseline (CSV and environment notes) is committed | **Met, with a caveat** | `doc/modernization/benchmarks/baseline-3.x.csv` has 60 timings, and `baseline-3.x.md` describes the environment. The machine wasn't idle (load average about 9–14), and many steps vary by 20–90% between runs. The notes say so. Re-measure on an idle machine before any number is used as a gate |
| 5 | `CLAUDE.md`, `REVIEW.md`, the hooks, the verifier, the `golden-layout` skill and 10 evals are in place, and the eval baseline is recorded | **Met** | Everything is in place (PR #7): `CLAUDE.md`, `REVIEW.md`, `.claude/settings.json` with `protect-paths.sh` and `after-edit.sh`, `.claude/agents/verifier.md`, `.claude/skills/golden-layout/SKILL.md`, and 10 tasks in `evals/tasks/` (01 and 09 fixed in PR #10). **The baseline is recorded in `evals/README.md`: 8 of 9 tasks pass** at `eval-base-phase-0`, with 03 not applicable. Task 02 failed on duplicated Redis line lists; PR #18 fixed them, and 02 passes on the new guardrails |
| 6 | A snapshot has been published to GitHub Packages | **Met, not seen directly** | The `publish-snapshot` runs 45076832942 (from a temporary branch) and 45077405383 (from `spike/phase-0` at `d90cdaf`) both succeeded, and the deploy step ended in `BUILD SUCCESS` for `spark-redis_2.12-3.1.0-SNAPSHOT`. The agent's `gh` token lacks `read:packages`, so the owner should confirm the package appears on the fork's Packages page |
| 7 | `backlog.md` is confirmed | **Waiting on the owner** | `doc/modernization/backlog.md` has 117 rows. **The sign-off line is blank** |
| 8 | The RESP3 check is recorded for the latest Redis Software, and for Redis Cloud if a database was provided | **Met** | Redis Software 8.2.6-58, with a default 8.8 database: `HELLO 3` returned `proto=3` ([findings](phase-0-findings.md#a5-resp3-on-managed-redis)). **Redis Cloud was skipped by owner decision (2026-11-22)**: it's assumed to accept RESP3 because the latest Redis Software does. No Redis Cloud database was provided |

## Measurements

These are the measures the playbook asks for (see `phase-0.md`, "Measurement").

| Measure | Value | Notes |
|---|---|---|
| Time from plan approval to exit sign-off | Not yet: **about 3 h 50 min** so far | The plan was approved at `72e5bbd` (2026-11-22 19:25 UTC), and this review was written at 23:17 UTC |
| First-pass merge rate of Phase 0 PRs | **10 of 11** merged PRs had no commits after opening | Only #6 was rebased after opening, to sit on top of #7. #5 was a throwaway proof PR and was closed |
| Follow-up PRs that fixed earlier merged Phase 0 work | **3 of lane A's 7 PRs** | #2 changed A1's fixed ports to dynamic ports, at the owner's request. #3 fixed `-Dredis.image` never reaching the test JVM, a bug in A1's harness. #4 did the same for `golden.update`. (#9's JaCoCo fix repaired a pre-existing 3.x bug, not Phase 0 work) |
| Code-owner reviews on GitHub | **0 formal reviews** across 11 merged PRs | The owner approved each merge in chat. The median time from opening to merge was about 3 minutes. The playbook's code-owner gate was exercised, but not recorded on GitHub |
| Quarantined tests | **0** | 215 of 215 tests pass on every line |
| Eval baseline pass rate | **8 of 9** (89%) | Claude Opus 5.5 at `eval-base-phase-0`. Owner interventions: 0. See `evals/README.md` |
| Hook blocks | **0** across the 9 eval runs | Each hook appends a block to `.claude/hook-blocks.log`; a test block confirmed that it does. No eval agent tried a blocked edit or command |

## Open items

1. **Done: the eval baseline** is recorded in `evals/README.md` (criterion 5).
2. **Owner: confirm `backlog.md`** and sign it (criterion 7).
3. **Owner: confirm the snapshot** appears on the fork's Packages page (criterion 6).
4. **The 8.12-rc3 preview job runs too long.** In every run it stayed on its Test step for 14 minutes or more, while
   the required jobs finish in about 4. One run was cancelled when a newer push superseded it. The job is allowed to
   fail and times out at 45 minutes. Read its log once a run ends, to see whether it's a hang on the release
   candidate or a harness problem.
5. **CLAUDE.md lessons.** The playbook says to add a line to `CLAUDE.md` for each repeated mistake. Two candidates
   from Phase 0:
   - *"scalatest-maven-plugin doesn't pass `-D` properties to the test JVM; add new properties to both
     `systemProperties` blocks in `pom.xml`."* This came up twice, for `redis.image` and `golden.update`.
   - *"Wait for CI to finish before merging."* PRs #11 and #12 were merged with their test jobs still running.
6. **The test TLS certificate: fixed in this PR**, at the owner's request. `redis.crt` expired in 2021. It is now
   re-issued by the same test CA and valid until 2030-05-13, with subject alternative names `localhost`,
   `127.0.0.1` and `::1`, ready for Phase 1's hostname verification. See finding 3.
7. **`src/main` changed during Phase 0.** After this review, the owner merged connector changes that grew out of
   the evals:
   - `client.name` (#398, PR #21)
   - `toRedisKV` following `MOVED` and `ASK` (PRs #19 and #22)
   - `write.mode` (#351, PR #23)

   PRs #20 (#358 parse errors) and #26 (`MSETEX`) are open. The plan and CLAUDE.md rule 1 say `src/main` doesn't
   change in Phase 0. So either record these as owner-approved exceptions, or amend rule 1 before more land.

   Also, `redisFunctions.scala` and `PipelineUtils.scala` were reformatted whole, in a commit of their own (PRs #19
   and #22). The after-edit hook formats every Scala file an agent touches, so the first edit to a file that was
   never formatted reformats all of it, which rule 7 ("format only what you edit") doesn't anticipate.

## Sign-off

When items 1–3 are done, the owner signs here to close Phase 0 and approve starting the Phase 1 plan.

  Owner: ____________________  Date: ____________
