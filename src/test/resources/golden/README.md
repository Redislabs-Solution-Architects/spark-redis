# Golden fixtures

Characterization fixtures for spark-redis 3.x (Phase 0, steps B1–B3). They record exactly what the 3.x connector
writes to Redis and what it reads back, so that the 4.x rewrite can be checked against them. The format is plain
JSON, so the 4.x `it` module can reuse the files.

| Folder | Contents | Suite |
|---|---|---|
| `write/` | The keyspace each 3.x write leaves behind | `golden.WriteLayoutSuite` (standalone and cluster) |
| `read/` | The rows each 3.x read returns from a dataset | `golden.ReadResultSuite` (standalone and cluster) |
| `datasets/` | The fixed inputs the read scenarios load, in the snapshot format | loaded by `ReadResultSuite` |

The legacy Java-serialized blobs are in `../legacy/`.

## Rules

- **Fixtures change only with the owner's approval.** A failing golden test means the connector's layout changed:
  fix the code, not the fixture.
- **Only the owner regenerates fixtures,** and reviews every changed file before it is committed.
- Fixtures are captured on `redis:8.2` and must pass unchanged on every Redis line in the matrix (8.2, 8.6, 8.10),
  in both standalone and cluster.

## Running

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./mvnw -B verify -Dredis.image=redis:8.2 \
  -DwildcardSuites=com.redislabs.provider.redis.golden
```

A mismatch fails with the differences and writes what the test saw to
`target/golden-actual/<kind>/<scenario>.<topology>.json`.

**Regenerating (owner only):** add `-Dgolden.update=true` to the command above. When standalone and cluster run in
the same update, the second compares with what the first wrote, so both topologies must agree. The legacy files in
`../legacy/` are frozen: an update only writes the ones that are missing, from the write fixtures.

## Snapshot format (`spark-redis-keyspace-snapshot/1`)

```json
{
  "format": "spark-redis-keyspace-snapshot/1",
  "keys": [
    {"key": "person:John", "type": "hash", "ttl": "none", "value": {"age": "30", "name": "John"}}
  ]
}
```

- **Keys** are sorted, and each records its Redis `type`. A key is its UTF-8 text, or `base64:<base64>` when its
  bytes are not valid UTF-8.
- **Random keys:** every 32-hex segment of a key (the parts between `:`), such as 3.x's random row IDs, becomes
  `<uuid>`. Keys that share a name are matched regardless of order.
- **TTL** is `"none"`, or whole seconds (`PTTL` rounded). A TTL matches when the seconds are within ±5.
- **Elements** (hash fields and values, list items, set and sorted set members, stream fields) are JSON strings
  when the bytes are valid UTF-8, and `{"base64": "..."}` otherwise.

| Type | `value` |
|---|---|
| `string` | `{"base64": ..., "utf8": ...}`: `utf8` only when the bytes are valid UTF-8. Java-serialized values also get a `javaSerialized` view (below) |
| `hash` | An object of field to element, sorted by field |
| `list` | An array of elements, in list order |
| `set` | An array of elements, sorted by bytes |
| `zset` | An array of `{"member": ..., "score": "..."}`, sorted by score and then member. The score is the text Redis returns, such as `"1.5"`, `"1000"` or `"inf"` |
| `stream` | `{"entries": [...], "groups": [...]}`. Entries are in order, and each ID is replaced by its 1-based position: `{"id": "#1", "fields": [["name", "John"], ...]}`. Each group records `name`, `last-delivered-id` (as `#n` when it is an entry, else the raw ID), `entries-read`, `lag`, `pending` and its `consumers` (`name`, `pending`) |

Sorting is by unsigned bytes, which is code point order for UTF-8 text.

**Java-serialized strings** (bytes starting `AC ED 00 05`) get a decoded `javaSerialized` view:

- A `StructType` (the `_spark:<table>:schema` key) shows its Spark schema JSON, and is **compared by that view
  only**. Spark serializes the state of the type's lazy fields (`nameToField`, `nameToIndex`, a cached hash code),
  so the same schema can serialize to different bytes depending on what Spark evaluated before writing it.
  `KeyspaceSnapshotSpec` demonstrates this.
