# Phase 0 plan: ground truth and guardrails

> Stage: **Build**, the `plan.md` for Phase 0 (AI-native SDLC playbook). Inputs: [`roadmap.md`](../roadmap.md)
> (Phase 0) and [`spec.md`](../spec.md) (§2.12 verification, §2.14 no legacy patterns). Status: **draft, awaiting
> the owner's approval**. Work happens on `spike/phase-0` in the `Redislabs-Solution-Architects/spark-redis`
> fork, cut from `docs/modernization-roadmap`.

## Goal

Make the current 3.x codebase safe to change before anything is ported. At the end of Phase 0:

- The existing test suite runs on Redis 8 in containers, locally and in CI.
- The exact Redis data that 3.x writes and reads is captured as golden fixtures.
- Performance has a recorded baseline.
- Agents work inside guardrails that the owner trusts.

**Out of scope for Phase 0:** connector code under `src/main` stays unchanged, and nothing is ported to Spark 4.
Every change here is to tests, the build, CI, docs or agent configuration.

## Environment

| Where | Setup |
|---|---|
| Owner's Mac (arm64) | Azul Zulu **JDK 8** (`/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home`) for building and testing 3.x. Docker Desktop (engine 29.9, 16 CPUs, 31 GiB) for Testcontainers. Homebrew Maven 3.10.1; the Maven Wrapper (step C8) pins the version that CI uses. The `scalafmt` command-line tool (Homebrew) for the format-on-edit hook (C4) |
| CI | GitHub Actions on the fork, `ubuntu-latest`, Temurin 8. Docker is available on the runners. **Actions must be enabled on the fork**: it currently reports 0 registered workflows |
| Claude's sandbox | Cannot reach the Docker socket, and cannot write `.claude/hooks/` or `.claude/skills/`. Steps that need either are run in the owner's terminal or with the owner's explicit approval |

## Branches, lanes and file ownership

| Lane | Branch | Owns these files | Starts |
|---|---|---|---|
| **A: test harness and CI** | `spike/phase-0-a` | `pom.xml`, `Makefile`, `src/test/scala/**/env/`, `src/test/resources/containers/`, `versions.yml`, `.github/workflows/ci.yml`, `scripts/check-resp3.sh` | First. A1 lands before anything else |
| **B: golden layout, legacy fixtures, baseline** | `spike/phase-0-b` | `src/test/scala/**/golden/`, `src/test/resources/golden/`, `src/test/resources/legacy/`, `src/test/scala/**/df/benchmark/`, `src/test/scala/**/RedisBenchmarks.scala`, `doc/modernization/benchmarks/` | After A1 merges |
| **C: guardrails, cleanup, triage** | `spike/phase-0-c` | `CLAUDE.md`, `REVIEW.md`, `.claude/`, `evals/`, `scripts/check-*.sh` (except `check-resp3.sh`), `.github/workflows/guardrails.yml`, `.github/workflows/publish-snapshot.yml`, `build/`, `project/`, `.mvn/`, `mvnw*`, `.scalafmt.conf`, `doc/modernization/backlog.md` | Right away. Its `pom.xml` edit (C9) waits for A1 |

Each lane merges into `spike/phase-0` through a PR in the fork, which the owner approves as code owner. Two rules
prevent conflicts:

- **`pom.xml` belongs to lane A.** Lane C sends its `pom.xml` changes as a separate PR after A1 merges.
- **Lane C never edits `ci.yml`.** Its checks run in its own workflow, `guardrails.yml`.

## Steps

Each step gives the files it touches, what changes, and the **proof** that it's done. The proof is a command or an
artifact the owner can check.

### Lane A: test harness and CI

**A1. Testcontainers harness, with the existing suites unchanged**

- **Files:**
  - `pom.xml`: add `org.testcontainers:testcontainers` 1.21.x at test scope. That line still supports Java 8.
  - New: `src/test/scala/com/redislabs/provider/redis/env/RedisContainers.scala`.
  - `env/Env.scala`: start the containers before the Spark session is created.
  - New: `src/test/resources/containers/standalone.conf`, `standalone-tls.conf`, `cluster-node.conf`, `users.acl`
    and `start-cluster.sh`.
