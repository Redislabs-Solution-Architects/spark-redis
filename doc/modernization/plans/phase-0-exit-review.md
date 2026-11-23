# Phase 0 exit review

> Reviews [`phase-0.md`](phase-0.md)'s exit criteria against what is merged on `spike/phase-0` at `88f9d67` and
> what CI and the GitHub API report. Written by an agent on 2026-11-22. **The owner signs off below.**
>
> **Updated three times the same day:**
>
> - with the eval baseline (criterion 5);
> - after PRs #14–#31 merged, up to `2dc56ba`: criteria 1 and 7, one measurement, and open items 4, 5 and 7–10;
> - after the owner amended CLAUDE.md rule 1 (open item 7).
>
> **Updated again on 2026-11-23** at `b794947`, after PRs #32–#38 and upstream PRs #416–#429. The fork now merges
> upstream (`RedisLabs/spark-redis`) instead of diverging from it, builds with Spark 3.5.10 on Scala 2.12 and 2.13,
> and most of Phase 0's 3.x fixes are in upstream as well. "Since then" in each row marks what changed.
>
> **And at 21:20 UTC,** after upstream PRs #430–#434: the three 3.x fixes this review listed for a clean Phase 1
> start are in upstream, and PR #40 merges them into `spike/phase-0`. See
> [3.x fixes before Phase 1](#3x-fixes-before-phase-1).

## Verdict

**Phase 0 can't exit yet: 7 of 8 criteria are met, and 1 waits on the owner:** the `backlog.md` sign-off.

Criterion 8 counts as met because the owner decided Redis Cloud doesn't need its own RESP3 check (see the table).

The earlier non-blocking items are settled:
- the flaky redirected-write tests are fixed (open item 8);
- the password no longer reaches the logs (open item 9);
- the 3.x fixes the owner wanted before Phase 1 are in upstream, and in the fork with PR #40. The owner's goal is a
  clean 3.x starting point, with today's features working and none added; see
  [3.x fixes before Phase 1](#3x-fixes-before-phase-1).

One remains: the published package hasn't been seen directly, and predates the move to Spark 3.5 (criterion 6).

## Exit criteria

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | `./mvnw verify` passes in CI on Redis 8.2, 8.6 and 8.10, and the quarantine list has been reviewed | **Met** | The required `Test (Redis 8.2 / 8.6 / 8.10, JDK 8)` jobs passed in CI runs 45080543075 (PR #11), 45080785634 (`spike/phase-0` after #11) and 45081856280 (PR #12), taking about 3½–4 minutes each. The run for `88f9d67` (after #12) was still in progress at review time. No test fails, so there is no quarantine list to review ([findings](phase-0-findings.md#a3-the-existing-suite-on-redis-8)). **Since then:** PR #16 made 8.6 the only line a PR must pass; 8.2, 8.8 and 8.10 run after each merge and may fail (`versions.yml`). PR #25's 21 intentionally failing tests made the required job fail at `85cd410` until PR #29 fixed the writers. Run 45112304933 on `2dc56ba` passed 8.6, 8.2 and 8.10, all 436 tests each; 8.8 failed one flaky test (open item 8). **Since then:** #33 took 8.8, 8.10 and 8.12 out of CI, at the owner's request, so 8.10 isn't run any more: PRs must pass 8.6, and 8.2 runs after each merge, allowed to fail. #35 moved the fork to upstream's Spark 3.5.10 and added Scala 2.13, so the required checks are `Test (Redis 8.6, JDK 8, Scala 2.12)` and `… Scala 2.13`. From #35 until #38, only tests that waited on the owner failed: the legacy fixtures (fixed by #37) and the golden write layouts (re-captured in #38). Run 45252979535 on `b794947` (after #38) passed all three jobs: 8.6 on Scala 2.12 and on 2.13, and 8.2. That is the first fully green run on `spike/phase-0` since #35 |
| 2 | Golden write and read fixtures are committed and pass on all three lines, standalone and cluster | **Met** | 54 files under `src/test/resources/golden/`: 27 write scenarios, 21 read results and 5 datasets. Captured on 8.2.11 and passing unchanged on 8.6.8 and 8.10.3 in both standalone and cluster (PR #8). They run in every CI test job. The owner approved them as captured. **Since then:** the owner re-captured 16 write fixtures in #38, on 8.2.11, after two deliberate upstream changes. The 15 `df-*` scenarios store the schema key as JSON (upstream #416), and in `stream-consumer-group` neither group has consumers any more and `spark-source`'s `entries-read` is `null` (upstream reads batches by offset range, #412). An agent checked that no other key changed |
| 3 | Legacy fixtures and their README are committed | **Met** | 8 files in `src/test/resources/legacy/`. The README names the producing build: spark-redis `3.1.0-SNAPSHOT` at `37c855d`, Scala 2.12, Spark 3.2.1, Java 8. **Since then:** the fixtures are tested through upstream's schema migration (#429), in #37. The schema blobs are read with its version-independent reader, and the table loads after `SchemaMigration`. `LegacyFixturesSuite` passes 4/4 on Spark 3.5.10 (Scala 2.12 and 2.13) and on Spark 3.2.1. The fixtures didn't change |
| 4 | The benchmark baseline (CSV and environment notes) is committed | **Met, with a caveat** | `doc/modernization/benchmarks/baseline-3.x.csv` has 60 timings, and `baseline-3.x.md` describes the environment. The machine wasn't idle (load average about 9–14), and many steps vary by 20–90% between runs. The notes say so. Re-measure on an idle machine before any number is used as a gate |
| 5 | `CLAUDE.md`, `REVIEW.md`, the hooks, the verifier, the `golden-layout` skill and 10 evals are in place, and the eval baseline is recorded | **Met** | Everything is in place (PR #7): `CLAUDE.md`, `REVIEW.md`, `.claude/settings.json` with `protect-paths.sh` and `after-edit.sh`, `.claude/agents/verifier.md`, `.claude/skills/golden-layout/SKILL.md`, and 10 tasks in `evals/tasks/` (01 and 09 fixed in PR #10). **The baseline is recorded in `evals/README.md`: 8 of 9 tasks pass** at `eval-base-phase-0`, with 03 not applicable. Task 02 failed on duplicated Redis line lists; PR #18 fixed them, and 02 passes on the new guardrails. **Since then:** the owner amended rule 1 (#34), and the upstream rule in "Working on this repo" now describes how this work sends fixes upstream (this PR) |
| 6 | A snapshot has been published to GitHub Packages | **Met, not seen directly** | The `publish-snapshot` runs 45076832942 (from a temporary branch) and 45077405383 (from `spike/phase-0` at `d90cdaf`) both succeeded, and the deploy step ended in `BUILD SUCCESS` for `spark-redis_2.12-3.1.0-SNAPSHOT`. The agent's `gh` token lacks `read:packages`, so the owner should confirm the package appears on the fork's Packages page. **Since then:** no snapshot has been published. `publish-snapshot.yml` runs only by hand, and the fork has moved to Spark 3.5.10 and Scala 2.13 since `d90cdaf`. Run it again (`gh workflow run publish-snapshot.yml --ref spike/phase-0`) so the package matches the code Phase 0 ends with |
| 7 | `backlog.md` is confirmed | **Waiting on the owner** | `doc/modernization/backlog.md` has 117 rows. Six of them note a 3.x fix that merged during Phase 0 (#33, #267, #347, #351, #358, #398). **The sign-off line is blank.** **Since then:** 88 of the 117 issues have been closed upstream, 80 as completed and 8 as not planned, several when the upstream PR that fixed them merged. `backlog.md` now shows each issue's upstream state, and which upstream PR carries the 3.x fix |
| 8 | The RESP3 check is recorded for the latest Redis Software, and for Redis Cloud if a database was provided | **Met** | Redis Software 8.2.6-58, with a default 8.8 database: `HELLO 3` returned `proto=3` ([findings](phase-0-findings.md#a5-resp3-on-managed-redis)). **Redis Cloud was skipped by owner decision (2026-11-22)**: it's assumed to accept RESP3 because the latest Redis Software does. No Redis Cloud database was provided |

## Measurements

These are the measures the playbook asks for (see `phase-0.md`, "Measurement").

| Measure | Value | Notes |
|---|---|---|
| Time from plan approval to exit sign-off | Not yet: **about 25 h 55 min** so far | The plan was approved at `72e5bbd` (2026-11-22 19:25 UTC). This review was first written at 23:17 UTC, and updated on 2026-11-23 at 19:50 and 21:20 UTC |
| First-pass merge rate of Phase 0 PRs | **10 of 11** merged PRs had no commits after opening | Only #6 was rebased after opening, to sit on top of #7. #5 was a throwaway proof PR and was closed. **Of #32–#38, 6 of 7** had no commits after opening; #35, the upstream merge, was refreshed twice as upstream moved |
| Follow-up PRs that fixed earlier merged Phase 0 work | **3 of lane A's 7 PRs** | #2 changed A1's fixed ports to dynamic ports, at the owner's request. #3 fixed `-Dredis.image` never reaching the test JVM, a bug in A1's harness. #4 did the same for `golden.update`. (#9's JaCoCo fix repaired a pre-existing 3.x bug, not Phase 0 work) |
| PRs merged after this review | **25** (#14–#38) | #14–#31 as before: 13 from the eval sessions and the sessions they started, at the owner's request, and 5 from this session (#18, #27, #29, #30, #31). Then #32–#38: this review, the CI lines, rule 1, the upstream merge, the slot-move fix, the legacy fixtures through the migration, and the golden re-capture |
| Code-owner reviews on GitHub | **0 formal reviews** across 11 merged PRs | The owner approved each merge in chat. The median time from opening to merge was about 3 minutes. The playbook's code-owner gate was exercised, but not recorded on GitHub. **Since then:** still none, on the fork's PRs or on the upstream PRs, which the owner merged past upstream's required review |
| Quarantined tests | **0** | 215 of 215 tests pass on every line. Still 0 |
| Fixes sent upstream | **19 upstream PRs merged** (#416–#434) | Most port this fork's Phase 0 fixes to upstream 3.x: the Testcontainers harness and Redis 7.4+ floor (#417), `scalatest-maven-plugin` 2.2.0 (#418), `client.name` (#419), the TLS certificate (#420), parse-error context (#421), `write.mode` (#423), the redirect-aware writers and `MSETEX` (#424). New upstream work: JSON schemas (#416), the JNA pin (#422), the leak-test race (#425), docs (#426), the site plugin (#427), password redaction (#428) and the schema migration (#429). **Since then:** the three 3.x fixes before Phase 1 (#430, #433, #434), the Central Publisher Portal release (#431) and the 3.1.1 install docs (#432) |
| Eval baseline pass rate | **8 of 9** (89%) | Claude Opus 5.5 at `eval-base-phase-0`. Owner interventions: 0. See `evals/README.md` |
| Hook blocks | **0** across the 9 eval runs | Each hook appends a block to `.claude/hook-blocks.log`; a test block confirmed that it does. No eval agent tried a blocked edit or command |

## Open items

1. **Done: the eval baseline** is recorded in `evals/README.md` (criterion 5).
2. **Owner: confirm `backlog.md`** and sign it (criterion 7).
3. **Owner: confirm the snapshot** appears on the fork's Packages page (criterion 6), and publish one from the current
   `spike/phase-0`.
4. **Done: the 8.12-rc3 preview job finishes again** (PR #24).
   - **The cause:** Redis 8.12 refused to start the test cluster without TLS on its bus, and the harness repeated
     the failed start for every cluster suite.
   - **Now:** the job finishes in about 3½ minutes. It fails "runs on the Redis line that redis.image names",
     because `redis:8.12-rc3` reports server version 8.11.242. The owner chose to keep that check.
   - **Its other failures:** see open item 8.
   - **Since then:** #33 took the preview line (8.12) out of CI.
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
   - *"After merging upstream, look for tests that both sides added."* Both had added the same two #358 tests to
     `HashDataframeSuite`, and ScalaTest can't load a suite with duplicate test names, so the hash DataFrame suites
     aborted (fixed in `1a3f0a8`, in #35).

   Also, "wait for CI" came up again: #38 and upstream #424 merged with their test jobs running, and so did upstream
   #430, #433 and #434, each about 5 minutes before its 9 jobs finished. All 9 passed on each.
6. **Done: the test TLS certificate**, at the owner's request, and upstream in #420. `redis.crt` expired in 2021. It is now
   re-issued by the same test CA and valid until 2030-05-13, with subject alternative names `localhost`,
   `127.0.0.1` and `::1`, ready for Phase 1's hostname verification. See finding 3.
7. **Done: `src/main` changed during Phase 0, and rule 1 now allows it.** After this review, the owner merged
   connector changes, most of which grew out of the evals:
   - `client.name` (#398, PR #21)
   - parse errors that name the key, field, value and type (#358, PR #20)
   - `write.mode` (#351, PR #23)
   - `MSETEX` for binary rows with a TTL, where the server has it (PR #26)
   - every writer following `MOVED` and `ASK` (#347): `toRedisKV` in PRs #19 and #22, the others in PR #29

   The plan and CLAUDE.md rule 1 said `src/main` doesn't change in Phase 0. **The owner amended rule 1**: in Phase 0,
   `src/main` now changes only to fix a bug, with its failing-first test, or when the owner asks for the change, and
   the golden layouts pass unchanged either way. The plan and roadmap note the amendment, and REVIEW.md asks each PR
   which case applies. The merged changes fit the amended rule:
   - **Bug fixes with failing-first tests:** #20, #22 and #29, plus the `toRedisKV` part of #19.
   - **Requested by the owner:** #21, #23 and #26 came from eval tasks the owner set, and the owner merged them.

   Still open from #347: `write.mode = replace` and binary rows with a TTL don't follow redirects yet. They fail the
   job instead of losing rows. (Checked again at `b794947`: both still send to the node the relation's topology
   names.) **Done:** upstream #430 makes both follow `MOVED` and `ASK`, and PR #40 brings it into the fork.

   **Since then:** all of these are in upstream 3.x too: #419, #421, #423 and #424.

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

   **Done (#36):** the cause was config epochs. The moved slot's old owner could still win it back over the cluster
   bus. `ClusterSlots` now bumps the new owner's epoch and waits until the move is settled and stays settled, and the
   redirected-write suites ran 20 times on 8.6 without a failure. Upstream has the same fix, in #424. 8.8 and 8.12
   are out of CI since #33, so it hasn't been confirmed on those lines.
9. **The Redis password is written to the logs.** `RedisSourceRelation` logs `redisConfig.initialHost` at INFO,
   and `RedisEndpoint`'s `toString` includes the password. Eval 04 found it; it goes with finding 4 (redaction).
   CLAUDE.md rule 8 says never to log a password. It's a small fix.

   **Done upstream (#428), and in the fork since #35:** `RedisEndpoint.toString` shows the password as redacted, and
   the password now comes from `spark.redis.password`, which Spark redacts in its UI, event logs and query plans.
   `spark.redis.auth` still works, with a deprecation warning.
10. **Done: the failing-first rule** (PRs #30 and #31). The PR that fixes a bug now removes its test's line, and
    `scripts/check-failing-first.sh` fails a PR that also changes that test. The list is empty.

11. **Done: the fork follows upstream (#35).** It merged upstream `master` three times, and builds with upstream's
    Spark 3.5.10 on Scala 2.12 and 2.13, which CI tests on every PR. Phase 1 starts from this code.
12. **Done: the legacy fixtures go through the migration (#37)**, which upstream now has (#429). See criterion 3.

## 3.x fixes before Phase 1

The owner wants a clean 3.x starting point for Phase 1: today's features keep working, with bugs fixed and no new
features. These were the fixes still open in the 3.x code at `b794947`. None blocks the exit.

**Since then:** the three fixes went upstream as PRs of their own, and PR #40 merges them into `spike/phase-0`.

- Each PR came with tests that failed on the code before it.
- Each passed the full suite locally on Redis 8.6 and 7.4, and all 9 upstream CI jobs.
- Only the optional build fixes are left.

| Fix | Where | Why it's a fix | Status |
|---|---|---|---|
| Follow `MOVED`/`ASK` in the last two writers | `write.mode = replace`, and the binary model with a `ttl` (`RedisSourceRelation.saveRows`) | The rest of #347: after a slot moves, these writes fail the job, where every other writer follows the redirect | **Done: upstream #430.** Each row's transaction is sent again to the node a redirect names. `MSETEX` is split into `SET … EX` while its slot migrates |
| Set values and TTLs in one step | The RDD writers with a `ttl`, and the hash model's default write, send `EXPIRE` after the write in the same pipeline | A reader, or a failure between the two, can leave a value without its TTL. Rule 3: `MULTI`/`EXEC` per key, as `write.mode = replace` does, or `SET … EX` for strings | **Done: upstream #433.** One `MULTI`/`EXEC` per key, per batch for the single-key writers, and per row for the hash model. Writes without a `ttl` are unchanged |
| Replace the legacy reads | `RedisRDD.scala`: `SMEMBERS` (`fromRedisSet`), `ZRANGEBYSCORE` twice (`fromRedisZRangeByScore`, `…WithScore`) | Rule 3. Same results with `SSCAN` and `ZRANGE … BYSCORE` (Redis 6.2+; the floor is 7.4); `SSCAN` doesn't block Redis on a large set | **Done: upstream #434.** A set that changes during the read may or may not show the members added or removed meanwhile, where `SMEMBERS` gave one point-in-time copy |
| Optional: build on JDK 11+, and the Scaladoc warnings | #188, #293 | Build fixes, not features. Phase 1 moves to JDK 17 anyway | Open |

Not on the list, because they'd be new features: #45 (a configurable pool size), #264 (`MGET` for key-value reads)
and #349 (`spark.redis.url`). Also left alone: `BLPOP` in the list DStream (rule 3, but nothing is broken, and
DStreams go away in 4.x), and binary-model rows, which stay Java-serialized. Their struct, array and map columns hold
Spark and Scala classes, so they're tied to those versions, and a new row format is a 4.x design decision.

## Sign-off

When items 2 and 3 are done, the owner signs here to close Phase 0 and approve starting the Phase 1 plan.

  Owner: ____________________  Date: ____________
