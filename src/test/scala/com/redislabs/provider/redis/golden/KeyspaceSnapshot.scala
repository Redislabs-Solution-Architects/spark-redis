package com.redislabs.provider.redis.golden

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.time.Instant
import java.util.Base64
import java.util.{List => JList}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try
import scala.util.matching.Regex

import com.redislabs.provider.redis.RedisConfig
import org.apache.commons.lang3.SerializationUtils
import org.apache.spark.sql.types.StructType
import org.json4s._
import org.json4s.jackson.JsonMethods.parse
import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.util.SafeEncoder

// scalastyle:off multiple.string.literals number.of.methods cyclomatic.complexity

/**
  * A canonical, language-neutral JSON dump of part of a Redis keyspace. It is the format of the golden fixtures
  * (`src/test/resources/golden/`): what 3.x writes, and the datasets the read scenarios load. The format is
  * described in `src/test/resources/golden/README.md`.
  *
  * Every Redis call is a raw command, so the same capture can be rewritten for any client.
  */
object KeyspaceSnapshot {

  val Format = "spark-redis-keyspace-snapshot/1"
  val UuidPlaceholder = "<uuid>"
  val TtlToleranceSeconds = 5L
  val StructTypeClass: String = classOf[StructType].getName

  /** A key's time to live, as `"none"` or whole seconds. */
  sealed trait Ttl
  case object NoTtl extends Ttl
  final case class TtlSeconds(seconds: Long) extends Ttl

  /** One key: its name (with UUID segments replaced), type, TTL and canonical value. */
  final case class Entry(key: String, keyType: String, ttl: Ttl, value: JValue)

  final case class Snapshot(entries: Seq[Entry]) {
    def sorted: Snapshot = Snapshot(entries.sortBy(e => (e.key, GoldenJson.compact(e.value), ttlOrder(e.ttl))))
  }

  /**
    * A placeholder for values that are not deterministic, applied to text elements (hash values, list items, set
    * members, string text). For example, 3.x stores a `binary` column of the `hash` model as Java's
    * `[B@<identity hash>`.
    */
  final case class ValueRule(pattern: Regex, placeholder: String)

  val JavaByteArrayIdentity = ValueRule("""\[B@[0-9a-f]+""".r, "[B@<identity-hash>")

  private val Uuid = "[0-9a-fA-F]{32}".r
  private val ChunkSize = 1000
  private val JavaSerializationMagic = Array(0xAC.toByte, 0xED.toByte, 0x00.toByte, 0x05.toByte)

  // ---------------------------------------------------------------------------------------------------------------
  // Capture

  /**
    * Captures every key that matches one of `patterns`, gathered from every primary (in a cluster, each primary
    * holds part of the keyspace).
    */
  def capture(config: RedisConfig, patterns: Seq[String], rules: Seq[ValueRule] = Nil): Snapshot = {
    val endpoints = config.hosts.map(_.endpoint).distinct
    val entries = endpoints.flatMap { endpoint =>
      withJedis(endpoint.connect()) { conn =>
        scanKeys(conn, patterns).flatMap(key => captureKey(conn, key, rules))
      }
    }
    Snapshot(entries.toSeq).sorted
  }

  /** The keys matching any of `patterns` on one server, without duplicates. */
  def scanKeys(conn: Jedis, patterns: Seq[String]): Seq[Array[Byte]] = {
    val keys = mutable.LinkedHashMap[ByteBuffer, Array[Byte]]()
    patterns.foreach { pattern =>
      var cursor = "0"
      do {
        val reply = list(cmd(conn, "SCAN", cursor, "MATCH", pattern, "COUNT", ChunkSize.toString))
        cursor = text(reply.get(0))
        list(reply.get(1)).asScala.map(bytes).foreach(k => keys.getOrElseUpdate(ByteBuffer.wrap(k), k))
      } while (cursor != "0")
    }
    keys.values.toSeq
  }