- **Change:**
  - **One set of containers per test JVM**, started lazily. The image comes from the system property
    `redis.image` (default `redis:8.2`).
  - **Containers bind the same host ports the suites already use:** 6379, 6380 for TLS, and 7379–7381 for the
    cluster. That keeps all 59 suites unchanged. Testcontainers' port bindings are set through
    `withCreateContainerCmdModifier`.
  - **Authentication uses an ACL file instead of `requirepass`:**
    - `user default on >passwd ~* &* +@all`
    - `user alice on >p1pp0 ~* &* +@all`
    
    These are the same test credentials the suites already use (spec §2.14). They replace the Makefile's
    `requirepass` and `USER_ACL` lines.
  - **TLS container:** mounts `src/test/resources/tls` read-only, with `port 0` and `tls-port 6380`.
  - **Cluster:** one container runs three `redis-server` processes on 7379–7381 with
    `cluster-announce-ip 127.0.0.1`. Slots are assigned with exactly the Makefile's ranges, so tests that depend
    on slots behave as before.
- **Proof:**
  - `JAVA_HOME=<zulu-8> ./mvnw -B test -Dsuites=com.redislabs.provider.redis.df.standalone.HashDataframeStandaloneSuite`
    passes on a machine with no `redis-server` installed.
  - `docker ps` lists the containers during the run, and none afterwards.

**A2. Makefile becomes a thin wrapper**

- **Files:** `Makefile`.
- **Change:**
  - Delete the `start*`, `stop*` and `restart` targets and the `redis-server` config heredocs.
  - `test` → `./mvnw -B verify`, and `benchmark` → `./mvnw -B -Pbenchmark verify`.
  - Delete `deploy` and `package`.
- **Proof:** `make test` runs green with no local Redis.

**A3. Run the existing suite on Redis 8 and record what breaks**

- **Files:** `doc/modernization/plans/phase-0-findings.md`, `src/test/resources/quarantine.txt`, and a ScalaTest tag
  exclusion in `pom.xml`.
- **Change:**
  - Run `./mvnw -B verify -Dredis.image=redis:<v>` for 8.2, 8.6 and 8.10.
  - Record every failure in the findings doc: suite, test, Redis version, error, and probable cause.
  - Harness problems are fixed in A1.
  - Real Redis 8 behaviour changes are **not** fixed in Phase 0. Those tests go into `quarantine.txt`, one line
    per test, each linking to its findings entry, and the owner reviews the list. Phase 1 empties it.
- **Proof:**
  - The findings doc is committed.
  - With the quarantine applied, `./mvnw verify` passes on all three versions.
  - The quarantine count is reported in the PR.

**A4. `versions.yml` and the CI workflow**

- **Files:** new `versions.yml`, new `.github/workflows/ci.yml`; delete `.github/workflows/integration.yml`.
- **Change:**
  - `versions.yml` is the single source for the test matrix: Redis `8.2`, `8.6` and `8.10`; the newest Redis
    8.12 release-candidate tag as an allowed-to-fail entry; JDK `8`. Phase 1 adds the Spark lines.
  - A `matrix` job reads the file with `yq`, which is preinstalled on Ubuntu runners.
  - A `test` job then runs `./mvnw -B verify -Dredis.image=redis:${{ matrix.redis }}` on Temurin 8 and uploads
    the test reports.
  - Triggers: pushes and PRs (ignoring changes only under `doc/`), a nightly schedule, and `workflow_dispatch`.
- **Proof:** a push to `spike/phase-0` runs one job per Redis version, and all of them pass.

**A5. Check RESP3 on managed Redis**

- **Files:** new `scripts/check-resp3.sh`; results go in `phase-0-findings.md`.
- **Change:**
  - The script runs `redis-cli -u <url> HELLO 3` from a `redis:8.10` container, so no local `redis-cli` is
    needed, and checks that the reply says `proto 3`.
  - Run it against the **latest Redis Software** container (`redislabs/redis`, one-node cluster, one
    database). The bootstrap steps are written up in the findings doc.
  - Run it against a **latest Redis Cloud** database only if the owner provides one. Otherwise record it as
    not done.
- **Proof:** the `HELLO 3` output is recorded in the findings doc.

### Lane B: golden layout, legacy fixtures, baseline

**B1. Snapshot format and tooling**

- **Files:**
  - New: `src/test/scala/com/redislabs/provider/redis/golden/KeyspaceSnapshot.scala`, `GoldenSuite.scala` and
    `KeyspaceSnapshotSpec.scala`.
  - Fixtures go under `src/test/resources/golden/{write,read}/<scenario>.json`.
