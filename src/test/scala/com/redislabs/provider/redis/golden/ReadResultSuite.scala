package com.redislabs.provider.redis.golden

import com.redislabs.provider.redis.golden.GoldenData.{StreamSchema, streamOffsets}
import com.redislabs.provider.redis.toRedisContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.redis._
import org.apache.spark.sql.types._
import org.json4s._

// scalastyle:off multiple.string.literals magic.number

/**
  * Golden read results (Phase 0, step B3): the rows 3.x reads from the fixed datasets in `golden/datasets/`, compared
  * with `golden/read/<scenario>.json`. Rows are sorted unless the reader's order is part of its contract.
  */
trait ReadResultSuite extends GoldenSuite {

  private val Datasets = Seq("people", "types", "mixed", "rdd")

  private val PeopleSchema = StructType(Seq(
    StructField("name", StringType),
    StructField("age", IntegerType),
    StructField("address", StringType),
    StructField("salary", DoubleType),
    StructField("email", StringType)))

  private val TypesSchema = StructType(Seq(
    StructField("id", StringType),
    StructField("string", StringType),
    StructField("byte", ByteType),
    StructField("short", ShortType),
    StructField("int", IntegerType),
    StructField("long", LongType),
    StructField("float", FloatType),
    StructField("double", DoubleType),
    StructField("boolean", BooleanType),
    StructField("date", DateType),
    StructField("timestamp", TimestampType)))

  override def beforeAll(): Unit = {
    super.beforeAll()
    Datasets.foreach(loadDataset)
  }

  override def afterAll(): Unit = {
    try Datasets.foreach(unloadDataset) finally super.afterAll()
  }

  private def scenario(name: String)(result: => JValue): Unit =
    test(s"read result: $name") {
      assertReadResult(name, result)
    }

  private def read(options: (String, String)*): DataFrame =
    spark.read.format(RedisFormat).options(options.toMap).load()

  private def readWith(schema: StructType, options: (String, String)*): DataFrame =
    spark.read.format(RedisFormat).schema(schema).options(options.toMap).load()

  private def sorted(rdd: RDD[_]): JValue =
    JObject("order" -> JString("sorted"), "rows" -> sortedRows(rdd.collect().map(GoldenSuite.json)))

  private def asReturned(rdd: RDD[_]): JValue =
    JObject("order" -> JString("as returned"), "rows" -> JArray(rdd.collect().map(GoldenSuite.json).toList))

  // DataFrame reads

  scenario("df-schema") {
    dataFrameResult(readWith(PeopleSchema, SqlOptionTableName -> "golden_people", SqlOptionKeyColumn -> "name"))
  }

  scenario("df-schema-types") {
    dataFrameResult(readWith(TypesSchema, SqlOptionTableName -> "golden_types", SqlOptionKeyColumn -> "id"))
  }

  scenario("df-infer-schema") {
    dataFrameResult(read(SqlOptionTableName -> "golden_people", SqlOptionInferSchema -> "true"))
  }

  scenario("df-keys-pattern") {
    dataFrameResult(readWith(PeopleSchema, SqlOptionKeysPattern -> "golden_people:*", SqlOptionKeyColumn -> "name"))
  }

  scenario("df-keys-pattern-prefix") {
    // the key column is the part of the key after the pattern's prefix, so `golden_people:P*` gives `eter`
    dataFrameResult(readWith(PeopleSchema, SqlOptionKeysPattern -> "golden_people:P*", SqlOptionKeyColumn -> "name"))
  }

  scenario("df-filter-keys-by-type") {
    val schema = StructType(Seq(StructField("id", StringType), StructField("name", StringType),
      StructField("age", IntegerType)))
    dataFrameResult(readWith(schema, SqlOptionTableName -> "golden_mixed", SqlOptionKeyColumn -> "id",
      SqlOptionFilterKeysByType -> "true"))
  }

  scenario("df-pruning") {
    dataFrameResult(readWith(PeopleSchema, SqlOptionTableName -> "golden_people", SqlOptionKeyColumn -> "name")
      .select("salary", "name"))
  }

  scenario("df-pruning-no-columns") {
    dataFrameResult(readWith(PeopleSchema, SqlOptionTableName -> "golden_people", SqlOptionKeyColumn -> "name")
      .select())
  }

  // RDD readers, over a pattern that matches keys of every type

  private val All = "golden_rdd:*"

  scenario("rdd-from-redis-kv") {
    sorted(sc.fromRedisKV(All))
  }

  scenario("rdd-from-redis-kv-keys") {
    sorted(sc.fromRedisKV(Array("golden_rdd:str:a", "golden_rdd:hash:h1", "golden_rdd:missing")))
  }

  scenario("rdd-from-redis-hash") {
    sorted(sc.fromRedisHash(All))
  }

  scenario("rdd-from-redis-list") {
    sorted(sc.fromRedisList(All))
  }

  scenario("rdd-from-redis-list-one-key") {
    asReturned(sc.fromRedisList("golden_rdd:list:l1"))
  }

  scenario("rdd-from-redis-set") {
    sorted(sc.fromRedisSet(All))
  }

  scenario("rdd-from-redis-zset") {
    sorted(sc.fromRedisZSet(All))
  }

  scenario("rdd-from-redis-zset-with-score") {
    sorted(sc.fromRedisZSetWithScore(All))
  }

  scenario("rdd-from-redis-zrange") {
    sorted(sc.fromRedisZRange(All, 1, 2))
  }

  scenario("rdd-from-redis-zrange-with-score") {
    sorted(sc.fromRedisZRangeWithScore(All, 1, 2))
  }

  scenario("rdd-from-redis-zrange-by-score") {
    sorted(sc.fromRedisZRangeByScore(All, 0, 3))
  }

  scenario("rdd-from-redis-zrange-by-score-with-score") {
    sorted(sc.fromRedisZRangeByScoreWithScore(All, 0, 3))
  }

  // One Structured Streaming batch, from explicit offsets

  scenario("stream-batch-offsets") {
    val key = "golden_stream:events"
    loadDataset("stream")
    try {
      val rows = readStreamBatch(key, StreamSchema, Map(
        StreamOptionGroupName -> "golden-reader",
        StreamOptionStreamOffsets -> streamOffsets(key, "golden-reader", "2-0")))
      JObject("order" -> JString("sorted"), "rows" -> sortedRows(rows.map(GoldenSuite.json)))
    } finally {
      unloadDataset("stream")
    }
  }
}
