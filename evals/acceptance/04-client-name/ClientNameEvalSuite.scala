package com.redislabs.provider.redis.evals

import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.TestUtils.generateTableName
import com.redislabs.provider.redis.{RedisConfig, SparkRedisSuite}
import org.apache.spark.sql.redis.{RedisFormat, SqlOptionTableName}
import org.scalatest.Matchers

/** Acceptance test for eval task 04 (client.name, #398). Copied into src/test only after the agent finishes. */
class ClientNameEvalSuite extends SparkRedisSuite with RedisStandaloneEnv with Matchers {

  import TestSqlImplicits._

  private val confName = "eval-client"
  private val sourceName = "eval-source"

  override def beforeAll(): Unit = {
    conf.set("spark.redis.client.name", confName)
    super.beforeAll()
  }

  private def clientNames(): Seq[String] =
    withConnection(redisConfig.initialHost.connect()) { conn =>
      conn.clientList().split("\n").toSeq.flatMap { line =>
        line.split(" ").find(_.startsWith("name=")).map(_.stripPrefix("name="))
      }
    }

  test("a connection made from spark.redis.client.name carries that name") {
    withConnection(RedisConfig.fromSparkConf(conf).initialHost.connect()) { conn =>
      conn.clientGetname() shouldBe confName
    }
  }

  test("a DataFrame write connects with the session's client name") {
    Seq(("1", "a")).toDF("id", "v").write.format(RedisFormat)
      .option(SqlOptionTableName, generateTableName("evalname"))
      .option("key.column", "id")
      .save()
    clientNames() should contain(confName)
  }

  test("the client.name option overrides the session setting for one source") {
    Seq(("1", "a")).toDF("id", "v").write.format(RedisFormat)
      .option(SqlOptionTableName, generateTableName("evalname"))
      .option("key.column", "id")
      .option("client.name", sourceName)
      .save()
    clientNames() should contain(sourceName)
  }

  test("without the setting, connections have no client name, as before") {
    withConnection(redisConfig.initialHost.connect()) { conn =>
      conn.clientGetname() shouldBe null
    }
  }
}