- **Format:** language-neutral JSON, so the 4.x `it` module can reuse it in Phase 2:
  - **Keys and types:** keys are sorted, and each records its `type`.
  - **Values:**
    - strings: base64, plus UTF-8 text when the bytes are valid UTF-8
    - hashes: a field map sorted by field name
    - lists: in order
    - sets: sorted
    - sorted sets: sorted by score and then member, with scores as strings
    - streams: entries in order, with IDs replaced by their position
  - **TTL:** either `"none"`, or the expected seconds with a ±5 s tolerance.
  - **Random keys:** 32-hex UUID key segments become `<uuid>`, and matching is order-independent.
  - **Cluster runs:** a snapshot gathers keys from every primary.
- **Modes:** comparing is the default. `-Dgolden.update=true` regenerates the fixtures. Only the owner does
  that, and a hook blocks agents from editing fixtures (C4).
- **Proof:** `KeyspaceSnapshotSpec` covers every type, the UUID placeholder and TTL tolerance.

**B2. Write-path scenarios**

Fixtures are captured on Redis 8.2 and compared on every line in the matrix, in both standalone and cluster.

- **Files:** `golden/WriteLayoutSuite.scala` and `golden/write/*.json`.
- **Scenarios:**
  - **DataFrame `hash` model:** with `key.column`, without it (UUID keys), with `ttl`, `Overwrite`, `Append` over
    existing rows, null values, and every supported type (int, long, double, boolean, date, timestamp, binary).
  - **DataFrame `binary` model:** the same subset.
  - **The `_spark:<table>:schema` key.**
  - **Each RDD writer, with and without TTL:** `toRedisKV`, `toRedisHASH`, `toRedisHASHes`, `toRedisByteHASHes`,
    `toRedisZSET`, `toRedisSET`, `toRedisLIST`, `toRedisLISTs`, `toRedisByteLIST`, `toRedisByteLISTs` and
    `toRedisFixedLIST`.
  - **Stream consumer groups:** the group state after a Structured Streaming read (`XINFO GROUPS`).
- **Proof:** every scenario passes in CI on 8.2, 8.6 and 8.10, and the owner reviews each fixture the first time
  it's captured.

**B3. Read-path scenarios**

- **Files:** `golden/ReadResultSuite.scala`, `golden/read/*.json`, and the input datasets under
  `golden/datasets/*.json`.
- **Change:** load fixed datasets into Redis: hash rows, strings, lists, sets, sorted sets, and streams with fixed
  IDs. Then run the 3.x reads and store the rows they return, sorted:
  - **DataFrame reads:** with a schema, with `infer.schema`, with `keys.pattern`, with `filter.keys.by.type`, and
    with column pruning.
  - **RDD readers:** `fromRedisKV`, `Hash`, `List`, `Set`, `ZSet`, `ZRange*` and `ZRangeByScore*`.
  - **One Structured Streaming batch,** read with explicit offsets.
- **Proof:** the same as B2.

**B4. Legacy fixtures for the migration tool**

- **Files:** `src/test/resources/legacy/*.bin` and `legacy/README.md`. The README names the build that produced
  them: Scala 2.12, Spark 3.2.1, Java 8.
- **Change:** save `_spark:<table>:schema` blobs for three schemas (flat, every type, and a wide one with 50
  columns) and a set of `binary`-model rows. These come from the B2 captures.
- **Proof:** a test deserializes every blob with the current build.

**B5. Benchmark baseline**

- **Files:**
  - `src/test/scala/com/redislabs/provider/redis/RedisBenchmarks.scala`: in addition to printing, append
    `name,millis` to `target/benchmarks.csv`.
  - New: `KeyListBenchmarkSuite.scala` (read 10k keys by an explicit key list from a 1M-key table) and
    `StreamBenchmarkSuite.scala` (read 1M stream entries through the Structured Streaming source).
  - Results go in `doc/modernization/benchmarks/baseline-3.x.csv` and `baseline-3.x.md`.
- **Change:**
  - Keep the existing suites: 1M rows, write, read, read all/1/0 fields, take 10, for the `hash` and `binary`
    models, on standalone and cluster.
  - Run the full set on the owner's Mac with `./mvnw -B -Pbenchmark verify -Dredis.image=redis:8.2` three
    times, and keep the median.
  - `baseline-3.x.md` records the machine and Docker resources.
  - CI's nightly run also runs the benchmarks and uploads the CSV. Until a stable runner is decided, regressions
    are **warnings only**.
- **Proof:** the baseline files are committed, and the nightly CSV artifact exists.

