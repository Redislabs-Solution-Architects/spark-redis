package com.redislabs.provider.redis.golden

import java.nio.charset.StandardCharsets.UTF_8

import com.redislabs.provider.redis.golden.GoldenData._
import com.redislabs.provider.redis.golden.KeyspaceSnapshot.{JavaByteArrayIdentity, ValueRule}
import com.redislabs.provider.redis.toRedisContext
import org.apache.spark.sql.redis._
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SaveMode}

// scalastyle:off multiple.string.literals magic.number

/**
  * Golden write layouts (Phase 0, step B2): the exact keys, values and TTLs each 3.x write produces. Each scenario
  * writes under its own table, `golden_<scenario>`, and compares every key under `golden_<scenario>:*` and the table's
  * `_spark:<table>:schema` key with `golden/write/<scenario>.json`.
  */
trait WriteLayoutSuite extends GoldenSuite {

  private val Ttl = 3600

  def table(scenario: String): String = "golden_" + scenario.replace('-', '_')

  private def scenario(name: String, rules: Seq[ValueRule] = Nil)(write: String => Unit): Unit =
    test(s"write layout: $name") {
      val t = table(name)
      val patterns = Seq(s"$t:*", s"_spark:$t:schema")
      KeyspaceSnapshot.delete(redisConfig, patterns)
      try {
        write(t)
        assertKeyspace(name, patterns, rules)
      } finally {
        KeyspaceSnapshot.delete(redisConfig, patterns)
      }
    }

  private def save(df: DataFrame, table: String, model: String, mode: SaveMode = SaveMode.Append,
                   options: Map[String, String] = Map()): Unit =
    df.write.format(RedisFormat)
      .option(SqlOptionModel, model)
      .option(SqlOptionTableName, table)
      .options(options)
      .mode(mode)
      .save()

  private def rows(schema: StructType, data: Seq[Row]): DataFrame =
    spark.createDataFrame(sc.parallelize(data, 2), schema)

  private def people: DataFrame = spark.createDataFrame(People)

  private def bytes(s: String): Array[Byte] = s.getBytes(UTF_8)

  // DataFrame writes, for both models

  for (model <- Seq(SqlOptionModelHash, SqlOptionModelBinary)) {
    val byName = Map(SqlOptionKeyColumn -> "name")

    scenario(s"df-$model-key-column") { t =>
      save(people, t, model, options = byName)
    }

    scenario(s"df-$model-uuid-keys") { t =>
      save(people, t, model)
    }

    scenario(s"df-$model-ttl") { t =>
      save(people, t, model, options = byName + (SqlOptionTTL -> Ttl.toString))
    }

    scenario(s"df-$model-overwrite") { t =>
      save(people, t, model, SaveMode.Append, byName)
      save(rows(PersonTitleSchema, PeopleUpdate), t, model, SaveMode.Overwrite, byName)
    }

    scenario(s"df-$model-append") { t =>
      save(people, t, model, SaveMode.Append, byName)
      save(rows(PersonTitleSchema, PeopleUpdate), t, model, SaveMode.Append, byName)
    }

    scenario(s"df-$model-nulls") { t =>
      save(rows(PersonSchema, PeopleWithNulls), t, model, options = byName)
    }

    // The hash model stores a binary column as Java's Object.toString, `[B@<identity hash>`, which differs per run.
    val allTypesRules = if (model == SqlOptionModelHash) Seq(JavaByteArrayIdentity) else Nil
    scenario(s"df-$model-all-types", allTypesRules) { t =>
      save(rows(AllTypesSchema, allTypes), t, model, options = Map(SqlOptionKeyColumn -> "id"))
    }
  }

  scenario("df-schema-wide") { t =>
    save(rows(WideSchema, Seq(wideRow)), t, SqlOptionModelHash, options = Map(SqlOptionKeyColumn -> "id"))
  }

  // RDD writers, each with and without a TTL. Lists are written from one partition: across partitions their order
  // is not defined.

  scenario("rdd-to-redis-kv") { t =>
    sc.toRedisKV(sc.parallelize(Seq(s"$t:a" -> "1", s"$t:empty" -> "", s"$t:unicode" -> "ünïcødé ✓")))
    sc.toRedisKV(sc.parallelize(Seq(s"$t:ttl" -> "expires")), Ttl)
  }

  scenario("rdd-to-redis-hash") { t =>
    sc.toRedisHASH(sc.parallelize(Seq("f1" -> "v1", "f2" -> "")), s"$t:h")
    sc.toRedisHASH(sc.parallelize(Seq("f1" -> "v1")), s"$t:ttl", Ttl)
  }

