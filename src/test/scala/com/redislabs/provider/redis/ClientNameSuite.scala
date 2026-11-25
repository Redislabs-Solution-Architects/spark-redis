package com.redislabs.provider.redis

import java.util.UUID

import com.redislabs.provider.redis.RedisConfig.{ClientNameConfKey, ClientNameOption}
import com.redislabs.provider.redis.df.RedisDataframeSuite
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.TestUtils.{findCause, generateTableName}
import org.apache.spark.sql.redis.{StreamOptionStreamKeys, StreamOptionStreamOffsets}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import redis.clients.jedis.exceptions.{JedisConnectionException, JedisDataException}
import redis.clients.jedis.{Jedis, StreamEntryID}

import scala.jdk.CollectionConverters._

/**
  * `spark.redis.client.name` and the `client.name` option name every connection the connector opens, as
  * `CLIENT LIST` shows it (#398). The connector's connections are the ones `CLIENT SETINFO` marked with its library
  * name. [[ClientNameConfigSuite]] covers how the setting and the option become an endpoint's client name.
  *
  * Each test checks every connection of the connector's that it used, new or reused, and uses a name no other test
  * uses.
  */
trait ClientNameSuite extends RedisDataframeSuite {

  /** Set before the SparkContext starts. Only the first test connects with it. */
  private val ConfName = uniqueName("conf")

  override def beforeAll(): Unit = {
    conf.set(ClientNameConfKey, ConfName)
    super.beforeAll()
  }

  private def uniqueName(prefix: String): String =
    s"spark-redis-test-$prefix-" + UUID.randomUUID().toString.take(8)

  /** Each node once: `redisConfig.nodes` lists a cluster node once per slot range it serves. */
  private def nodes: Seq[RedisEndpoint] =
    redisConfig.nodes.toSeq.map(_.endpoint).groupBy(e => (e.host, e.port)).values.map(_.head).toSeq

  /** Runs `body` on a plain connection to `endpoint`, not a pooled one, so it isn't one of the connector's. */
  private def admin[A](endpoint: RedisEndpoint)(body: Jedis => A): A = {
    val conn = new Jedis(endpoint.host, endpoint.port)
    try {
      if (endpoint.user != null) {
        conn.auth(endpoint.user, endpoint.auth)
      } else if (endpoint.auth != null) {
        conn.auth(endpoint.auth)
      }
      body(conn)
    } finally {
      conn.close()
    }
  }

  /** One of the connector's connections, as a line of `CLIENT LIST` shows it. */
  private case class Client(node: String, id: String, name: String, commands: Long)

  /** The connector's connections on every node: the `CLIENT LIST` entries with its library name. */
  private def connectorClients(): Set[Client] =
    nodes.flatMap { endpoint =>
      val lines = admin(endpoint)(_.clientList()).split("\n").toSeq.map(_.trim).filter(_.nonEmpty)
      lines.map(fields).filter(_.get("lib-name").contains(RedisClientLibraryInfo.libName)).map { f =>
        val commands =
          f.getOrElse("tot-cmds", fail(s"CLIENT LIST has no tot-cmds (Redis 8.2 has it, 7.4 doesn't): $f")).toLong
        Client(s"${endpoint.host}:${endpoint.port}", f("id"), f("name"), commands)
      }
    }.toSet

  /** The `key=value` fields of a `CLIENT LIST` line. No value has a space: Redis refuses names that have one. */
  private def fields(line: String): Map[String, String] =
    line.split(' ').map { field =>
      val eq = field.indexOf('=')
      field.substring(0, eq) -> field.substring(eq + 1)
    }.toMap

  /**
    * The connector's connections that `body` used: those it opened, and those already open that ran more commands
    * after it than before. A pooled connection opened earlier, without the name, is counted when `body` reuses it.
    * Connection ids are never reused.
    */
  private def used(body: => Unit): Set[Client] = {
    val before = connectorClients().map(c => (c.node, c.id) -> c.commands).toMap
    body
    connectorClients().filter(c => before.get((c.node, c.id)).forall(_ < c.commands))
  }

