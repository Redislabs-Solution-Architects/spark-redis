# Phase 0 exit review

> Reviews [`phase-0.md`](phase-0.md)'s exit criteria against what is merged on `spike/phase-0` at `88f9d67` and
> what CI and the GitHub API report. Written by an agent on 2026-11-22. **The owner signs off below.**
>
> **Updated twice the same day:**
>
> - with the eval baseline (criterion 5);
> - after PRs #14–#31 merged, up to `2dc56ba`: criteria 1 and 7, one measurement, and open items 4, 5 and 7–10.

## Verdict

**Phase 0 can't exit yet: 7 of 8 criteria are met, and 1 waits on the owner:** the `backlog.md` sign-off.

Criterion 8 counts as met because the owner decided Redis Cloud doesn't need its own RESP3 check (see the table).

Four non-blocking items also need follow-up:

- the published package hasn't been seen directly (criterion 6)
- `src/main` has changed in Phase 0, which the plan and CLAUDE.md rule 1 don't allow (open item 7)
- the redirected-write cluster tests are flaky on Redis 8.8 and 8.12-rc3 in CI (open item 8)
- the Redis password is written to the logs (open item 9)

## Exit criteria

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | `./mvnw verify` passes in CI on Redis 8.2, 8.6 and 8.10, and the quarantine list has been reviewed | **Met** | The required `Test (Redis 8.2 / 8.6 / 8.10, JDK 8)` jobs passed in CI runs 45080543075 (PR #11), 45080785634 (`spike/phase-0` after #11) and 45081856280 (PR #12), taking about 3½–4 minutes each. The run for `88f9d67` (after #12) was still in progress at review time. No test fails, so there is no quarantine list to review ([findings](phase-0-findings.md#a3-the-existing-suite-on-redis-8)). **Since then:** PR #16 made 8.6 the only line a PR must pass; 8.2, 8.8 and 8.10 run after each merge and may fail (`versions.yml`). PR #25's 21 intentionally failing tests made the required job fail at `85cd410` until PR #29 fixed the writers. Run 45112304933 on `2dc56ba` passed 8.6, 8.2 and 8.10, all 436 tests each; 8.8 failed one flaky test (open item 8) |
| 2 | Golden write and read fixtures are committed and pass on all three lines, standalone and cluster | **Met** | 54 files under `src/test/resources/golden/`: 27 write scenarios, 21 read results and 5 datasets. Captured on 8.2.11 and passing unchanged on 8.6.8 and 8.10.3 in both standalone and cluster (PR #8). They run in every CI test job. The owner approved them as captured |
| 3 | Legacy fixtures and their README are committed | **Met** | 8 files in `src/test/resources/legacy/`. The README names the producing build: spark-redis `3.1.0-SNAPSHOT` at `37c855d`, Scala 2.12, Spark 3.2.1, Java 8 |
| 4 | The benchmark baseline (CSV and environment notes) is committed | **Met, with a caveat** | `doc/modernization/benchmarks/baseline-3.x.csv` has 60 timings, and `baseline-3.x.md` describes the environment. The machine wasn't idle (load average about 9–14), and many steps vary by 20–90% between runs. The notes say so. Re-measure on an idle machine before any number is used as a gate |
| 5 | `CLAUDE.md`, `REVIEW.md`, the hooks, the verifier, the `golden-layout` skill and 10 evals are in place, and the eval baseline is recorded | **Met** | Everything is in place (PR #7): `CLAUDE.md`, `REVIEW.md`, `.claude/settings.json` with `protect-paths.sh` and `after-edit.sh`, `.claude/agents/verifier.md`, `.claude/skills/golden-layout/SKILL.md`, and 10 tasks in `evals/tasks/` (01 and 09 fixed in PR #10). **The baseline is recorded in `evals/README.md`: 8 of 9 tasks pass** at `eval-base-phase-0`, with 03 not applicable. Task 02 failed on duplicated Redis line lists; PR #18 fixed them, and 02 passes on the new guardrails |
| 6 | A snapshot has been published to GitHub Packages | **Met, not seen directly** | The `publish-snapshot` runs 45076832942 (from a temporary branch) and 45077405383 (from `spike/phase-0` at `d90cdaf`) both succeeded, and the deploy step ended in `BUILD SUCCESS` for `spark-redis_2.12-3.1.0-SNAPSHOT`. The agent's `gh` token lacks `read:packages`, so the owner should confirm the package appears on the fork's Packages page |
| 7 | `backlog.md` is confirmed | **Waiting on the owner** | `doc/modernization/backlog.md` has 117 rows. Six of them note a 3.x fix that merged during Phase 0 (#33, #267, #347, #351, #358, #398). **The sign-off line is blank** |
| 8 | The RESP3 check is recorded for the latest Redis Software, and for Redis Cloud if a database was provided | **Met** | Redis Software 8.2.6-58, with a default 8.8 database: `HELLO 3` returned `proto=3` ([findings](phase-0-findings.md#a5-resp3-on-managed-redis)). **Redis Cloud was skipped by owner decision (2026-11-22)**: it's assumed to accept RESP3 because the latest Redis Software does. No Redis Cloud database was provided |

## Measurements

These are the measures the playbook asks for (see `phase-0.md`, "Measurement").

| Measure | Value | Notes |
|---|---|---|
| Time from plan approval to exit sign-off | Not yet: **about 3 h 50 min** so far | The plan was approved at `72e5bbd` (2026-11-22 19:25 UTC), and this review was written at 23:17 UTC |
| First-pass merge rate of Phase 0 PRs | **10 of 11** merged PRs had no commits after opening | Only #6 was rebased after opening, to sit on top of #7. #5 was a throwaway proof PR and was closed |
| Follow-up PRs that fixed earlier merged Phase 0 work | **3 of lane A's 7 PRs** | #2 changed A1's fixed ports to dynamic ports, at the owner's request. #3 fixed `-Dredis.image` never reaching the test JVM, a bug in A1's harness. #4 did the same for `golden.update`. (#9's JaCoCo fix repaired a pre-existing 3.x bug, not Phase 0 work) |
| PRs merged after this review | **18** (#14–#31) | 13 came out of the eval sessions and the sessions they started, at the owner's request; 5 came from this session (#18, #27, #29, #30, #31) |
| Code-owner reviews on GitHub | **0 formal reviews** across 11 merged PRs | The owner approved each merge in chat. The median time from opening to merge was about 3 minutes. The playbook's code-owner gate was exercised, but not recorded on GitHub |
| Quarantined tests | **0** | 215 of 215 tests pass on every line |
| Eval baseline pass rate | **8 of 9** (89%) | Claude Opus 5.5 at `eval-base-phase-0`. Owner interventions: 0. See `evals/README.md` |
| Hook blocks | **0** across the 9 eval runs | Each hook appends a block to `.claude/hook-blocks.log`; a test block confirmed that it does. No eval agent tried a blocked edit or command |

## Open items

1. **Done: the eval baseline** is recorded in `evals/README.md` (criterion 5).
2. **Owner: confirm `backlog.md`** and sign it (criterion 7).
3. **Owner: confirm the snapshot** appears on the fork's Packages page (criterion 6).
4. **Done: the 8.12-rc3 preview job finishes again** (PR #24).
   - **The cause:** Redis 8.12 refused to start the test cluster without TLS on its bus, and the harness repeated
     the failed start for every cluster suite.
   - **Now:** the job finishes in about 3½ minutes. It fails "runs on the Redis line that redis.image names",
     because `redis:8.12-rc3` reports server version 8.11.242. The owner chose to keep that check.
   - **Its other failures:** see open item 8.
5. **CLAUDE.md lessons.** The playbook says to add a line to `CLAUDE.md` for each repeated mistake. Four candidates
   from Phase 0:
   - *"scalatest-maven-plugin doesn't pass `-D` properties to the test JVM; add new properties to both
     `systemProperties` blocks in `pom.xml`."* This came up twice, for `redis.image` and `golden.update`.
   - *"Wait for CI to finish before merging."* PRs #11 and #12 were merged with their test jobs still running, and
     so was #31.
   - *"A failing-first test merges together with its fix."* PR #25 merged 21 failing tests on their own, and every
     PR's required job failed until PR #29.
   - *"Before pushing to a PR's branch, check that the PR is still open."* An agent pushed #29's cleanup after #29
     had merged, so the cleanup needed a PR of its own (#31).
6. **The test TLS certificate: fixed in this PR**, at the owner's request. `redis.crt` expired in 2021. It is now
   re-issued by the same test CA and valid until 2030-05-13, with subject alternative names `localhost`,
   `127.0.0.1` and `::1`, ready for Phase 1's hostname verification. See finding 3.
7. **`src/main` changed during Phase 0.** After this review, the owner merged connector changes, most of which grew
   out of the evals:
   - `client.name` (#398, PR #21)
   - parse errors that name the key, field, value and type (#358, PR #20)
   - `write.mode` (#351, PR #23)
   - `MSETEX` for binary rows with a TTL, where the server has it (PR #26)
   - every writer following `MOVED` and `ASK` (#347): `toRedisKV` in PRs #19 and #22, the others in PR #29

   The plan and CLAUDE.md rule 1 say `src/main` doesn't change in Phase 0. **Owner:** either record these as
   approved exceptions, or amend rule 1. Still open from #347: `write.mode = replace` and binary rows with a TTL
   don't follow redirects yet. They fail the job instead of losing rows.

   Also, `redisFunctions.scala` and `PipelineUtils.scala` were reformatted whole, in a commit of their own (PRs #19
   and #22). The after-edit hook formats every Scala file an agent touches, so the first edit to a file that was
   never formatted reformats all of it, which rule 7 ("format only what you edit") doesn't anticipate.
8. **The redirected-write cluster tests are flaky on Redis 8.8 and 8.12-rc3.** In run 45112304933 on `2dc56ba`,
   with 8.6, 8.2 and 8.10 passing:
   - **8.8:** `ListRedirectedWriteClusterSuite`'s "toRedisLISTs … after its slot moved" failed in its own check.
     The node it had just moved the slot to answered `MOVED`, so the write itself succeeded, but by then the slot's
     owner had changed again.
   - **8.12-rc3:** "toRedisLISTs" and "toRedisByteLISTs … after its slot moved" failed with "5 writes were still
     redirected after 5 redirects", which is what nodes that disagree about the slot would cause. The same test
     failed the same way in PR #29's run.

   `ClusterSlots.moveSlot` waits until every primary agrees on the new owner, yet the owner can still change
   afterwards. Config epochs, with the same slot moving back and forth within seconds, are a likely cause, but this
   is unconfirmed. Find the cause before 8.8 or 8.12 becomes a line PRs must pass.
9. **The Redis password is written to the logs.** `RedisSourceRelation` logs `redisConfig.initialHost` at INFO,
   and `RedisEndpoint`'s `toString` includes the password. Eval 04 found it; it goes with finding 4 (redaction).
   CLAUDE.md rule 8 says never to log a password. It's a small fix.
10. **Done: the failing-first rule** (PRs #30 and #31). The PR that fixes a bug now removes its test's line, and
    `scripts/check-failing-first.sh` fails a PR that also changes that test. The list is empty.

## Sign-off

When items 2 and 3 are done, and the owner has decided item 7, the owner signs here to close Phase 0 and approve
starting the Phase 1 plan.

  Owner: ____________________  Date: ____________
