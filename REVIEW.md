# Review rubric

How Claude reviews a pull request in this repo. The review is advice to the code owner: **a finding never approves
or blocks a PR on its own.** Post the review as a comment, never as "Approve" or "Request changes". The owner
decides.

## Before the passes

- Read the PR description. It must cite the plan step (`plans/phase-N.md`) or spec section (`spec.md` §x.y) it
  implements, and list its proof. If it cites neither, that is the first Important finding.
- Read the cited section, then the whole diff. Review the diff against the section, not against your own idea of
  the feature.
- The guardrail checks (`guardrails.yml`) and the test matrix (`ci.yml`) run separately. Don't repeat what they
  report. Do flag anything that gets around them.

## Passes

Run every pass. A pass with nothing to report says "no findings".

1. **Spec and plan correctness.**
   - Does the diff do what the cited section says, all of it, and nothing outside it?
   - Does the stated proof show that, and could the owner rerun it?
   - Files outside the lane's ownership table (plan) need a reason in the description.
   - A Phase 0 change to `src/main` is a bug fix with its failing-first test, or a change the owner asked for
     (CLAUDE.md rule 1). The description says which.
   - Tests: fix the code, not the test. Flag any weakened assertion, new skip or `ignore`, widened tolerance,
     edited test listed in `.claude/failing-first.txt` (or removed from it in this PR), or quarantine entry
     without a findings link.
2. **Cluster correctness.**
   - Multi-key commands and pipelines are grouped by slot or by node. No `CROSSSLOT` risk.
   - Scans and reads cover every primary (or replica, when asked), each slot range exactly once.
   - `MOVED`/`ASK` re-resolves the slot owner instead of failing or dropping data.
   - Pipelined error replies are checked. A partial write must fail the task, never pass silently.
   - A new read or write path has a `cluster/` test as well as a `standalone/` one.
3. **Credential leakage.**
   - No password, token or credentialed URL reaches a log line, an exception message, a `toString`, test output
     or the Spark UI (options must be covered by redaction).
   - No new secret in the diff. Test credentials are the existing `passwd` and `p1pp0` only.
4. **3.x data compatibility.**
   - The golden-layout suites still pass, and no golden or legacy fixture changed without the owner's note in the
     PR.
   - Key format (`<table>:<id>`), value encoding, TTLs and `_spark:<table>:schema` are unchanged, unless the
     spec says otherwise.
   - Renamed or removed options fail with the message in spec §2.11 (from Phase 2).
5. **No legacy Redis patterns** (spec §2.14).
   - No `KEYS`, `SMEMBERS`, `HGETALL` on unbounded hashes, `LRANGE 0 -1`, `HMSET`, `SETEX`, `SETNX`, `GETSET`,
     `EVAL`/`EVALSHA`, `ZRANGEBYSCORE`, `BLPOP`/`BRPOP`, `SUBSCRIBE`, `INFO` parsing, `SELECT`/`dbNum`, or
     password-only `AUTH`.
   - A value and its TTL are written in one command or one `MULTI`/`EXEC`, never in separate steps.
   - The check script sees only literal calls. Look for the same commands sent another way, such as
     `sendCommand` or a command-name string.
6. **Capability gating.**
   - New commands beyond the 8.2 floor are gated on a probe (`COMMAND INFO`, `MODULE LIST`, `FT._LIST`), never on
     a version number.
   - There is a fallback, or a planning-time error that names the missing command.
   - Tests cover both sides: the floor (`redis:8.2`) and a line that has the capability.

## Findings

- **Important:** a bug, a spec or plan deviation, data loss, a compatibility break, a credential leak, or a way
  around a guardrail.
- **Nit:** naming, style, comments, docs. **At most 5 nits.** Keep the most useful ones and drop the rest.

Write each finding as: severity, pass, `path:line`, what is wrong, why it matters, and a suggested fix. List the
Important findings first. End with one line per pass ("2. Cluster correctness: no findings").
