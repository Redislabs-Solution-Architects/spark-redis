package com.redislabs.provider.redis.util

import java.nio.ByteBuffer
import java.util.UUID

import org.scalatest.Assertions
import redis.clients.jedis.{Jedis, ScanParams}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.Manifest

object TestUtils {

  def generateTableName(prefix: String): String = {
    // generate random table, so we can run test multiple times and not append/overwrite data
    prefix + UUID.randomUUID().toString.replace("-", "")
  }

  def generateRandomKey(): String = {
    UUID.randomUUID().toString.replace("-", "")
  }

  /**
    * A wrapper of Assertions.intercept() that suppresses spark errors in the logs.
    * It makes easier to analyse unit tests output.
    */
  def interceptSparkErr[T <: AnyRef](f: => Any)(implicit manifest: Manifest[T]): T = {
    // turn off spark logger
    val logger = org.apache.log4j.Logger.getLogger("org")
    val levelBefore = logger.getLevel
    logger.setLevel(org.apache.log4j.Level.OFF)

    // delegate interception
    val interceptRes = Assertions.intercept(f)

    // revert logger
    logger.setLevel(levelBefore)

    interceptRes
  }

  /**
    * How many keys on the server behind `conn` match the glob `pattern`. Uses SCAN ... MATCH, not KEYS (spec section
    * 2.14), and counts each key once even when SCAN returns it more than once. On a cluster it counts one node only.
    */
  def countKeys(conn: Jedis, pattern: String): Long = {
    val params = new ScanParams().`match`(pattern).count(1000)
    val keys = mutable.Set[ByteBuffer]()
    var cursor = ScanParams.SCAN_POINTER_START_BINARY
    var done = false
    while (!done) {
      val reply = conn.scan(cursor, params)
      reply.getResult.asScala.foreach(key => keys += ByteBuffer.wrap(key))
      cursor = reply.getCursorAsBytes
      done = reply.isCompleteIteration
    }
    keys.size.toLong
  }
}
