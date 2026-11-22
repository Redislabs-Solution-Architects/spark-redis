---
name: golden-layout
description: How to add, run and review golden-layout tests, the characterization tests that pin down the exact Redis data spark-redis 3.x writes and the rows it reads back. Use when adding a write or read scenario, when a golden test fails, or when a change might alter keys, values, types or TTLs. Agents never create, edit or regenerate the fixtures themselves.
---

# Golden-layout tests

Golden-layout tests record **exactly what 3.x does**: the keys, types, values and TTLs each write produces, and the
rows each read returns from fixed data. They are the compatibility contract: 4.x must reproduce them byte for byte
(spec §2.11, §2.12; Phase 0 plan steps B1–B3). They describe the current code. They are not a spec for new
behaviour.

## Where things are

| What | Where |
|---|---|
| Snapshot format and helper | `src/test/scala/com/redislabs/provider/redis/golden/KeyspaceSnapshot.scala` |
| Write scenarios | `golden/WriteLayoutSuite.scala` → fixtures in `src/test/resources/golden/write/<scenario>.json` |
| Read scenarios | `golden/ReadResultSuite.scala`, input data in `golden/datasets/*.json` → `golden/read/<scenario>.json` |
| Tests of the format itself | `golden/KeyspaceSnapshotSpec.scala` |

Read `KeyspaceSnapshot.scala` for the exact API before writing a scenario. The format, in short:

- keys sorted, each with its Redis `type`
- strings in base64, plus the UTF-8 text when the bytes are valid UTF-8
- hashes as a field map sorted by field; lists in order; sets sorted
- sorted sets by score, then member, with scores as strings
- stream entries in order, with IDs replaced by their position
- TTL is `"none"` or the expected seconds (±5 s)
- 32-hex UUID key segments become `<uuid>`, and matching is then order-independent
- on a cluster, the snapshot gathers keys from every primary

## Adding a write scenario

1. **Name it** after the API and the variant, in kebab case, like the existing files: for example
   `rdd-to-redis-fixed-list` and `rdd-to-redis-fixed-list-ttl`.
2. **Write the test case** in `WriteLayoutSuite.scala`, next to its neighbours and in their style:
   - fixed input data, with no randomness except where 3.x itself generates UUID keys
   - its own key prefix or table name, so the snapshot holds only this scenario's keys
   - the 3.x write exactly as a user would call it (`df.write…save()`, `sc.toRedis…`)
   - a snapshot of the scenario's keys, compared with `write/<scenario>.json`
3. **Run it in both topologies.** Scenarios run on standalone and on the cluster, like the others.
4. **Stop before the fixture.** The new test fails because its fixture doesn't exist. That's expected. Don't write
   the JSON yourself, and don't run `-Dgolden.update=true`: a hook blocks both. Hand over to the owner with:
   - the command: `./mvnw -B verify -Dredis.image=redis:8.2 -DwildcardSuites=com.redislabs.provider.redis.golden -Dgolden.update=true`
   - what you expect the fixture to contain (keys, types, values, TTLs), so the owner can check the capture
5. **The owner captures and reviews** the fixture on Redis 8.2, then commits it with the test. From then on the
   test compares on every Redis line in the matrix.

Read scenarios work the same way: a fixed dataset in `golden/datasets/`, the 3.x read, and the returned rows sorted
and compared with `read/<scenario>.json`.

## When a golden test fails

A failing golden test means the data changed. Find out why before anything else:

- **The code changed the layout by accident.** That's a compatibility bug. Fix the code, not the test
  (CLAUDE.md rule 5).
- **The spec deliberately changes the layout** (for example, spec §2.4 writes TTLs atomically, but the stored data
  stays the same). Say so in the PR, cite the spec section, and leave the fixture to the owner. Only the owner
  regenerates fixtures, and the PR lists every fixture that changed.
- **The test is flaky** (ordering, timing, TTL drift). Report it with the evidence. Don't widen a tolerance or
  change the snapshot format to make it pass; that's an owner decision too.

Never edit a fixture, the comparison, the `<uuid>` rule or the TTL tolerance to get a green run.

## Why agents never regenerate fixtures

Regenerating a fixture turns whatever the code does now into the expected answer, so a regression would pass
silently. The agent that changes the code must not also redefine what correct looks like. This is why
`.claude/hooks/protect-paths.sh` blocks agent edits under `src/test/resources/golden/` and any command with
`-Dgolden.update=true`, and why the owner reviews each fixture the first time it is captured.

## Review checklist

For a PR that adds or touches golden tests (REVIEW.md, pass 4):

- Each new scenario has its fixture, captured by the owner, and runs in standalone and cluster.
- No existing fixture changed, unless the PR says why, cites the spec, and the owner regenerated it.
- Input data is fixed, and the scenario's keys don't overlap any other scenario's.
- The comparison, tolerances and placeholders are unchanged.
