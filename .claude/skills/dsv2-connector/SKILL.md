---
name: dsv2-connector
description: DRAFT, finished in Phase 2. Which Spark DataSource V2 interfaces the 4.x connector implements and the rules for using them - public connector APIs only, the com.redis.sparkredis package, per-Spark-minor overlays. Use when adding or changing a read, write, streaming, catalog or metrics path in the 4.x code.
---

# The DataSource V2 connector

> **Draft.** Phase 0 seeds this skill from spec §2.3–§2.10; Phase 2a finishes it once the `core`/`sql` module split
> and the `RedisTableModel` interface exist.

## The rules

1. **Public connector APIs only:** `org.apache.spark.sql.connector.*` and the public `org.apache.spark.sql` types.
   No new imports from `org.apache.spark.sql.catalyst` or `org.apache.spark.sql.execution` outside
   `scripts/spark-internal-allowlist.txt` (spec §3). CI and the after-edit hook check this.
2. **New code goes in `com.redis.sparkredis`.** Nothing new under `org.apache.spark.*` (spec §2).
3. **One source tree, thin overlays.** An interface that only a newer Spark minor has goes in that minor's
   `spark-4.x` overlay source set, with a fallback (or nothing) in the base (spec §2, "Build").
4. **Report filters honestly.** Return only the filters you did *not* push down as post-scan filters (spec §2.3).
5. **Every table model goes through `RedisTableModel`** (`encode`, `decode`, `readPlan`, `writePlan`,
   `capabilities`), so models stay independent lanes (spec §2.2).
6. **No RDD API, DStreams or DSv1** in 4.x (spec §2.14, "Legacy Spark and connector APIs").

## Interfaces we implement

| Area | Interfaces | Spec |
|---|---|---|
| Provider and table | `TableProvider`, `Table`, `SupportsRead`, `SupportsWrite`, `SupportsMetadataColumns` | §2, §2.3 |
| Scan | `ScanBuilder`, `SupportsPushDownV2Filters`, `SupportsPushDownRequiredColumns`, `SupportsPushDownLimit`, `SupportsPushDownTopN`, `SupportsPushDownAggregates`, `SupportsRuntimeV2Filtering`, `SupportsReportStatistics` | §2.3 |
| `VARIANT` | `SupportsPushDownVariantExtractions` (Spark 4.1+) | §2.3 |
| Write | `WriteBuilder`, `RequiresDistributionAndOrdering`, `SupportsTruncate` / `SupportsOverwriteV2` | §2.4 |
| Row-level | `SupportsRowLevelOperations`, `SupportsDelta` (`_key` is the row ID) | §2.4 |
| Streaming source | `MicroBatchStream`, `SupportsAdmissionControl`, `SupportsTriggerAvailableNow`, `ReportsSourceMetrics` | §2.5 |
| Streaming sink | `StreamingWrite`; plus the supported `RedisForeachWriter` | §2.5 |
| Real-Time Mode | `SupportsRealTimeMode`, `SupportsRealTimeRead`: `@Evolving`, own module, behind a flag | §2.5 |
| Catalog | `TableCatalog`, `FunctionCatalog`, `SupportsSchemaEvolution` (Spark 4.2+ artifacts) | §2.6 |
| Metrics | `CustomMetric`, `CustomTaskMetric` (override `mergeWith` for non-sums; Spark 4.2 requires it) | §2.10 |

## To finish in Phase 2

- [ ] The module layout (`core`, `sql`, `it`) and where each interface's implementation lives.
- [ ] A walkthrough of one scan from `ScanBuilder` to partition reader, with the pushdown decision order (point
      lookup, Query Engine, key scan).
- [ ] How a Spark minor's overlay is added and tested (with the `new-spark-line` skill, Phase 1).
- [ ] The `EXPLAIN` strategy string and the custom metrics every scan and write must report.
