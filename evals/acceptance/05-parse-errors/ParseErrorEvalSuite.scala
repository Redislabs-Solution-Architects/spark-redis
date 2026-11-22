package com.redislabs.provider.redis.evals

import com.redislabs.provider.redis.SparkRedisSuite
import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.TestUtils.{generateTableName, interceptSparkErr}
import org.apache.spark.SparkException
import org.apache.spark.sql.redis.{RedisFormat, SqlOptionTableName}
import org.apache.spark.sql.types._
import org.scalatest.Matchers

import scala.collection.JavaConverters._

/** Acceptance test for eval task 05 (clear parse errors, #358). Copied into src/test only after the agent finishes. */
class ParseErrorEvalSuite extends SparkRedisSuite with RedisStandaloneEnv with Matchers {

  private def writeHash(key: String, fields: Map[String, String]): Unit =
    withConnection(redisConfig.initialHost.connect()) { conn =>
      conn.hset(key, fields.asJava)
    }

  private def read(table: String, schema: StructType) =
    spark.read.format(RedisFormat).schema(schema).option(SqlOptionTableName, table).load()

  /** The messages of the exception chain, leaving out Spark's own wrapper, whose message holds the stack trace. */
  private def messages(e: Throwable): String =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null)
      .filterNot(_.isInstanceOf[SparkException])
      .map(t => Option(t.getMessage).getOrElse(""))
      .mkString("\n")

  private def checkError(dataType: DataType, typeWord: String, bad: String): Unit = {
    val table = generateTableName("evalparse")
    val key = s"$table:1"
    writeHash(key, Map("name" -> "x", "when" -> bad))
    val schema = StructType(Seq(StructField("name", StringType), StructField("when", dataType)))
    val e = interceptSparkErr[SparkException] {
      read(table, schema).collect()
    }
    val text = messages(e)
    withClue(s"error messages were:\n$text\n") {
      text should include(key)
      text should include("when")
      text should include(bad)
      text.toLowerCase should include(typeWord)
    }
  }

  test("an unparsable date names the key, field, value and type") {
    checkError(DateType, "date", "not-a-date")
  }

  test("an unparsable timestamp names the key, field, value and type") {
    checkError(TimestampType, "timestamp", "yesterday at noon")
  }

  test("valid dates and timestamps still parse") {
    val table = generateTableName("evalparse")
    writeHash(s"$table:1", Map("d" -> "2024-02-29", "t" -> "2024-02-29 12:34:56"))
    val schema = StructType(Seq(StructField("d", DateType), StructField("t", TimestampType)))
    val row = read(table, schema).collect().head
    row.getDate(0).toString shouldBe "2024-02-29"
    row.getTimestamp(1).toString shouldBe "2024-02-29 12:34:56.0"
  }
}
