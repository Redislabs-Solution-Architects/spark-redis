package com.redislabs.provider.redis.util

import java.nio.ByteBuffer
import java.util.UUID

import com.redislabs.provider.redis.RedisConfig
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
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
    * How many keys match the glob `pattern` across the whole deployment `config` points at: the one server when
    * standalone, every primary when clustered. Replicas are skipped so no key is counted twice, and a primary that
    * owns several slot ranges is scanned once.
    */
  def countKeys(config: RedisConfig, pattern: String): Long = {
    val primaries = config.hosts.map(node => (node.endpoint.host, node.endpoint.port) -> node.endpoint).toMap.values
    primaries.map(endpoint => withConnection(endpoint.connect())(conn => countKeys(conn, pattern))).sum
  }

  /**
    * How many keys on the server behind `conn` match the glob `pattern`. Uses SCAN ... MATCH, not KEYS (spec section
    * 2.14), and counts each key once even when SCAN returns it more than once. On a cluster it counts one node only;
    * use the `RedisConfig` overload to count across the cluster.
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

  /**
    * The first exception of type T in the chain of causes of e, starting with e itself. Fails the test if there is
    * none.
    */
  def findCause[T <: Throwable](e: Throwable)(implicit manifest: Manifest[T]): T =
    Iterator.iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .collectFirst { case t if manifest.runtimeClass.isInstance(t) => t.asInstanceOf[T] }
      .getOrElse(Assertions.fail(s"No ${manifest.runtimeClass.getName} among the causes of $e"))
}