### Lane C: guardrails, cleanup, triage

**C1. `CLAUDE.md`**, under one page.

- **Commands:** the build and test command (`JAVA_HOME=<zulu-8> ./mvnw -B verify -Dredis.image=redis:8.2`).
- **Layout:** the module map.
- **Rules:**
  - `src/main` doesn't change in Phase 0
  - new code goes in `com.redis.sparkredis` from Phase 2
  - nothing new under `org.apache.spark.*`
  - no legacy Redis commands (spec §2.14)
  - "if a test fails, fix the code, not the test"
  - golden fixtures and quarantine entries change only with the owner's approval
- **Pointers:** the intent, spec, roadmap and this plan.

**C2. `REVIEW.md`**, the rubric for Claude's PR review.

- **Passes:**
  - correctness against the spec section or plan step the PR cites
  - cluster correctness
  - credential leakage
  - 3.x data compatibility (golden layout)
  - no legacy Redis patterns
  - capability gating has a fallback and a test
- **Severity:** findings are marked Important or Nit, with at most 5 nits. A finding never approves or blocks a
  PR on its own.

**C3. Check scripts and the guardrails workflow**

- **Files:**
  - `scripts/check-legacy-redis.sh`. It looks at **added lines only**, because 3.x code still contains these calls
    until Phase 1: `.keys(`, `smembers(`, `hmset(`, `setex(`, `setnx(`, `getSet(`, `eval(`, `evalsha(`,
    `zrangeByScore`, `blpop(`, `brpop(` and `subscribe(`.
  - `scripts/check-spark-internal-imports.sh`, which flags new imports from `org.apache.spark.sql.catalyst` or
    `execution` that aren't in `scripts/spark-internal-allowlist.txt`.
  - `scripts/check-secrets.sh`, which runs `gitleaks` in a container.
  - `.github/workflows/guardrails.yml`, which runs all three on PRs.
- **Proof:** a throwaway branch that adds `jedis.keys("*")` fails the workflow, and the PR shows the message.

**C4. Hooks and project settings**

The owner creates these or approves them, because the sandbox can't write them.

- **Files:** `.claude/settings.json`, `.claude/hooks/protect-paths.sh`, `.claude/hooks/after-edit.sh` and
  `.claude/failing-first.txt`.
