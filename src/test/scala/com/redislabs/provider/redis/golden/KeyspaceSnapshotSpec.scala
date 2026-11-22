package com.redislabs.provider.redis.golden

import java.nio.charset.StandardCharsets.UTF_8

import com.redislabs.provider.redis.env.{Env, RedisClusterEnv, RedisContainers, RedisStandaloneEnv}
import com.redislabs.provider.redis.golden.KeyspaceSnapshot._
import org.apache.commons.lang3.SerializationUtils
import org.apache.spark.sql.types.{IntegerType, StringType, StructType}
import org.json4s._
import org.scalatest.{BeforeAndAfterEach, FunSuite, Matchers}
import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.util.SafeEncoder

// scalastyle:off multiple.string.literals magic.number

/** The snapshot format and its comparison rules, without Redis. */
class KeyspaceSnapshotSpec extends FunSuite with Matchers {

  private def entry(key: String, value: String, ttl: Ttl = NoTtl, keyType: String = "string"): Entry =
    Entry(key, keyType, ttl, stringValue(value.getBytes(UTF_8)))

  test("32-hex key segments become <uuid>") {
    normalizeKey("t:0123456789abcdef0123456789ABCDEF") shouldBe "t:<uuid>"
    normalizeKey("0123456789abcdef0123456789abcdef:x:0123456789abcdef0123456789abcdef") shouldBe "<uuid>:x:<uuid>"
    normalizeKey("t:0123456789abcdef0123456789abcde") shouldBe "t:0123456789abcdef0123456789abcde"
    normalizeKey("t0123456789abcdef0123456789abcdef") shouldBe "t0123456789abcdef0123456789abcdef"
    normalizeKey("t:0123456789abcdef0123456789abcdeg") shouldBe "t:0123456789abcdef0123456789abcdeg"
  }

  test("keys that share a <uuid> name match regardless of order") {
    val expected = Snapshot(Seq(entry("t:<uuid>", "a"), entry("t:<uuid>", "b")))
    compare(expected, Snapshot(Seq(entry("t:<uuid>", "b"), entry("t:<uuid>", "a")))) shouldBe empty
    val differences = compare(expected, Snapshot(Seq(entry("t:<uuid>", "a"), entry("t:<uuid>", "a"))))
    differences should have size 2
    differences.head should startWith("missing: ")
    differences(1) should startWith("unexpected: ")
    compare(expected, Snapshot(Seq(entry("t:<uuid>", "a")))) should have size 1
  }

  test("a TTL matches within 5 seconds, and none only matches none") {
    ttlMatches(NoTtl, NoTtl) shouldBe true
    ttlMatches(TtlSeconds(3600), TtlSeconds(3600)) shouldBe true
    ttlMatches(TtlSeconds(3600), TtlSeconds(3595)) shouldBe true
    ttlMatches(TtlSeconds(3600), TtlSeconds(3605)) shouldBe true
    ttlMatches(TtlSeconds(3600), TtlSeconds(3594)) shouldBe false
    ttlMatches(TtlSeconds(3600), TtlSeconds(3606)) shouldBe false
    ttlMatches(NoTtl, TtlSeconds(3600)) shouldBe false
    ttlMatches(TtlSeconds(3600), NoTtl) shouldBe false

    val expected = Snapshot(Seq(entry("k", "v", TtlSeconds(60))))
    compare(expected, Snapshot(Seq(entry("k", "v", TtlSeconds(57))))) shouldBe empty
    compare(expected, Snapshot(Seq(entry("k", "v", TtlSeconds(50))))) shouldBe Seq("k: ttl 60s, got 50s")
    compare(expected, Snapshot(Seq(entry("k", "v")))) shouldBe Seq("k: ttl 60s, got none")
  }

  test("<uuid> keys pair up within the TTL tolerance") {
    val expected = Snapshot(Seq(entry("t:<uuid>", "a", TtlSeconds(100)), entry("t:<uuid>", "a", TtlSeconds(10))))
    val actual = Snapshot(Seq(entry("t:<uuid>", "a", TtlSeconds(8)), entry("t:<uuid>", "a", TtlSeconds(97))))
    compare(expected, actual) shouldBe empty
  }

