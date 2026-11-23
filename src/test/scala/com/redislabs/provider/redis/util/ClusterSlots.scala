package com.redislabs.provider.redis.util

import scala.collection.JavaConverters._

import com.redislabs.provider.redis.RedisEndpoint
import org.scalatest.Assertions
import redis.clients.jedis.Jedis
import redis.clients.jedis.params.MigrateParams

/**
  * Moves hash slots between the primaries of the test cluster the way `redis-cli --cluster reshard` does:
  * `CLUSTER SETSLOT … IMPORTING` and `MIGRATING`, `MIGRATE`, then `CLUSTER SETSLOT … NODE` on every primary.
  *
  * Every step can be run again, so a test's `finally` block can call [[moveSlot]] to finish a move that stopped
  * part way, then move the slot back.
  */
object ClusterSlots {

  private val MigrateBatch = 100
  private val MigrateTimeoutMs = 5000
  private val AgreeTimeoutMs = 10000

  /** A primary, from `CLUSTER NODES`. `slots` holds the ranges it owns, without importing or migrating slots. */
  case class Primary(id: String, host: String, port: Int, slots: Seq[(Int, Int)]) {
    def owns(slot: Int): Boolean = slots.exists { case (start, end) => start <= slot && slot <= end }
  }

  def withNode[A](node: Primary)(body: Jedis => A): A = {
    val conn = RedisEndpoint(node.host, node.port).connect()
    try body(conn)
    finally conn.close()
  }

  /** The cluster's primaries, as the node at `host`:`port` sees them. */
  def primaries(host: String, port: Int): Seq[Primary] = {
    val conn = RedisEndpoint(host, port).connect()
    try primaries(conn)
    finally conn.close()
  }

  /** The primaries as `node` sees them. */
  def primaries(node: Jedis): Seq[Primary] = {
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

  /** True if `node` says it owns `slot` itself. */
  def ownsItself(node: Primary, slot: Int): Boolean =
    withNode(node)(conn => primaries(conn).exists(p => p.id == node.id && p.owns(slot)))

  /**
    * Starts moving `slot` from `from` to `to`: `to` imports it and `from` migrates it. `from` still owns the slot,
    * and answers `ASK` for keys it doesn't hold. Skips each step that is already done.
    */
  def beginMove(slot: Int, from: Primary, to: Primary): Unit = {
    if (!ownsItself(to, slot)) withNode(to)(_.clusterSetSlotImporting(slot, from.id))
    if (ownsItself(from, slot)) withNode(from)(_.clusterSetSlotMigrating(slot, to.id))
  }

  /** Finishes moving `slot`: migrates the keys still on `from`, then assigns the slot to `to` on every primary. */
  def finishMove(slot: Int, from: Primary, to: Primary, all: Seq[Primary]): Unit = {
    withNode(from) { conn =>
      var keys = conn.clusterGetKeysInSlot(slot, MigrateBatch).asScala
      while (keys.nonEmpty) {
        conn.migrate(to.host, to.port, 0, MigrateTimeoutMs, MigrateParams.migrateParams(), keys.toSeq: _*)
        keys = conn.clusterGetKeysInSlot(slot, MigrateBatch).asScala
      }
    }
    // The new owner first, so a node that redirects a client there finds it already knows it owns the slot.
    (to +: all.filterNot(_.id == to.id)).foreach(node => withNode(node)(_.clusterSetSlotNode(slot, to.id)))
    awaitOwner(slot, to, all)
  }

  /** Moves `slot` and all of its keys from `from` to `to`. Safe to run again, part way through or after it ends. */
  def moveSlot(slot: Int, from: Primary, to: Primary, all: Seq[Primary]): Unit = {
    beginMove(slot, from, to)
    finishMove(slot, from, to, all)
  }

  /** Waits until every primary says `owner` holds `slot`. */
  private def awaitOwner(slot: Int, owner: Primary, all: Seq[Primary]): Unit = {
    val deadline = System.currentTimeMillis() + AgreeTimeoutMs
    def agreed: Boolean = all.forall { node =>
      withNode(node)(conn => primaries(conn).exists(p => p.id == owner.id && p.owns(slot)))
    }
    while (!agreed) {
      if (System.currentTimeMillis() > deadline) {
        Assertions.fail(s"the primaries did not agree within ${AgreeTimeoutMs}ms that slot $slot is on ${owner.port}")
      }
      Thread.sleep(50)
    }
  }
}
