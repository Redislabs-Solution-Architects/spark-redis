# Legacy 3.x fixtures

Java-serialized data that spark-redis 3.x writes and that only `spark-redis-migrate` will read (spec §2.11). Each
file holds the exact bytes of one Redis value, copied from a golden write capture (`../golden/write/`).

## Producing build

| | |
|---|---|
| spark-redis | `3.1.0-SNAPSHOT`, connector code as of `37c855d` on `spike/phase-0` (Phase 0 does not change `src/main`) |
| Scala | 2.12 (`scala-library` 2.12.0 on the test classpath: the pom pins `${scala.major.version}.0`) |
| Spark | 3.2.1 |
| Java | 8: Azul Zulu `1.8.0_514` |
| Redis | `redis:8.2` (8.2.11), standalone and cluster wrote identical bytes |
| Time zone | UTC (the golden suites set it; `java.sql.Date` and `Timestamp` serialize local time) |

Java serialization embeds class names and `serialVersionUID`s of Spark and Scala 2.12 classes (for example
`scala.collection.immutable.Map$EmptyMap$` inside a `StructType`), so these files cannot be read reliably on Scala
2.13. That is why `spark-redis-migrate` runs on the 3.x classpath.

## Files

| File | What it is | Captured from |
|---|---|---|
| `schema-flat.bin` | `_spark:<table>:schema`: the `Person` case class (`name`, `age`, `address`, `salary`; `age` and `salary` not nullable) | `df-hash-key-column` |
| `schema-all-types.bin` | `_spark:<table>:schema`: string, byte, short, int, long, float, double, decimal(10,2), boolean, date, timestamp, binary | `df-hash-all-types` |
| `schema-wide-50.bin` | `_spark:<table>:schema`: 50 columns (`id`, then `c01`..`c49` cycling string, int, long, double, boolean) | `df-schema-wide` |
| `binary-row-person-john.bin` | `binary` model row `John, 30, 60 Wall Street, 150.5` | `df-binary-key-column` |
| `binary-row-nulls-john.bin` | `binary` model row with nulls: `John, null, 60 Wall Street, null` | `df-binary-nulls` |
| `binary-row-all-types-a.bin` | `binary` model row of every type (row `a`) | `df-binary-all-types` |
| `binary-row-all-types-b.bin` | `binary` model row of every type (row `b`: negatives, Unicode, empty binary) | `df-binary-all-types` |

A `binary` model row is `SerializationUtils.serialize(Object[])` of every column, the key column included, in schema
order.

A `StructType` blob's bytes also depend on which of the type's lazy fields Spark evaluated before writing it, so
another write of the same schema can produce different bytes that deserialize to the same `StructType`. These files
are one such capture.

## Checks

`com.redislabs.provider.redis.golden.LegacyFixturesSuite` checks that:

- each `binary` row equals its golden write capture byte for byte, and each schema blob decodes to the same
  `StructType` as its capture;
- every schema blob deserializes to the expected `StructType` with the current build;
- every row deserializes to the expected values;
- the 3.x connector reads `schema-all-types.bin` with the two all-types rows back as a `binary` model table.

The files are frozen. An update with `-Dgolden.update=true` only writes the missing files, from the write fixtures;
to capture one again, the owner deletes it first (see `../golden/README.md`).
