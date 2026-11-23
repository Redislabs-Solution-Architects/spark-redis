package com.redislabs.provider.redis.rdd.cluster

import java.util.UUID

import scala.collection.JavaConverters._

import com.redislabs.provider.redis._
import com.redislabs.provider.redis.env.RedisClusterEnv
import org.scalatest.Matchers
import redis.clients.jedis.Jedis
import redis.clients.jedis.params.MigrateParams
import redis.clients.jedis.util.JedisClusterCRC16

/**
  * A slot moves to another primary after a `RedisConfig` has read the cluster topology, and the connector then
  * writes keys in that slot with the same `RedisConfig`. The cluster answers the old owner with `MOVED`, and every
  * key must still be stored (the #347 family: writes lost without an error).
  *
  * The test moves the slot the way `redis-cli --cluster reshard` does (`CLUSTER SETSLOT … IMPORTING`/`MIGRATING`,
  * `MIGRATE`, then `CLUSTER SETSLOT … NODE` on every primary) and moves it back in a `finally` block, so the suites
  * that run next in this JVM see the original layout.
  */
class MovedWriteClusterSuite extends SparkRedisSuite with RedisClusterEnv with Matchers {

  private val HashTag = "{moved-write}"
  private val Slot = JedisClusterCRC16.getSlot(HashTag)
  private val KeyCount = 100
  private val SeedKeyCount = 5
  private val MigrateBatch = 100
  private val MigrateTimeoutMs = 5000
  private val AgreeTimeoutMs = 10000

  /** A primary, from `CLUSTER NODES`. `slots` holds the ranges it owns, without importing or migrating slots. */
  private case class Primary(id: String, host: String, port: Int, slots: Seq[(Int, Int)]) {
    def owns(slot: Int): Boolean = slots.exists { case (start, end) => start <= slot && slot <= end }
  }

  private def connect(node: Primary): Jedis = RedisEndpoint(node.host, node.port).connect()

  private def withNode[A](node: Primary)(body: Jedis => A): A = {
    val conn = connect(node)
    try body(conn)
    finally conn.close()
  }

  /** The primaries as `node` sees them. */
  private def primaries(node: Jedis): Seq[Primary] = {
    node.clusterNodes().split("\n").map(_.trim).filter(_.nonEmpty).toSeq.flatMap { line =>
      val fields = line.split(" ")
      // <id> <ip:port@cport[,hostname]> <flags> <primary> <ping> <pong> <epoch> <link> <slot> <slot> ...
      val address = fields(1).takeWhile(c => c != '@' && c != ',')
      val colon = address.lastIndexOf(':')
      val slots = fields.drop(8).filterNot(_.startsWith("[")).map { range =>
        range.split("-") match {
          case Array(start, end) => (start.toInt, end.toInt)
          case Array(single) => (single.toInt, single.toInt)
        }
      }
      if (fields(2).split(",").contains("master")) {
        Some(Primary(fields(0), address.substring(0, colon), address.substring(colon + 1).toInt, slots.toSeq))
      } else {
        None
      }
    }
  }

  private def clusterPrimaries(): Seq[Primary] = {
    val conn = RedisEndpoint(redisHost, redisPort).connect()
    try primaries(conn)
    finally conn.close()
  }

  /** Waits until every primary says `owner` holds `slot`. */
  private def awaitOwner(slot: Int, owner: Primary, all: Seq[Primary]): Unit = {
    val deadline = System.currentTimeMillis() + AgreeTimeoutMs
    def agreed: Boolean = all.forall { node =>
      withNode(node)(conn => primaries(conn).exists(p => p.id == owner.id && p.owns(slot)))
    }
    while (!agreed) {
      if (System.currentTimeMillis() > deadline) {
        fail(s"the primaries did not agree within ${AgreeTimeoutMs}ms that slot $slot is on ${owner.port}")
      }
      Thread.sleep(50)
    }
  }

  /** True if `node` says it owns `slot` itself. */
  private def ownsItself(node: Primary, slot: Int): Boolean =
    withNode(node)(conn => primaries(conn).exists(p => p.id == node.id && p.owns(slot)))

  /**
    * Moves `slot` and all of its keys from `from` to `to`. Safe to run again, after it stopped part way or after it
    * finished: it skips `IMPORTING` once `to` owns the slot and `MIGRATING` once `from` no longer does, `MIGRATE`
    * sends only the keys still on `from`, and `SETSLOT … NODE` can be repeated.
    */
  private def moveSlot(slot: Int, from: Primary, to: Primary, all: Seq[Primary]): Unit = {
    if (!ownsItself(to, slot)) withNode(to)(_.clusterSetSlotImporting(slot, from.id))
    if (ownsItself(from, slot)) withNode(from)(_.clusterSetSlotMigrating(slot, to.id))
    withNode(from) { conn =>
      var keys = conn.clusterGetKeysInSlot(slot, MigrateBatch).asScala
      while (keys.nonEmpty) {
        conn.migrate(to.host, to.port, 0, MigrateTimeoutMs, MigrateParams.migrateParams(), keys: _*)
        keys = conn.clusterGetKeysInSlot(slot, MigrateBatch).asScala
      }
    }
    // The new owner first, so a node that redirects a client there finds it already knows it owns the slot.
    (to +: all.filterNot(_.id == to.id)).foreach(node => withNode(node)(_.clusterSetSlotNode(slot, to.id)))
    awaitOwner(slot, to, all)
  }

  test("toRedisKV stores every key after its slot moved to another primary") {
    val all = clusterPrimaries()
    val source = all.find(_.owns(Slot)).getOrElse(fail(s"no primary owns slot $Slot"))
    val target = all.find(_.id != source.id).getOrElse(fail("the cluster has only one primary"))

    val run = UUID.randomUUID().toString.replace("-", "")
    val seedKeys = (1 to SeedKeyCount).map(i => s"$HashTag:$run:seed:$i")
    val kvs = (1 to KeyCount).map(i => (s"$HashTag:$run:key:$i", s"value-$i"))

    // The connector reads the topology here, while the slot is still on `source`.
    val staleConfig = new RedisConfig(RedisEndpoint(redisHost, redisPort))
    staleConfig.getHost(kvs.head._1).endpoint.port shouldBe source.port

    withNode(source)(conn => seedKeys.foreach(key => conn.set(key, "seed")))
    try {
      moveSlot(Slot, source, target, all)
      withClue("setup: the seed keys moved with the slot:") {
        withNode(target)(conn => seedKeys.map(conn.get)) shouldBe seedKeys.map(_ => "seed")
      }

      sc.toRedisKV(sc.parallelize(kvs, 4))(staleConfig, ReadWriteConfig.fromSparkConf(sc.getConf))

      val stored = withNode(target)(conn => kvs.map { case (key, _) => key -> conn.get(key) }.toMap)
      val missing = kvs.count { case (key, value) => stored(key) != value }
      withClue(s"$missing of $KeyCount keys not stored after slot $Slot moved:") {
        stored shouldBe kvs.toMap
      }
    } finally {
      moveSlot(Slot, source, target, all) // finishes the move if it stopped part way; a no-op if it finished
      moveSlot(Slot, target, source, all)
      withNode(source)(conn => conn.del((seedKeys ++ kvs.map(_._1)): _*))
    }
  }
}