  private def allNamed(clients: Set[Client], name: String): Unit =
    withClue(s"connections used: $clients: ") {
      clients should not be empty
      clients.map(_.name) shouldBe Set(name)
    }

  test("spark.redis.client.name names every connection of the RDD functions and of a DataFrame without the option") {
    val prefix = generateTableName("client-name-conf")
    val clients = used {
      sc.toRedisKV(sc.parallelize((1 to 20).map(i => s"$prefix:$i" -> i.toString), 4))
      sc.fromRedisKV(s"$prefix:*").collect() should have size 20
      writeDf(s"$prefix-df")
      loadAndVerifyDf(s"$prefix-df")
    }
    allNamed(clients, ConfName)
  }

  test("the client.name option names every connection of a DataFrame write and read, not spark.redis.client.name") {
    val name = uniqueName("option")
    val table = generateTableName("client-name-option")
    val clients = used {
      writeDf(table, Map(ClientNameOption -> name))
      loadAndVerifyDf(table, Map(ClientNameOption -> name))
    }
    allNamed(clients, name)
  }

  test("the client.name option names every connection of a Structured Streaming source") {
    val name = uniqueName("stream")
    val streamKey = generateTableName("client-name-stream")
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      (1 to 5).foreach(i => conn.xadd(streamKey, new StreamEntryID(0, i), Map("name" -> s"p$i").asJava))
    }
    val fromStart = s"""{"offsets":{"$streamKey":{"groupName":"spark-source","offset":"0-0"}}}"""
    val queryName = generateTableName("client_name_stream_")
    val clients = used {
      val query = spark.readStream
        .format("redis")
        .schema(StructType(Seq(StructField("name", StringType))))
        .option(StreamOptionStreamKeys, streamKey)
        .option(StreamOptionStreamOffsets, fromStart)
        .option(ClientNameOption, name)
        .load()
        .writeStream
        .format("memory")
        .queryName(queryName)
        .start()
      try {
        query.processAllAvailable()
      } finally {
        query.stop()
      }
      spark.table(queryName).count() shouldBe 5
    }
    allNamed(clients, name)
  }

  test("a RedisConfig gives its client name to every node it finds, and to the connections it opens to find them") {
    val name = uniqueName("nodes")
    var config: RedisConfig = null
    val clients = used {
      config = new RedisConfig(redisConfig.initialHost.copy(clientName = name))
    }
    allNamed(clients, name)
    config.nodes.map(_.endpoint.clientName).toSet shouldBe Set(name)
    config.hosts.map(_.endpoint.clientName).toSet shouldBe Set(name)
  }

  test("every connection from an endpoint's pool has the endpoint's client name") {
    val name = uniqueName("pool")
    val endpoint = redisConfig.initialHost.copy(clientName = name)
    val conns = (1 to 3).map(_ => endpoint.connect())
    try {
      conns.map(_.clientGetname()) shouldBe Seq(name, name, name)
    } finally {
      conns.foreach(_.close())
    }
  }

  test("without a client name a connection has none, as before") {
    redisConfig.initialHost.clientName shouldBe null
    withConnection(redisConfig.initialHost.connect())(_.clientGetname()) shouldBe null
  }

  test("a server that refuses CLIENT SETNAME fails the connection with its reply") {
    val node = redisConfig.initialHost
    val user = uniqueName("no-setname")
    admin(node)(_.aclSetUser(user, "on", ">p1pp0", "~*", "&*", "+@all", "-client|setname"))
    try {
      val endpoint = node.copy(user = user, auth = "p1pp0", clientName = uniqueName("refused"))
      val e = intercept[JedisConnectionException](endpoint.connect())
      findCause[JedisDataException](e).getMessage should include("client|setname")
    } finally {
      admin(node)(_.aclDelUser(user))
    }
  }
}