- **Change:**
  - A `PreToolUse` hook on `Edit`/`Write` blocks agent edits to:
    - `src/test/resources/tls/**`
    - `src/test/resources/golden/**`
    - `src/test/resources/legacy/**`
    - `src/test/resources/quarantine.txt`
    - any test listed in `failing-first.txt` (a bug's test, committed before its fix)
  - A `PostToolUse` hook runs scalafmt on the edited Scala file, followed by the C3 checks on that file.
- **Proof:** an agent's attempt to edit a golden fixture is blocked with a message that names the rule.

**C5. Verifier subagent**

- **Files:** `.claude/agents/verifier.md`.
- **Change:** it has only the Bash and Read tools. Given a list of Redis images, it runs `./mvnw verify` for each
  and reports a pass/fail table and the failures. It never edits anything.
- **Proof:** a run against the A1 branch produces the table.

**C6. Skills**

- **Files:** `.claude/skills/golden-layout/SKILL.md` (complete), plus `redis-capabilities/SKILL.md` and
  `dsv2-connector/SKILL.md` as drafts to finish in Phase 2.
- **Change:** `golden-layout` covers how to add a scenario, how fixtures are reviewed, and why agents never
  regenerate them. `new-spark-line` waits until Phase 1, when there are Spark lines to add.
- **Proof:** the files exist and are linked from `CLAUDE.md`.

**C7. Seed the eval suite**

- **Files:** `evals/README.md` and `evals/tasks/01-…10-*.md`. Each task gives a prompt, a starting commit and an
  acceptance command.
- **Tasks:**
  1. add a golden scenario for `toRedisFixedLIST`
  2. add a Redis line to `versions.yml`
  3. add a properly linked quarantine entry
  4. `client.name` (#398)
  5. a clear error for bad dates and timestamps (#358)
  6. `write.mode=replace` (#351) without changing the `merge` golden layout
  7. a capability check with a fallback
  8. a `MOVED` redirect test
  9. the benchmark CSV sink
  10. an attempt to add `KEYS`, which must be blocked by the hook
- **Runner:** Phase 0 runs the suite by hand, and Phase 2 automates it.
- **Proof:** the owner runs all 10 once and records the baseline pass rate in `evals/README.md`.

**C8. Repo cleanup**

- **Files:**
  - delete `build/sbt`, `build/sbt-launch-lib.bash` and `project/`
  - add the Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`), pinned to the current Maven 3.9.x release
  - add `.scalafmt.conf`
- **Change:** scalafmt formats only the files that are edited, so the old code isn't reformatted in bulk. That
  would only add churn before the Phase 2 module split. scalastyle stays until Phase 2.
- **Proof:** `./mvnw -v` works on a clean checkout, and nothing in the repo still mentions sbt.

**C9. Release pipeline dry run**

This step waits for A1 because it touches `pom.xml`.

- **Files:**
  - delete `.github/workflows/version-and-release.yml`, which targets OSSRH
  - add `.github/workflows/publish-snapshot.yml` (`workflow_dispatch`)
  - in `pom.xml`, replace the OSSRH `distributionManagement` and the `nexus-staging` plugin with GitHub Packages:
    repository id `github`, URL `https://maven.pkg.github.com/Redislabs-Solution-Architects/spark-redis`
- **Change:**
  - The workflow runs `./mvnw -B deploy -DskipTests` with `GITHUB_TOKEN` and `packages: write`.
  - The dry run keeps today's coordinates (`com.redislabs:spark-redis_2.12:3.1.0-SNAPSHOT`). Phase 1 switches
    to the per-Spark `com.redis` artifacts.
  - The Release Drafter workflows stay as they are.
- **Proof:** a manual run publishes the snapshot, and the package appears under the fork's Packages.

**C10. Backlog triage**

- **Files:** `doc/modernization/backlog.md`.
- **Change:**
  - An agent drafts a table of every open upstream issue from `gh issue list -R RedisLabs/spark-redis`:
    number, title, the phase that resolves it or "declined: reason", and duplicate-of.
  - The owner confirms each row.
  - **No labels or comments go on upstream issues** during the spike.
- **Proof:** every open issue appears exactly once, and the file ends with the owner's sign-off.

## Order

1. **A1.** Everything else runs on this harness.
2. **In parallel, up to three sessions:** lane A (A2–A5), lane B (B1–B5) and lane C (C1–C8 and C10).
3. **C9,** after A1, because it touches `pom.xml`.
4. **The exit review** below.

## Exit criteria

The owner signs off on each item:

- [ ] `./mvnw verify` passes in CI on Redis 8.2, 8.6 and 8.10, and the quarantine list has been reviewed.
- [ ] The golden write and read fixtures are committed and pass on all three lines, in both standalone and
      cluster.
- [ ] The legacy fixtures and their README are committed.
- [ ] The benchmark baseline (CSV and environment notes) is committed.
- [ ] `CLAUDE.md`, `REVIEW.md`, the hooks, the verifier, the `golden-layout` skill and 10 evals are in place, and
      the eval baseline is recorded.
- [ ] A snapshot has been published to GitHub Packages.
- [ ] `backlog.md` is confirmed.
- [ ] The RESP3 check is recorded for the latest Redis Software, and for Redis Cloud if a database was provided.

## Risks

| Risk | Mitigation |
|---|---|
| Fixed host ports mean two runs on the same machine collide | Run one test job per runner in CI and one run at a time locally. Phase 2's `it` module moves to dynamic ports |
| Redis 8 behaviour changes break many existing tests | That's Phase 1 work, not harness work. If more than 20% of tests end up quarantined, stop and reassess with the owner |
| The cluster container's announced address is unreachable on some Docker setups | Announce `127.0.0.1` and bind the same host ports. If that fails on a platform, fall back to host networking on Linux CI |
| Benchmarks on GitHub-hosted runners are noisy | The baseline comes from the owner's Mac. CI results are warnings only until there's a stable runner |
| Java-serialized fixtures depend on the Scala and Spark build that made them | `legacy/README.md` records the producing build. B4's test proves the current build reads them |
| The sandbox blocks Docker and `.claude/hooks` | Those steps run in the owner's terminal or with explicit approval |
| Consumers need a token to resolve from GitHub Packages | Note it in the snapshot workflow's README section. Public download comes with GitHub Release assets in Phase 1 |

## Measurement

From the playbook, recorded in the Phase 0 exit review:

- time from approving this plan to the exit sign-off
- first-pass merge rate of the Phase 0 PRs
- number of quarantined tests
- the eval baseline pass rate
- the number of times a hook blocked an edit
