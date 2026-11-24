package com.redislabs.provider.redis.util

import java.util.UUID

import org.scalatest.Assertions
import redis.clients.jedis.{Jedis, ScanParams}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
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
    * How many keys on `conn`'s server match the glob `pattern`. It uses `SCAN ... MATCH`, not `KEYS` (spec section
    * 2.14), and counts each key once, since `SCAN` can return a key more than once. On a cluster it counts one node.
    */
  def countKeys(conn: Jedis, pattern: String): Long = {
    val params = new ScanParams().`match`(pattern).count(1000)
    val keys = mutable.Set[String]()
    var cursor = ScanParams.SCAN_POINTER_START
    do {
      val page = conn.scan(cursor, params)
      keys ++= page.getResult.asScala
      cursor = page.getCursor
    } while (cursor != ScanParams.SCAN_POINTER_START)
    keys.size.toLong
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
    * The first exception of type T in the chain of causes of e, starting with e itself. Fails the test if there is
    * none.
    */
  def findCause[T <: Throwable](e: Throwable)(implicit manifest: Manifest[T]): T =
    Iterator.iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .collectFirst { case t if manifest.runtimeClass.isInstance(t) => t.asInstanceOf[T] }
      .getOrElse(Assertions.fail(s"No ${manifest.runtimeClass.getName} among the causes of $e"))
}