  private def captureKey(conn: Jedis, key: Array[Byte], rules: Seq[ValueRule]): Option[Entry] = {
    val keyType = text(cmdBytes(conn, "TYPE", key))
    val pttl = long(cmdBytes(conn, "PTTL", key))
    if (keyType == "none" || pttl == -2L) {
      None // expired or deleted between SCAN and now
    } else {
      val ttl = if (pttl == -1L) NoTtl else TtlSeconds(math.round(pttl / 1000.0))
      val value = keyType match {
        case "string" => stringValue(bytes(cmdBytes(conn, "GET", key)), rules)
        case "hash" => hashValue(conn, key, rules)
        case "list" => JArray(listItems(conn, key).map(element(_, rules)).toList)
        case "set" => JArray(setMembers(conn, key).sortWith(lessThan).map(element(_, rules)).toList)
        case "zset" => zsetValue(conn, key, rules)
        case "stream" => streamValue(conn, key, rules)
        case other => throw new IllegalStateException(s"Key '${keyName(key)}' has type '$other', which the " +
          "snapshot format does not cover")
      }
      Some(Entry(normalizeKey(keyName(key)), keyType, ttl, value))
    }
  }

  /** Strings: base64, plus UTF-8 text when the bytes are valid UTF-8, plus a decoded view of Java-serialized data. */
  def stringValue(value: Array[Byte], rules: Seq[ValueRule] = Nil): JValue = {
    val fields = List("base64" -> JString(base64(value))) ++
      utf8(value).map(t => "utf8" -> JString(applyRules(t, rules))) ++
      javaSerialized(value).map(d => "javaSerialized" -> d)
    JObject(fields)
  }

  private def hashValue(conn: Jedis, key: Array[Byte], rules: Seq[ValueRule]): JValue = {
    val fields = mutable.ArrayBuffer[(Array[Byte], Array[Byte])]()
    var cursor = "0"
    do {
      val reply = list(cmdBytes(conn, "HSCAN", key, enc(cursor), enc("COUNT"), enc(ChunkSize.toString)))
      cursor = text(reply.get(0))
      list(reply.get(1)).asScala.map(bytes).grouped(2).foreach(p => fields += (p.head -> p(1)))
    } while (cursor != "0")
    JObject(fields.sortWith((a, b) => lessThan(a._1, b._1)).map { case (f, v) =>
      fieldName(f) -> element(v, rules)
    }.toList)
  }

  private def listItems(conn: Jedis, key: Array[Byte]): Seq[Array[Byte]] = {
    val length = long(cmdBytes(conn, "LLEN", key))
    (0L until length by ChunkSize.toLong).flatMap { start =>
      val stop = math.min(start + ChunkSize, length) - 1
      list(cmdBytes(conn, "LRANGE", key, enc(start.toString), enc(stop.toString))).asScala.map(bytes)
    }
  }

  private def setMembers(conn: Jedis, key: Array[Byte]): Seq[Array[Byte]] = {
    val members = mutable.ArrayBuffer[Array[Byte]]()
    var cursor = "0"
    do {
      val reply = list(cmdBytes(conn, "SSCAN", key, enc(cursor), enc("COUNT"), enc(ChunkSize.toString)))
      cursor = text(reply.get(0))
      members ++= list(reply.get(1)).asScala.map(bytes)
    } while (cursor != "0")
    members
  }

  /** Sorted sets: ordered by score and then member, with the score as the text Redis returns. */
  private def zsetValue(conn: Jedis, key: Array[Byte], rules: Seq[ValueRule]): JValue = {
    val length = long(cmdBytes(conn, "ZCARD", key))
    val members = (0L until length by ChunkSize.toLong).flatMap { start =>
      val stop = math.min(start + ChunkSize, length) - 1
      val reply = cmdBytes(conn, "ZRANGE", key, enc(start.toString), enc(stop.toString), enc("WITHSCORES"))
      list(reply).asScala.map(bytes).grouped(2).map(p => p.head -> text(p(1)))
    }
    val sorted = members.sortWith { case ((m1, s1), (m2, s2)) =>
      val (d1, d2) = (score(s1), score(s2))
      if (d1 != d2) d1 < d2 else lessThan(m1, m2)
    }
    JArray(sorted.map { case (member, s) =>
      JObject("member" -> element(member, rules), "score" -> JString(s))
    }.toList)
  }