  test("differences name the key and what differs") {
    val expected = Snapshot(Seq(entry("a", "1"), entry("b", "2"), entry("c", "3")))
    val actual = Snapshot(Seq(entry("a", "1"), entry("b", "x"), Entry("c", "hash", NoTtl, JObject()),
      entry("d", "4")))
    val differences = compare(expected, actual)
    differences should have size 4
    differences.head should startWith("b: value ")
    differences(1) shouldBe "c: type string, got hash"
    differences(2) should startWith("c: value ")
    differences(3) should startWith("unexpected: {\"key\":\"d\"")
    compare(actual, expected).last should startWith("missing: {\"key\":\"d\"")
  }

  test("strings carry base64, plus UTF-8 text when valid") {
    stringValue("héllo".getBytes(UTF_8)) shouldBe
      JObject("base64" -> JString("aMOpbGxv"), "utf8" -> JString("héllo"))
    stringValue(Array(0xff.toByte, 0x00.toByte, 0x41.toByte)) shouldBe JObject("base64" -> JString("/wBB"))
    stringValue(Array.emptyByteArray) shouldBe JObject("base64" -> JString(""), "utf8" -> JString(""))
  }

  test("a Java-serialized StructType is compared by its schema, not its bytes") {
    val schema = new StructType().add("name", StringType).add("age", IntegerType)
    val fresh = SerializationUtils.serialize(new StructType(schema.fields))
    val used = new StructType(schema.fields)
    used.fieldIndex("age") // evaluates a lazy field that Java serialization then writes
    val evaluated = SerializationUtils.serialize(used)
    fresh should not equal evaluated

    val expected = Snapshot(Seq(Entry("_spark:t:schema", "string", NoTtl, stringValue(fresh))))
    compare(expected, Snapshot(Seq(Entry("_spark:t:schema", "string", NoTtl, stringValue(evaluated))))) shouldBe
      empty
    val other = SerializationUtils.serialize(new StructType().add("name", StringType))
    compare(expected, Snapshot(Seq(Entry("_spark:t:schema", "string", NoTtl, stringValue(other))))) should
      have size 1
  }

  test("any other Java-serialized value is compared byte for byte") {
    val a = SerializationUtils.serialize(Array[AnyRef]("John", Int.box(30)))
    val b = SerializationUtils.serialize(Array[AnyRef]("John", Int.box(31)))
    (stringValue(a) \ "javaSerialized" \ "values") shouldBe JArray(List(
      JObject("class" -> JString("java.lang.String"), "value" -> JString("John")),
      JObject("class" -> JString("java.lang.Integer"), "value" -> JString("30"))))
    compare(Snapshot(Seq(Entry("k", "string", NoTtl, stringValue(a)))),
      Snapshot(Seq(Entry("k", "string", NoTtl, stringValue(b))))) should have size 1
  }

  test("JSON text round-trips and is stable") {
    val snapshot = Snapshot(Seq(entry("b", "2", TtlSeconds(10)), entry("a", "1"),
      Entry("h", "hash", NoTtl, JObject("f" -> JString("v")))))
    val text = GoldenJson.pretty(toJson(snapshot))
    fromJson(GoldenJson.parse(text)) shouldBe snapshot.sorted
    GoldenJson.pretty(toJson(fromJson(GoldenJson.parse(text)))) shouldBe text
    text should include("\"format\": \"spark-redis-keyspace-snapshot/1\"")
  }
}

/** Captures and loads against a live Redis. */
trait KeyspaceSnapshotLiveSpec extends FunSuite with Matchers with BeforeAndAfterEach with Env {

  val Prefix = "golden_spec"
  private val Patterns = Seq(s"$Prefix:*", s"{$Prefix}:*")

  private object Raw {
    final case class Command(name: String) extends ProtocolCommand {
      override val getRaw: Array[Byte] = SafeEncoder.encode(name)
    }

