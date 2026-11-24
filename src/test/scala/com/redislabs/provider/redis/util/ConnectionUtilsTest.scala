package com.redislabs.provider.redis.util

import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.ConnectionUtils.{JedisExt, XINFO}
import org.scalatest.{FunSuite, Matchers}
import redis.clients.jedis.StreamEntryID

import scala.jdk.CollectionConverters._

/**
  * @author The Viet Nguyen
  */
class ConnectionUtilsTest extends FunSuite with Matchers with RedisStandaloneEnv {

  test("xinfo") {
    val streamKey = TestUtils.generateRandomKey()
    val conn = redisConfig.connectionForKey(streamKey)
    val data = Map("key" -> "value").asJava
    val entryId = conn.xadd(streamKey, new StreamEntryID(0, 1), data)
    val info = conn.xinfo(XINFO.SubCommandStream, streamKey)
    info.get(XINFO.LastGeneratedId) shouldBe Some(entryId.toString)
  }

  test("xinfo GROUPS reads a group whose entries-read Redis reports as nil") {
    val streamKey = TestUtils.generateRandomKey()
    val conn = redisConfig.connectionForKey(streamKey)
    try {
      val data = Map("key" -> "value").asJava
      (1 to 3).foreach(i => conn.xadd(streamKey, new StreamEntryID(i.toLong, 0L), data))
      conn.xgroupCreate(streamKey, "group", new StreamEntryID(0, 0), false)
      // what RedisSource does after each batch: Redis then no longer knows how many entries the group read
      conn.xgroupSetID(streamKey, "group", new StreamEntryID(3, 0))
      val group = conn.xinfo(XINFO.SubCommandGroups, streamKey)("group").asInstanceOf[Map[String, Any]]
      group(XINFO.LastDeliveredId) shouldBe "3-0"
      group("entries-read") shouldBe (null: Any)
    } finally conn.close()
  }

  test("xinfo STREAM reads a stream whose entries are all trimmed") {
    val streamKey = TestUtils.generateRandomKey()
    val conn = redisConfig.connectionForKey(streamKey)
    try {
      val entryId = conn.xadd(streamKey, new StreamEntryID(0, 1), Map("key" -> "value").asJava)
      conn.xtrim(streamKey, 0, false)
      val info = conn.xinfo(XINFO.SubCommandStream, streamKey)
      info.get(XINFO.LastGeneratedId) shouldBe Some(entryId.toString)
      info(XINFO.LastEntry) shouldBe (null: Any)
    } finally conn.close()
  }
}