- Anything else (a `binary` model row) lists each value's class and text, for reviewers, and is compared **byte for
  byte** through `base64`.

**Placeholders for values that differ per run:** `[B@<identity-hash>` replaces Java's `Object.toString` of a byte
array, which is what the `hash` model stores for a `binary` column (`df-hash-all-types`).

**Datasets** use the same format, with two additions for hand-written input: a string may give only `utf8`, and
a stream's entry `#n` is loaded with the ID `n-0`. Datasets have no `<uuid>` keys and no stream groups.

## Read results

DataFrame reads record the schema (`name`, Spark `simpleString` type, `nullable`) and the rows. RDD reads record the
rows. Rows are sorted by their JSON text, unless `"order": "as returned"` says the reader's order is part of the
contract. Values are JSON numbers, strings, booleans or null; dates are ISO dates, timestamps ISO instants in UTC,
decimals strings, and binary `{"base64": ...}`.

## What the fixtures show about 3.x

Behaviour the fixtures pin down that a reader might not expect:

- `hash` model: null columns are not written, so an `Append` with a null keeps the old value of that field
  (`df-hash-append`). The key column is not stored in the hash. Values are `String.valueOf`: `1.0E10`,
  `1999-12-31 23:59:59.0`, and `[B@...` for binary.
- `binary` model: the value is a Java-serialized `Object[]` of every column, the key column included.
- `ttl` is applied to rows (`HMSET` then `EXPIRE`, or `SETEX`), never to `_spark:<table>:schema`.
- `Overwrite` deletes the table's rows and rewrites the schema key; `Append` replaces the schema key with the new
  DataFrame's schema.
- Without `key.column`, row keys are `<table>:<32-hex UUID>`.
- `infer.schema` gives every column as `string`, in Java `HashMap` order (`salary, age, address`), then `_id`.
- With `keys.pattern`, the key column is what follows the pattern's prefix, so `golden_people:P*` reads `Peter` as
  `eter` (`df-keys-pattern-prefix`).
- Selecting no columns returns one empty row per matching key.
- The RDD readers skip keys of another type, so one pattern can cover a mixed keyspace.
- `toRedisFixedLIST` pushes to the head (`LPUSH`) and then trims, so the list holds the last values in reverse.
- The Structured Streaming source reads with `NOACK`, so groups show no pending entries. A group that 3.x creates at
  the stream's end (no `stream.offsets`) reports `entries-read: null`.
- Dates and timestamps are written in the JVM time zone. The suites run in UTC.

## Scenarios

**Write (`write/`)**, each under the table or key prefix `golden_<scenario>`:

- DataFrame `hash` and `binary` models: `key-column`, `uuid-keys`, `ttl` (3600 s), `overwrite`, `append`, `nulls`,
  `all-types`; and `df-schema-wide` (50 columns, `hash`).
- RDD writers, each with and without a TTL: `toRedisKV`, `toRedisHASH`, `toRedisHASHes`, `toRedisByteHASHes`,
  `toRedisZSET`, `toRedisSET`, `toRedisLIST`, `toRedisLISTs`, `toRedisByteLIST`, `toRedisByteLISTs`, and
  `toRedisFixedLIST` (which takes no TTL; trimmed and untrimmed instead).
- `stream-consumer-group`: the group state after a Structured Streaming batch from `0-0`, and after one from the
  default start.

**Read (`read/`):**

- DataFrame: an explicit schema (`df-schema`, `df-schema-types`), `infer.schema`, `keys.pattern` (with and without a
  prefix before `*`), `filter.keys.by.type`, and column pruning (two columns, and none).
- RDD: `fromRedisKV` (pattern and key list), `fromRedisHash`, `fromRedisList` (pattern, and one key in order),
  `fromRedisSet`, `fromRedisZSet`, `fromRedisZSetWithScore`, `fromRedisZRange`, `fromRedisZRangeWithScore`,
  `fromRedisZRangeByScore`, `fromRedisZRangeByScoreWithScore`.
- `stream-batch-offsets`: one Structured Streaming batch from explicit offsets (`2-0`).
