package com.redislabs.provider.redis.evals

import com.redislabs.provider.redis.SparkRedisSuite
import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.TestUtils.{generateTableName, interceptSparkErr}
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.redis.{RedisFormat, SqlOptionTableName}
import org.scalatest.Matchers

import scala.collection.JavaConverters._

/** Acceptance test for eval task 06 (write.mode=replace, #351). Copied into src/test only after the agent finishes. */
class WriteModeEvalSuite extends SparkRedisSuite with RedisStandaloneEnv with Matchers {

  import TestSqlImplicits._

  private def write(table: String, name: String, age: String, options: Map[String, String]): Unit = {
    val writer = Seq(("1", name, age)).toDF("id", "name", "age").write.format(RedisFormat)
      .option(SqlOptionTableName, table)
      .option("key.column", "id")
      .mode(SaveMode.Append)
    options.foldLeft(writer) { case (w, (k, v)) => w.option(k, v) }.save()
  }

  private def stored(table: String): Map[String, String] =
    withConnection(redisConfig.initialHost.connect()) { conn =>
      conn.hgetAll(s"$table:1").asScala.toMap
    }

  private def ttl(table: String): Long =
    withConnection(redisConfig.initialHost.connect()) { conn =>
      conn.ttl(s"$table:1").longValue()
    }

  test("write.mode=replace drops the fields the new row doesn't have") {
    val table = generateTableName("evalmode")
    write(table, "John", "30", Map())
    write(table, "Peter", null, Map("write.mode" -> "replace"))
    stored(table) shouldBe Map("name" -> "Peter")
  }

  test("the default keeps them, as 3.x always has") {
    val table = generateTableName("evalmode")
    write(table, "John", "30", Map())
    write(table, "Peter", null, Map())
    stored(table) shouldBe Map("name" -> "Peter", "age" -> "30")
  }

  test("write.mode=merge is the default") {
    val table = generateTableName("evalmode")
    write(table, "John", "30", Map())
    write(table, "Peter", null, Map("write.mode" -> "merge"))
    stored(table) shouldBe Map("name" -> "Peter", "age" -> "30")
  }

  test("write.mode=replace with a ttl leaves the row with that ttl") {
    val table = generateTableName("evalmode")
    write(table, "John", "30", Map())
    write(table, "Peter", null, Map("write.mode" -> "replace", "ttl" -> "100"))
    stored(table) shouldBe Map("name" -> "Peter")
    ttl(table) should (be > 0L and be <= 100L)
  }

  test("an unknown write.mode fails and names the option") {
    val table = generateTableName("evalmode")
    val e = interceptSparkErr[Exception] {
      write(table, "John", "30", Map("write.mode" -> "upsert-ish"))
    }
    val text = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString("\n")
    text should include("write.mode")
  }
}
