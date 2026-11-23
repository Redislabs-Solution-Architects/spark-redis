package com.redislabs.provider.redis.df.benchmark

import java.util.UUID

import scala.jdk.CollectionConverters._

import com.redislabs.provider.redis.util.ConnectionUtils.{JedisExt, XINFO, withConnection}
import com.redislabs.provider.redis.util.Person
import com.redislabs.provider.redis.util.TestUtils.generateTableName
import com.redislabs.provider.redis.{RedisBenchmarks, SparkRedisSuite}
import org.apache.spark.sql.redis.{StreamOptionStreamKeys, StreamOptionStreamOffsets}
import org.apache.spark.sql.streaming.Trigger
import org.scalatest.Matchers
import redis.clients.jedis.StreamEntryID

/**
  * Stream throughput: reads 1,000,000 entries through the Structured Streaming source in one batch, from the start of
  * the stream, into the `noop` sink. The source's options are 3.x's defaults.
  */
trait StreamBenchmarkSuite extends SparkRedisSuite with Matchers with RedisBenchmarks {

  /** `standalone` or `cluster`. */
  def topology: String

  private val Entries = 1000000
  private val SyncEvery = 10000

  private val streamKey = generateTableName("stream-benchmark")

  override def beforeAll(): Unit = {
    super.beforeAll()
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      val pipeline = conn.pipelined()
      val fields = Person.dataMaps.head.asJava
      (1 to Entries).foreach { i =>
        pipeline.xadd(streamKey, new StreamEntryID(i.toLong, 0L), fields)
        if (i % SyncEvery == 0) pipeline.sync()
      }
      pipeline.sync()
    }
  }

  override def afterAll(): Unit = {
    try withConnection(redisConfig.connectionForKey(streamKey))(_.unlink(streamKey)) finally super.afterAll()
  }

  test(s"Read $Entries stream entries through the Structured Streaming source") {
    val offsets = s"""{"offsets":{"$streamKey":{"groupName":"spark-source","offset":"0-0"}}}"""
    time(s"${System.currentTimeMillis()}, Stream, $topology", s"$topology/stream/read-$Entries") {
      val query = spark.readStream
        .format("redis")
        .schema(Person.fullSchema)
        .option(StreamOptionStreamKeys, streamKey)
        .option(StreamOptionStreamOffsets, offsets)
        .load()
        .writeStream
        .format("noop")
        .option("checkpointLocation", s"target/benchmark-checkpoints/${UUID.randomUUID()}")
        .trigger(Trigger.Once())
        .start()
      query.awaitTermination()
    }
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      val group = conn.xinfo(XINFO.SubCommandGroups, streamKey)("spark-source").asInstanceOf[Map[String, Any]]
      group(XINFO.LastDeliveredId) shouldBe s"$Entries-0"
    }
  }
}