  /** Streams: entries in order with IDs replaced by their 1-based position (`#1`, `#2`, ...), and the groups. */
  private def streamValue(conn: Jedis, key: Array[Byte], rules: Seq[ValueRule]): JValue = {
    val entries = mutable.ArrayBuffer[(String, Seq[(Array[Byte], Array[Byte])])]()
    var start = "-"
    var more = true
    while (more) {
      val reply = list(cmdBytes(conn, "XRANGE", key, enc(start), enc("+"), enc("COUNT"), enc(ChunkSize.toString)))
      val batch = reply.asScala.map { e =>
        val entry = list(e)
        text(entry.get(0)) -> list(entry.get(1)).asScala.map(bytes).grouped(2).map(p => p.head -> p(1)).toSeq
      }
      entries ++= batch
      more = batch.size == ChunkSize
      if (more) start = "(" + batch.last._1
    }
    val positions = entries.map(_._1).zipWithIndex.map { case (id, i) => id -> s"#${i + 1}" }.toMap
    val entryJson = entries.zipWithIndex.map { case ((_, fields), i) =>
      JObject("id" -> JString(s"#${i + 1}"), "fields" -> JArray(fields.map { case (f, v) =>
        JArray(List(element(f, rules), element(v, rules)))
      }.toList))
    }
    JObject("entries" -> JArray(entryJson.toList), "groups" -> JArray(streamGroups(conn, key, positions)))
  }

  private def streamGroups(conn: Jedis, key: Array[Byte], positions: Map[String, String]): List[JValue] = {
    val groups = list(cmdBytes(conn, "XINFO", enc("GROUPS"), key)).asScala.map(g => replyMap(list(g)))
    groups.sortBy(g => text(g("name"))).map { g =>
      val name = text(g("name"))
      val consumers = list(cmdBytes(conn, "XINFO", enc("CONSUMERS"), key, enc(name))).asScala.map { c =>
        val m = replyMap(list(c))
        JObject("name" -> JString(text(m("name"))), "pending" -> JInt(long(m("pending"))))
      }.sortBy(c => GoldenJson.compact(c))
      val lastDelivered = text(g("last-delivered-id"))
      JObject(
        "name" -> JString(name),
        "last-delivered-id" -> JString(positions.getOrElse(lastDelivered, lastDelivered)),
        "entries-read" -> optionalLong(g.get("entries-read")),
        "lag" -> optionalLong(g.get("lag")),
        "pending" -> JInt(long(g("pending"))),
        "consumers" -> JArray(consumers.toList))
    }.toList
  }

  /**
    * Java-serialized values get a decoded view. A `StructType` (the `_spark:<table>:schema` key) is compared by this
    * view only: Spark serializes the state of the type's lazy fields, so the bytes depend on which of them Spark
    * happened to evaluate before writing. Any other serialized object (a `binary` model row) is compared byte for
    * byte, and its view is there for reviewers.
    */
  private def javaSerialized(value: Array[Byte]): Option[JValue] = {
    if (!value.startsWith(JavaSerializationMagic)) {
      None
    } else {
      Try(SerializationUtils.deserialize[AnyRef](value)).toOption.map {
        case schema: StructType =>
          JObject("class" -> JString(StructTypeClass), "sparkSchema" -> parse(schema.json))
        case values: Array[AnyRef] =>
          JObject("class" -> JString(values.getClass.getName), "values" -> JArray(values.map(javaValue).toList))
        case other =>
          JObject("class" -> JString(other.getClass.getName))
      }
    }
  }

