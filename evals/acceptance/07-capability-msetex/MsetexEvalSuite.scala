package com.redislabs.provider.redis.evals

import com.redislabs.provider.redis.SparkRedisSuite
import com.redislabs.provider.redis.env.{RedisClusterEnv, RedisStandaloneEnv}
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.TestUtils.generateTableName
import org.apache.spark.sql.redis.{RedisFormat, SqlOptionTableName}
import org.scalatest.Matchers
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.util.SafeEncoder

import scala.collection.JavaConverters._

/**
  * Acceptance test for eval task 07 (a capability check with a fallback). Copied into src/test only after the agent
  * finishes. It runs the same checks on any server: which commands the write used depends on whether the server
  * has MSETEX (8.4+), so it passes on redis:8.2 only through the fallback, and on redis:8.6 only through MSETEX.
  */
trait MsetexEvalSuite extends SparkRedisSuite with Matchers {

  import TestSqlImplicits._

  private val rows = 200
  private val ttlSeconds = 300

  /** Each Redis server once. In a cluster, `hosts` lists a node once per slot range. */
  private def eachNode[T](f: redis.clients.jedis.Jedis => T): Seq[T] =
    redisConfig.hosts.toSeq.map(_.endpoint).distinct.map(endpoint => withConnection(endpoint.connect())(f))

  /** Jedis 3.9 has no COMMAND in Protocol.Command. */
  private object Command extends ProtocolCommand {
    override def getRaw: Array[Byte] = SafeEncoder.encode("COMMAND")
  }

  private def hasMsetex: Boolean = withConnection(redisConfig.initialHost.connect()) { conn =>
    val reply = conn.sendCommand(Command, "INFO", "MSETEX").asInstanceOf[java.util.List[AnyRef]]
    reply.asScala.headOption.exists(_ != null)
  }

  /** Calls per command since the last CONFIG RESETSTAT, summed over every node. */
  private def calls(): Map[String, Long] =
    eachNode { conn =>
      conn.info("commandstats").split("\r?\n").toSeq.collect {
        case line if line.startsWith("cmdstat_") =>
          val name = line.stripPrefix("cmdstat_").takeWhile(_ != ':')
          val count = "calls=(\\d+)".r.findFirstMatchIn(line).map(_.group(1).toLong).getOrElse(0L)
          name -> count
      }.toMap
    }.foldLeft(Map.empty[String, Long]) { (acc, m) =>
      m.foldLeft(acc) { case (a, (k, v)) => a.updated(k, a.getOrElse(k, 0L) + v) }
    }

  test("a binary-model write with a ttl uses MSETEX when the server has it, else SET ... EX") {
    val table = generateTableName("evalcap")
    val df = (1 to rows).map(i => (s"id$i", s"value$i")).toDF("id", "value")
    eachNode(_.configResetStat())

    df.write.format(RedisFormat)
      .option(SqlOptionTableName, table)
      .option("model", "binary")
      .option("key.column", "id")
      .option("ttl", ttlSeconds.toString)
      .save()

    val used = calls()
    withClue(s"commands used: $used\n") {
      used.getOrElse("setex", 0L) shouldBe 0L
      used.getOrElse("psetex", 0L) shouldBe 0L
      // The table's schema key is one SET of its own, so the row writes are what's left.
      if (hasMsetex) {
        used.getOrElse("msetex", 0L) should be > 0L
        used.getOrElse("set", 0L) should be < rows.toLong
      } else {
        used.getOrElse("set", 0L) should be >= rows.toLong
      }
    }

    val keys = (1 to rows).map(i => s"$table:id$i")
    keys.foreach { key =>
      withConnection(redisConfig.connectionForKey(key)) { conn =>
        withClue(s"$key: ") {
          conn.exists(key).booleanValue() shouldBe true
          conn.ttl(key).longValue() should (be > 0L and be <= ttlSeconds.toLong)
        }
      }
    }
  }
}

class MsetexEvalStandaloneSuite extends MsetexEvalSuite with RedisStandaloneEnv

class MsetexEvalClusterSuite extends MsetexEvalSuite with RedisClusterEnv
