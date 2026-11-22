---
name: redis-capabilities
description: DRAFT, finished in Phase 2. How to add a feature that needs a Redis command newer than the 8.2 floor - probe for it, keep a fallback or fail at planning with a clear message, and test both sides. Use when a change sends a command that 8.2 may not have, or depends on a module, the Query Engine, or a server setting.
---

# Capability-gated features

> **Draft.** Phase 0 seeds this skill; Phase 2a (the capability probe in `core`) finishes it with the real API.
> Until then, follow the rules below. `REVIEW.md` pass 6 (capability gating) checks them.

The connector supports Redis 8.2 and later, on Redis Open Source, Redis Software and Redis Cloud. Their version
numbers don't line up, and Redis Software and Redis Cloud can disable commands, so **never branch on a version
number** (spec §1).

## The rules

1. **Probe the server, not its version.** Ask for the command itself: `COMMAND INFO <name>` (or `COMMAND DOCS`)
   for commands and their arguments, `MODULE LIST` for modules, `FT._LIST` / `FT.INFO` for Query Engine indexes.
   Never parse `INFO server` or `redis_version`.
2. **Probe once per job and connection profile,** at planning time, and cache the answer. Never per row or per
   partition.
3. **Decide what happens without the capability:**
   - an optimisation (a faster command, a pushdown) falls back to a path that gives exactly the same result
   - a feature the user asked for explicitly (an option that needs the command) fails **at planning time**, with a
     message that names the missing command and the option that needs it
4. **Both paths write the same data.** The golden-layout tests must pass on both.
5. **Test both sides:** on `redis:8.2` (the floor) and on a line that has the capability. A cluster test too, when
   the command is multi-key: every multi-key command stays within one slot.
6. **The fallback is a modern command too** (spec §2.14). For example, without `MSETEX` the fallback is `SET … EX`,
   never `SETEX`.

## Capabilities beyond the floor (spec §1, §2.2–§2.5)

| Command or feature | Since | Used for |
|---|---|---|
| `MSETEX` | 8.4 | `string` model batches with TTL |
| `SET … IFEQ`/`IFNE`/`IFDEQ`/`IFDNE`, `DELEX`, `DIGEST` | 8.4 | `write.condition`, conditional deletes |
| `XREADGROUP … CLAIM`, `FT.HYBRID` | 8.4 | stream takeover; hybrid search |
| `XADD … IDMP` | 8.6 | exactly-once stream sink |
| `XNACK` | 8.8 | releasing a failed task's stream entries |
| `XREADGROUP … MAXSIZE`, `TS.NRANGE` | 8.10 | byte-bounded stream reads; TimeSeries |
| `HIMPORT` | 8.10, Open Source only | bulk `hash` loads |
| `CLUSTER MIGRATION STATUS` | 8.12 | detecting atomic slot migration |

## To finish in Phase 2

- [ ] The `core` capability probe's API, and an example of registering a new capability.
- [ ] The planning-time error format, with an example message.
- [ ] How tests select a Redis line (`versions.yml` entries and `-Dredis.image`), and a test template for both sides.
- [ ] Redis Software and Redis Cloud notes: commands a database can disable, and the OSS Cluster API.