  scenario("rdd-to-redis-hashes") { t =>
    sc.toRedisHASHes(sc.parallelize(Seq(s"$t:a" -> Map("f1" -> "v1", "f2" -> "v2"), s"$t:b" -> Map("f1" -> "x"))))
    sc.toRedisHASHes(sc.parallelize(Seq(s"$t:ttl" -> Map("f" -> "v"))), Ttl)
  }

  scenario("rdd-to-redis-byte-hashes") { t =>
    sc.toRedisByteHASHes(sc.parallelize(Seq(
      bytes(s"$t:a") -> Map(bytes("text") -> bytes("v"), bytes("bytes") -> Array[Byte](-1, 0, 65)))))
    sc.toRedisByteHASHes(sc.parallelize(Seq(bytes(s"$t:ttl") -> Map(bytes("f") -> bytes("v")))), Ttl)
  }

  scenario("rdd-to-redis-zset") { t =>
    // (member, score): scores are parsed with toDouble, and members with equal scores sort by member
    sc.toRedisZSET(sc.parallelize(Seq("a" -> "1.5", "b" -> "-2", "c" -> "1e3", "d" -> "1.5", "e" -> "0.1")), s"$t:z")
    sc.toRedisZSET(sc.parallelize(Seq("a" -> "1")), s"$t:ttl", Ttl)
  }

  scenario("rdd-to-redis-set") { t =>
    sc.toRedisSET(sc.parallelize(Seq("x", "y", "z", "x")), s"$t:s")
    sc.toRedisSET(sc.parallelize(Seq("x")), s"$t:ttl", Ttl)
  }

  scenario("rdd-to-redis-list") { t =>
    sc.toRedisLIST(sc.parallelize(Seq("1", "2", "3", "2"), 1), s"$t:l")
    sc.toRedisLIST(sc.parallelize(Seq("1"), 1), s"$t:ttl", Ttl)
  }

  scenario("rdd-to-redis-lists") { t =>
    sc.toRedisLISTs(sc.parallelize(Seq(s"$t:a" -> Seq("1", "2", "3"), s"$t:b" -> Seq("x"))))
    sc.toRedisLISTs(sc.parallelize(Seq(s"$t:ttl" -> Seq("1"))), Ttl)
  }

  scenario("rdd-to-redis-byte-list") { t =>
    // toRedisByteLIST is the deprecated name of toRedisByteLISTs
    sc.toRedisByteLIST(sc.parallelize(Seq(bytes(s"$t:a") -> Seq(bytes("1"), Array[Byte](-1)))))
    sc.toRedisByteLIST(sc.parallelize(Seq(bytes(s"$t:ttl") -> Seq(bytes("1")))), Ttl)
  }

  scenario("rdd-to-redis-byte-lists") { t =>
    sc.toRedisByteLISTs(sc.parallelize(Seq(bytes(s"$t:a") -> Seq(bytes("1"), Array[Byte](-1)), bytes(s"$t:b") ->
      Seq(bytes("x")))))
    sc.toRedisByteLISTs(sc.parallelize(Seq(bytes(s"$t:ttl") -> Seq(bytes("1")))), Ttl)
  }

  scenario("rdd-to-redis-fixed-list") { t =>
    // toRedisFixedLIST takes no TTL, so its variants are trimmed (listSize 3) and untrimmed (the default, no limit)
    sc.toRedisFixedLIST(sc.parallelize(Seq("1", "2", "3", "4", "5"), 1), s"$t:trimmed", 3)
    sc.toRedisFixedLIST(sc.parallelize(Seq("1", "2", "3"), 1), s"$t:untrimmed")
  }

  // Consumer-group state after Structured Streaming reads

  scenario("stream-consumer-group") { t =>
    val key = s"$t:events"
    (1 to 5).foreach { i =>
      KeyspaceSnapshot.command(redisConfig, key, "XADD", s"$i-0", "name", s"name-$i", "age", s"${20 + i}")
    }
    // the default group, `spark-source`, started from the beginning with `stream.offsets`
    val fromStart = readStreamBatch(key, StreamSchema,
      Map(StreamOptionStreamOffsets -> streamOffsets(key, "spark-source", "0-0")))
    assert(fromStart.length == 5)
    // a second group with no offsets, which 3.x creates at the last entry
    val fromLatest = readStreamBatch(key, StreamSchema, Map(StreamOptionGroupName -> "latest"))
    assert(fromLatest.isEmpty)
  }
}