  private def javaValue(v: AnyRef): JValue = v match {
    case null => JNull
    case b: Array[Byte] => JObject("class" -> JString("[B"), "base64" -> JString(base64(b)))
    case t: java.sql.Timestamp =>
      JObject("class" -> JString(t.getClass.getName), "value" -> JString(t.toInstant.toString))
    case d: java.util.Date =>
      JObject("class" -> JString(d.getClass.getName), "value" -> JString(Instant.ofEpochMilli(d.getTime).toString))
    case d: java.math.BigDecimal => JObject("class" -> JString(d.getClass.getName), "value" -> JString(d.toPlainString))
    case other => JObject("class" -> JString(other.getClass.getName), "value" -> JString(String.valueOf(other)))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Load (datasets for the read scenarios)

  /**
    * Writes a snapshot into Redis, each key on the server that owns it. Stream entries `#n` get the ID `n-0`.
    * Strings may give only `utf8`. Keys with a `<uuid>` segment and stream groups cannot be loaded.
    */
  def load(config: RedisConfig, snapshot: Snapshot): Unit = snapshot.entries.foreach { e =>
    require(!e.key.split(":", -1).contains(UuidPlaceholder), s"Cannot load '${e.key}': it has a $UuidPlaceholder")
    val key = keyBytes(e.key)
    withJedis(config.connectionForKey(key)) { conn =>
      cmdBytes(conn, "UNLINK", key)
      e.keyType match {
        case "string" => cmdBytes(conn, "SET", key, stringBytes(e.value))
        case "hash" => cmdBytes(conn, "HSET", key +: obj(e.value).flatMap { case (f, v) =>
          Seq(keyBytes(f), elementBytes(v)) }: _*)
        case "list" => cmdBytes(conn, "RPUSH", key +: arr(e.value).map(elementBytes): _*)
        case "set" => cmdBytes(conn, "SADD", key +: arr(e.value).map(elementBytes): _*)
        case "zset" => cmdBytes(conn, "ZADD", key +: arr(e.value).flatMap { m =>
          val fields = obj(m).toMap
          Seq(enc(str(fields("score"))), elementBytes(fields("member")))
        }: _*)
        case "stream" =>
          val fields = obj(e.value).toMap
          require(fields.get("groups").forall(arr(_).isEmpty), s"Cannot load the groups of '${e.key}'")
          arr(fields("entries")).zipWithIndex.foreach { case (entry, i) =>
            val pairs = arr(obj(entry).toMap.apply("fields")).flatMap(p => arr(p).map(elementBytes))
            cmdBytes(conn, "XADD", key +: enc(s"${i + 1}-0") +: pairs: _*)
          }
        case other => throw new IllegalArgumentException(s"Cannot load '${e.key}' of type '$other'")
      }
      e.ttl match {
        case TtlSeconds(s) => cmdBytes(conn, "EXPIRE", key, enc(s.toString))
        case NoTtl =>
      }
    }
  }

  /** Deletes every key matching one of `patterns`, on every primary. */
  def delete(config: RedisConfig, patterns: Seq[String]): Unit = {
    config.hosts.map(_.endpoint).distinct.foreach { endpoint =>
      withJedis(endpoint.connect()) { conn =>
        scanKeys(conn, patterns).foreach(key => cmdBytes(conn, "UNLINK", key))
      }
    }
  }

  /** Fills in `base64` for strings that give only `utf8`, as a hand-written dataset may. */
  def canonical(snapshot: Snapshot): Snapshot = Snapshot(snapshot.entries.map {
    case e if e.keyType == "string" => e.copy(value = stringValue(stringBytes(e.value)))
    case e => e
  }).sorted

  // ---------------------------------------------------------------------------------------------------------------
  // Compare

  /**
    * Compares two snapshots and returns the differences, or nothing when they match. Keys that share a name after
    * UUID replacement are matched regardless of order. A TTL matches when both are `none`, or when the seconds are
    * within [[TtlToleranceSeconds]].
    */
  def compare(expected: Snapshot, actual: Snapshot): Seq[String] = {
    val e = expected.entries.groupBy(_.key)
    val a = actual.entries.groupBy(_.key)
    (e.keySet ++ a.keySet).toSeq.sorted.flatMap { key =>
      compareKey(key, e.getOrElse(key, Nil), a.getOrElse(key, Nil))
    }
  }

  private def compareKey(key: String, expected: Seq[Entry], actual: Seq[Entry]): Seq[String] = {
    if (expected.size == 1 && actual.size == 1) {
      describe(expected.head, actual.head)
    } else {
      val unmatched = mutable.ArrayBuffer(actual.sortBy(e => ttlOrder(e.ttl)): _*)
      val missing = expected.sortBy(e => ttlOrder(e.ttl)).filterNot { exp =>
        val i = unmatched.indexWhere(act => describe(exp, act).isEmpty)
        if (i >= 0) unmatched.remove(i)
        i >= 0
      }
      missing.map(m => s"missing: ${render(m)}") ++ unmatched.map(u => s"unexpected: ${render(u)}")
    }
  }

  private def describe(expected: Entry, actual: Entry): Seq[String] = {
    val k = expected.key
    Seq(
      if (expected.keyType != actual.keyType) Some(s"$k: type ${expected.keyType}, got ${actual.keyType}") else None,
      if (!ttlMatches(expected.ttl, actual.ttl)) {
        Some(s"$k: ttl ${ttlText(expected.ttl)}, got ${ttlText(actual.ttl)}")
      } else {
        None
      },
      if (!valueMatches(expected.value, actual.value)) {
        Some(s"$k: value ${GoldenJson.compact(expected.value)}, got ${GoldenJson.compact(actual.value)}")
      } else {
        None
      }
    ).flatten
  }

  def ttlMatches(expected: Ttl, actual: Ttl): Boolean = (expected, actual) match {
    case (NoTtl, NoTtl) => true
    case (TtlSeconds(e), TtlSeconds(a)) => math.abs(e - a) <= TtlToleranceSeconds
    case _ => false
  }

  private def valueMatches(expected: JValue, actual: JValue): Boolean = {
    (schemaView(expected), schemaView(actual)) match {
      case (Some(e), Some(a)) => GoldenJson.compact(e) == GoldenJson.compact(a)
      case _ => GoldenJson.compact(expected) == GoldenJson.compact(actual)
    }
  }

  private def schemaView(v: JValue): Option[JValue] = v \ "javaSerialized" match {
    case view: JObject if (view \ "class") == JString(StructTypeClass) => Some(view)
    case _ => None
  }

  // ---------------------------------------------------------------------------------------------------------------
  // JSON

  def toJson(snapshot: Snapshot): JValue = JObject(
    "format" -> JString(Format),
    "keys" -> JArray(snapshot.sorted.entries.map(entryJson).toList))

  def fromJson(json: JValue): Snapshot = {
    require((json \ "format") == JString(Format), s"Not a $Format document")
    Snapshot(arr(json \ "keys").map { k =>
      val fields = obj(k).toMap
      val ttl = fields("ttl") match {
        case JString("none") => NoTtl
        case JInt(s) => TtlSeconds(s.toLong)
        case other => throw new IllegalArgumentException(s"Bad ttl: $other")
      }
      Entry(str(fields("key")), str(fields("type")), ttl, fields("value"))
    })
  }

  def render(e: Entry): String = GoldenJson.compact(entryJson(e))

  private def entryJson(e: Entry): JValue = JObject(
    "key" -> JString(e.key),
    "type" -> JString(e.keyType),
    "ttl" -> (e.ttl match {
      case NoTtl => JString("none")
      case TtlSeconds(s) => JInt(s)
    }),
    "value" -> e.value)

  // ---------------------------------------------------------------------------------------------------------------
  // Encoding helpers

  /** Replaces each 32-hex key segment (a `:`-separated part, as 3.x's random row IDs) with `<uuid>`. */
  def normalizeKey(key: String): String =
    key.split(":", -1).map(s => if (Uuid.pattern.matcher(s).matches()) UuidPlaceholder else s).mkString(":")

  /** A key, field name or element: its UTF-8 text when valid, else `base64:<base64>`. */
  private def keyName(b: Array[Byte]): String = utf8(b).getOrElse("base64:" + base64(b))

  private def fieldName(b: Array[Byte]): String = keyName(b)

  private def keyBytes(name: String): Array[Byte] =
    if (name.startsWith("base64:")) Base64.getDecoder.decode(name.stripPrefix("base64:")) else enc(name)

  /** An element (hash value, list item, set or sorted set member, stream field): text, or `{"base64": ...}`. */
  private def element(b: Array[Byte], rules: Seq[ValueRule]): JValue =
    utf8(b).map(t => JString(applyRules(t, rules))).getOrElse(JObject("base64" -> JString(base64(b))))

  private def elementBytes(v: JValue): Array[Byte] = v match {
    case JString(s) => enc(s)
    case o: JObject => Base64.getDecoder.decode(str(o \ "base64"))
    case other => throw new IllegalArgumentException(s"Bad element: $other")
  }

  private def stringBytes(v: JValue): Array[Byte] = (v \ "base64", v \ "utf8") match {
    case (JString(b), _) => Base64.getDecoder.decode(b)
    case (_, JString(t)) => enc(t)
    case _ => throw new IllegalArgumentException(s"Bad string value: $v")
  }

  private def applyRules(text: String, rules: Seq[ValueRule]): String =
    rules.find(_.pattern.pattern.matcher(text).matches()).map(_.placeholder).getOrElse(text)

  def utf8(b: Array[Byte]): Option[String] = {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Some(decoder.decode(ByteBuffer.wrap(b)).toString) catch {
      case _: CharacterCodingException => None
    }
  }

  def base64(b: Array[Byte]): String = Base64.getEncoder.encodeToString(b)

  /** Unsigned byte order, which is code point order for UTF-8 text. */
  private def lessThan(a: Array[Byte], b: Array[Byte]): Boolean = {
    val n = math.min(a.length, b.length)
    var i = 0
    while (i < n && a(i) == b(i)) i += 1
    if (i < n) (a(i) & 0xff) < (b(i) & 0xff) else a.length < b.length
  }

  private def score(s: String): Double = s.toLowerCase match {
    case "inf" | "+inf" => Double.PositiveInfinity
    case "-inf" => Double.NegativeInfinity
    case other => other.toDouble
  }

  private def ttlOrder(t: Ttl): Long = t match {
    case NoTtl => -1L
    case TtlSeconds(s) => s
  }

  private def ttlText(t: Ttl): String = t match {
    case NoTtl => "none"
    case TtlSeconds(s) => s"${s}s"
  }

  private def optionalLong(v: Option[Any]): JValue = v match {
    case Some(n: java.lang.Long) => JInt(n.longValue())
    case _ => JNull
  }

  private def obj(v: JValue): List[(String, JValue)] = v match {
    case JObject(fields) => fields
    case other => throw new IllegalArgumentException(s"Expected an object: $other")
  }

  private def arr(v: JValue): List[JValue] = v match {
    case JArray(items) => items
    case other => throw new IllegalArgumentException(s"Expected an array: $other")
  }

  private def str(v: JValue): String = v match {
    case JString(s) => s
    case other => throw new IllegalArgumentException(s"Expected a string: $other")
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Raw commands

  private final case class Command(name: String) extends ProtocolCommand {
    override val getRaw: Array[Byte] = SafeEncoder.encode(name)
  }

  /** Sends `name key args...` to the server that owns `key`, for test setup that needs exact commands. */
  def command(config: RedisConfig, key: String, name: String, args: String*): AnyRef =
    withJedis(config.connectionForKey(key)) { conn => cmd(conn, name, key +: args: _*) }

  private def enc(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def cmd(conn: Jedis, name: String, args: String*): AnyRef = cmdBytes(conn, name, args.map(enc): _*)

  private def cmdBytes(conn: Jedis, name: String, args: Array[Byte]*): AnyRef =
    conn.sendCommand(Command(name), args: _*)

  private def withJedis[A](conn: Jedis)(body: Jedis => A): A = try body(conn) finally conn.close()

  private def list(reply: Any): JList[AnyRef] = reply.asInstanceOf[JList[AnyRef]]

  private def bytes(reply: Any): Array[Byte] = reply.asInstanceOf[Array[Byte]]

  private def text(reply: Any): String = reply match {
    case b: Array[Byte] => new String(b, StandardCharsets.UTF_8)
    case n: java.lang.Long => n.toString
    case other => String.valueOf(other)
  }

  private def long(reply: Any): Long = reply.asInstanceOf[java.lang.Long].longValue()

  private def replyMap(reply: JList[AnyRef]): Map[String, Any] =
    reply.asScala.grouped(2).map(p => text(p.head) -> p(1)).toMap
}