    /** `name key args...`, sent to the server that owns `key`. */
    def apply(key: String, name: String, args: Any*): AnyRef = routed(key, name, key +: args: _*)

    /** `name args...`, sent to the server that owns `key`. */
    def routed(key: String, name: String, args: Any*): AnyRef = {
      val conn: Jedis = redisConfig.connectionForKey(key)
      try {
        conn.sendCommand(Command(name), args.map {
          case b: Array[Byte] => b
          case other => other.toString.getBytes(UTF_8)
        }: _*)
      } finally conn.close()
    }
  }

  override def beforeEach(): Unit = KeyspaceSnapshot.delete(redisConfig, Patterns)

  override def afterEach(): Unit = KeyspaceSnapshot.delete(redisConfig, Patterns)

  private def b64(s: String): String = base64(s.getBytes(UTF_8))

  test("runs on the Redis line that redis.image names") {
    val conn = redisConfig.hosts.head.endpoint.connect()
    val hello = try {
      conn.sendCommand(Raw.Command("HELLO"), Seq.empty[Array[Byte]]: _*).asInstanceOf[java.util.List[AnyRef]]
    } finally conn.close()
    val fields: Map[String, AnyRef] = (0 until hello.size by 2).map { i =>
      new String(hello.get(i).asInstanceOf[Array[Byte]], UTF_8) -> hello.get(i + 1)
    }.toMap
    val version = new String(fields("version").asInstanceOf[Array[Byte]], UTF_8)
    val line = RedisContainers.Image.stripPrefix("redis:")
    info(s"Redis $version from ${RedisContainers.Image}")
    assert(version == line || version.startsWith(line + "."), s"${RedisContainers.Image} is running Redis $version")
  }

  test("captures every type") {
    val k = s"{$Prefix}" // one hash slot, so the stream and the rest can be checked together on a cluster
    Raw(s"$k:string", "SET", "hello")
    Raw(s"$k:binary", "SET", Array(0xff.toByte, 0x00.toByte, 0x41.toByte))
    Raw(s"$k:ttl", "SET", "v", "EX", 100)
    Raw(s"$k:hash", "HSET", "b", "2", "a", "1", "c", Array(0xff.toByte))
    Raw(s"$k:list", "RPUSH", "c", "a", "b")
    Raw(s"$k:set", "SADD", "z", "y", "x")
    Raw(s"$k:zset", "ZADD", 2, "b", 1, "c", 1, "a", "+inf", "top", -1.5, "neg")
    (1 to 3).foreach(i => Raw(s"$k:stream", "XADD", s"$i-0", "f", s"v$i"))
    Raw.routed(s"$k:stream", "XGROUP", "CREATE", s"$k:stream", "g", "0")
    Raw.routed(s"$k:stream", "XGROUP", "CREATE", s"$k:stream", "idle", "$")
    Raw.routed(s"$k:stream", "XREADGROUP", "GROUP", "g", "c1", "COUNT", 2, "STREAMS", s"$k:stream", ">")
    Raw(s"$k:row:0123456789abcdef0123456789abcdef", "SET", "first")
    Raw(s"$k:row:fedcba9876543210fedcba9876543210", "SET", "second")

    val expected = fromJson(GoldenJson.parse(
      s"""{"format": "$Format", "keys": [
         |  {"key": "$k:string", "type": "string", "ttl": "none",
         |   "value": {"base64": "${b64("hello")}", "utf8": "hello"}},
         |  {"key": "$k:binary", "type": "string", "ttl": "none", "value": {"base64": "/wBB"}},
         |  {"key": "$k:ttl", "type": "string", "ttl": 100, "value": {"base64": "${b64("v")}", "utf8": "v"}},
         |  {"key": "$k:hash", "type": "hash", "ttl": "none", "value": {"a": "1", "b": "2", "c": {"base64": "/w=="}}},
         |  {"key": "$k:list", "type": "list", "ttl": "none", "value": ["c", "a", "b"]},
         |  {"key": "$k:set", "type": "set", "ttl": "none", "value": ["x", "y", "z"]},
         |  {"key": "$k:zset", "type": "zset", "ttl": "none", "value": [
         |    {"member": "neg", "score": "-1.5"}, {"member": "a", "score": "1"}, {"member": "c", "score": "1"},
         |    {"member": "b", "score": "2"}, {"member": "top", "score": "inf"}]},
         |  {"key": "$k:stream", "type": "stream", "ttl": "none", "value": {
         |    "entries": [{"id": "#1", "fields": [["f", "v1"]]}, {"id": "#2", "fields": [["f", "v2"]]},
         |                {"id": "#3", "fields": [["f", "v3"]]}],
         |    "groups": [
         |      {"name": "g", "last-delivered-id": "#2", "entries-read": 2, "lag": 1, "pending": 2,
         |       "consumers": [{"name": "c1", "pending": 2}]},
         |      {"name": "idle", "last-delivered-id": "#3", "entries-read": null, "lag": 0, "pending": 0,
         |       "consumers": []}]}},
         |  {"key": "$k:row:<uuid>", "type": "string", "ttl": "none",
         |   "value": {"base64": "${b64("second")}", "utf8": "second"}},
         |  {"key": "$k:row:<uuid>", "type": "string", "ttl": "none",
         |   "value": {"base64": "${b64("first")}", "utf8": "first"}}
         |]}""".stripMargin))
    compare(expected, KeyspaceSnapshot.capture(redisConfig, Patterns)) shouldBe empty
  }

