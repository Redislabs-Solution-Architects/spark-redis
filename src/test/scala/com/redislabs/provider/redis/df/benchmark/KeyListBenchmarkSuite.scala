package com.redislabs.provider.redis.df.benchmark

import com.redislabs.provider.redis.df.RedisDataframeSuite
import com.redislabs.provider.redis.rdd.Keys
import com.redislabs.provider.redis.util.Person
import com.redislabs.provider.redis.util.PipelineUtils.foreachWithPipeline
import com.redislabs.provider.redis.util.TestUtils.generateTableName
import com.redislabs.provider.redis.{ReadWriteConfig, RedisBenchmarks, toRedisContext}
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.redis._
import redis.clients.jedis.PipelineBase

/**
  * Point lookups: reads 10,000 rows by an explicit key list from a 1,000,000-row `hash` table. 3.x has no
  * DataFrame option for a key list, so this is the RDD reader `fromRedisHash(keys)`.
  */
trait KeyListBenchmarkSuite extends RedisDataframeSuite with RedisBenchmarks {

  /** `standalone` or `cluster`. */
  def topology: String

  private val Rows = 1000000
  private val Lookups = 10000
  private val FieldsPerRow = 3 // age, address and salary; the key column, name, is not stored
  private val Partitions = 8

  private val tableName = generateTableName("keylist")

  override def beforeAll(): Unit = {
    super.beforeAll()
    val people = sc.parallelize(0 until Rows, Partitions).map(i => Person(s"John-$i", 30, "60 Wall Street", 150.5))
    spark.createDataFrame(people).write.format(RedisFormat)
      .option(SqlOptionModel, SqlOptionModelHash)
      .option(SqlOptionTableName, tableName)
      .option(SqlOptionKeyColumn, "name")
      .mode(SaveMode.Overwrite)
      .save()
  }

  override def afterAll(): Unit = {
    try {
      val hosts = redisConfig.hosts
      implicit val readWriteConfig: ReadWriteConfig = ReadWriteConfig.Default
      sc.fromRedisKeyPattern(s"$tableName:*").foreachPartition { partition =>
        Keys.groupKeysByNode(hosts, partition).foreach { case (node, keys) =>
          val conn = node.connect()
          foreachWithPipeline(conn, keys) { (pipeline, key) =>
            (pipeline: PipelineBase).del(key) // fix ambiguous reference to overloaded definition
          }
          conn.close()
        }
      }
    } finally {
      super.afterAll()
    }
  }

  test(s"Read $Lookups keys by key list from a $Rows-row table") {
    val keys = (0 until Lookups).map(i => s"$tableName:John-${i * (Rows / Lookups)}").toArray
    val tag = s"${System.currentTimeMillis()}, KeyList, $topology"
    val fields = time(tag, s"$topology/hash/keylist-$Lookups-of-$Rows/read") {
      sc.fromRedisHash(keys, Partitions).count()
    }
    fields shouldBe Lookups.toLong * FieldsPerRow
  }
}