  test("gathers keys from every primary") {
    val keys = (1 to 30).map(i => s"$Prefix:spread:$i")
    keys.foreach(key => Raw(key, "SET", key))
    val captured = KeyspaceSnapshot.capture(redisConfig, Patterns).entries.map(_.key)
    captured shouldBe keys.sorted
    val primaries = redisConfig.hosts.map(_.endpoint).distinct
    val holding = primaries.count { endpoint =>
      val conn = endpoint.connect()
      try KeyspaceSnapshot.scanKeys(conn, Patterns).nonEmpty finally conn.close()
    }
    holding shouldBe primaries.length
  }

  test("loads a dataset that captures back unchanged") {
    val dataset = fromJson(GoldenJson.parse(
      s"""{"format": "$Format", "keys": [
         |  {"key": "$Prefix:s", "type": "string", "ttl": "none", "value": {"utf8": "text"}},
         |  {"key": "$Prefix:b", "type": "string", "ttl": 3600, "value": {"base64": "/wBB"}},
         |  {"key": "$Prefix:h", "type": "hash", "ttl": "none", "value": {"a": "1", "z": {"base64": "/w=="}}},
         |  {"key": "$Prefix:l", "type": "list", "ttl": "none", "value": ["c", "a", "b"]},
         |  {"key": "$Prefix:set", "type": "set", "ttl": "none", "value": ["x", "y"]},
         |  {"key": "$Prefix:z", "type": "zset", "ttl": "none",
         |   "value": [{"member": "a", "score": "1.5"}, {"member": "b", "score": "inf"}]},
         |  {"key": "$Prefix:x", "type": "stream", "ttl": "none",
         |   "value": {"entries": [{"id": "#1", "fields": [["f", "1"], ["g", "2"]]}], "groups": []}}
         |]}""".stripMargin))
    KeyspaceSnapshot.load(redisConfig, dataset)
    compare(canonical(dataset), KeyspaceSnapshot.capture(redisConfig, Patterns)) shouldBe empty
    val firstEntry = Raw(s"$Prefix:x", "XRANGE", "-", "+").asInstanceOf[java.util.List[java.util.List[AnyRef]]].get(0)
    new String(firstEntry.get(0).asInstanceOf[Array[Byte]], UTF_8) shouldBe "1-0"
  }
}

class KeyspaceSnapshotStandaloneSpec extends KeyspaceSnapshotLiveSpec with RedisStandaloneEnv

class KeyspaceSnapshotClusterSpec extends KeyspaceSnapshotLiveSpec with RedisClusterEnv
