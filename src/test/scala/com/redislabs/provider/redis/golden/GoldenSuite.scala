package com.redislabs.provider.redis.golden

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.{TimeZone, UUID}

import scala.collection.mutable

import com.redislabs.provider.redis.SparkRedisSuite
import com.redislabs.provider.redis.golden.KeyspaceSnapshot.{Snapshot, ValueRule}
import org.apache.spark.sql.redis.StreamOptionStreamKeys
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row}
import org.json4s._

/**
  * Base for the golden suites. A scenario's result is compared with its fixture under
  * `src/test/resources/golden/<kind>/<scenario>.json`; the comparison fails with the differences and writes what it
  * saw under `target/golden-actual/`.
  *
  * With `-Dgolden.update=true` the suites write the fixtures instead. Only the owner regenerates fixtures, and
  * reviews every changed file. When standalone and cluster both run in one update, the second one compares with what
  * the first wrote, so the two topologies must agree.
  *
  * The JVM time zone is UTC while a golden suite runs: 3.x writes dates and timestamps through `java.sql.Date` and
  * `java.sql.Timestamp`, whose text and serialized form depend on it.
  */
trait GoldenSuite extends SparkRedisSuite {

  /** `standalone` or `cluster`, used in the name of the files written on a mismatch. */
  def topology: String

  private var previousTimeZone: TimeZone = _

  override def beforeAll(): Unit = {
    previousTimeZone = TimeZone.getDefault
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    super.beforeAll()
    spark.conf.set("spark.sql.session.timeZone", "UTC")
  }

  override def afterAll(): Unit = {
    try super.afterAll() finally TimeZone.setDefault(previousTimeZone)
  }

  /** Compares the keys matching `patterns` with the write fixture `scenario`. */
  def assertKeyspace(scenario: String, patterns: Seq[String], rules: Seq[ValueRule] = Nil): Unit = {
    val actual = KeyspaceSnapshot.capture(redisConfig, patterns, rules)
    assertFixture("write", scenario, KeyspaceSnapshot.toJson(actual)) { expectedJson =>
      KeyspaceSnapshot.compare(KeyspaceSnapshot.fromJson(expectedJson), actual)
    }
  }

  /** Compares a read result (rows, sorted unless the reader's order is part of the contract) with a read fixture. */
  def assertReadResult(scenario: String, result: JValue): Unit = {
    assertFixture("read", scenario, result) { expectedJson =>
      if (GoldenJson.compact(expectedJson) == GoldenJson.compact(result)) {
        Nil
      } else {
        Seq(s"expected ${GoldenJson.compact(expectedJson)}", s"got      ${GoldenJson.compact(result)}")
      }
    }
  }

  private def assertFixture(kind: String, scenario: String, actual: JValue)(diff: JValue => Seq[String]): Unit = {
    val file = GoldenSuite.fixture(kind, scenario)
    if (GoldenSuite.UpdateMode && !GoldenSuite.writtenThisRun(file)) {
      GoldenSuite.write(file, actual)
    } else {
      assert(file.isFile, s"Missing fixture $file. Only the owner captures fixtures, with -Dgolden.update=true.")
      val differences = diff(GoldenJson.parse(new String(Files.readAllBytes(file.toPath), UTF_8)))
      if (differences.nonEmpty) {
        val seen = new File(s"target/golden-actual/$kind/$scenario.$topology.json")
        GoldenSuite.writeFile(seen, GoldenJson.pretty(actual))
        fail(s"$kind/$scenario on $topology does not match $file (got $seen):\n" + differences.mkString("\n"))
      }
    }
  }

  /** Loads a dataset from `golden/datasets/<name>.json`, and checks that Redis now holds exactly that dataset. */
  def loadDataset(name: String): Snapshot = {
    val dataset = datasetSnapshot(name)
    val patterns = dataset.entries.map(e => KeyspaceSnapshot.normalizeKey(e.key))
    KeyspaceSnapshot.delete(redisConfig, patterns)
    KeyspaceSnapshot.load(redisConfig, dataset)
    val differences = KeyspaceSnapshot.compare(KeyspaceSnapshot.canonical(dataset),
      KeyspaceSnapshot.capture(redisConfig, patterns))
    assert(differences.isEmpty, s"Dataset $name did not load as written:\n${differences.mkString("\n")}")
    dataset
  }

  def unloadDataset(name: String): Unit =
    KeyspaceSnapshot.delete(redisConfig, datasetSnapshot(name).entries.map(_.key))

  private def datasetSnapshot(name: String): Snapshot = {
    val file = new File(GoldenSuite.Root, s"datasets/$name.json")
    KeyspaceSnapshot.fromJson(GoldenJson.parse(new String(Files.readAllBytes(file.toPath), UTF_8)))
  }

  /**
    * Runs one Structured Streaming batch (`Trigger.Once`) from the Redis stream source into a memory table, and
    * returns its rows.
    */
  def readStreamBatch(streamKey: String, schema: StructType, options: Map[String, String]): Array[Row] = {
    val name = "golden_stream_" + UUID.randomUUID().toString.replace("-", "")
    val query = spark.readStream
      .format("redis")
      .schema(schema)
      .option(StreamOptionStreamKeys, streamKey)
      .options(options)
      .load()
      .writeStream
      .format("memory")
      .queryName(name)
      .option("checkpointLocation", s"target/golden-checkpoints/$name")
      .trigger(Trigger.Once())
      .start()
    query.awaitTermination(GoldenSuite.StreamTimeoutMillis)
    assert(!query.isActive, s"The streaming batch did not finish within ${GoldenSuite.StreamTimeoutMillis} ms")
    query.exception.foreach(e => throw e)
    try spark.table(name).collect() finally spark.catalog.dropTempView(name)
  }

  /** A DataFrame read result: its schema, and its rows sorted by their JSON text. */
  def dataFrameResult(df: DataFrame): JValue = JObject(
    "schema" -> JArray(df.schema.fields.map { f =>
      JObject("name" -> JString(f.name), "type" -> JString(f.dataType.simpleString), "nullable" -> JBool(f.nullable))
    }.toList),
    "rows" -> sortedRows(df.collect().map(r => GoldenSuite.json(r))))

  /** Rows sorted by their JSON text: for readers whose output order is not part of the contract. */
  def sortedRows(rows: Seq[JValue]): JValue = JArray(rows.sortBy(GoldenJson.compact).toList)
}

object GoldenSuite {

  val Root = new File("src/test/resources/golden")
  /**
    * `-Dgolden.update=true` in the test JVM. The scalatest plugin only forwards the system properties listed in
    * `pom.xml`, so until the pom forwards this one, pass it with `JAVA_TOOL_OPTIONS=-Dgolden.update=true`.
    */
  val UpdateMode: Boolean = sys.props.get("golden.update").exists(_.toBoolean)
  val StreamTimeoutMillis = 60000L

  private val written = mutable.Set[String]()

  def fixture(kind: String, scenario: String): File = new File(Root, s"$kind/$scenario.json")

  private def writtenThisRun(file: File): Boolean = written.synchronized(written.contains(file.getPath))

  private def write(file: File, json: JValue): Unit = written.synchronized {
    writeFile(file, GoldenJson.pretty(json))
    written += file.getPath
  }

  def writeFile(file: File, text: String): Unit = {
    file.getParentFile.mkdirs()
    Files.write(file.toPath, text.getBytes(UTF_8))
  }

  /** Spark values as language-neutral JSON. Dates are ISO dates, and timestamps ISO instants in UTC. */
  def json(value: Any): JValue = value match {
    case null => JNull
    case s: String => JString(s)
    case b: Boolean => JBool(b)
    case n: Byte => JInt(n.toInt)
    case n: Short => JInt(n.toInt)
    case n: Int => JInt(n)
    case n: Long => JInt(n)
    case n: Float => JDouble(n.toString.toDouble)
    case n: Double => JDouble(n)
    case d: java.math.BigDecimal => JString(d.toPlainString)
    case d: java.sql.Date => JString(d.toLocalDate.toString)
    case t: java.sql.Timestamp => JString(t.toInstant.toString)
    case b: Array[Byte] => JObject("base64" -> JString(KeyspaceSnapshot.base64(b)))
    case r: Row => JArray(r.toSeq.map(json).toList)
    case p: Product => JArray(p.productIterator.map(json).toList)
    case s: Seq[_] => JArray(s.map(json).toList)
    case other => throw new IllegalArgumentException(s"No JSON form for ${other.getClass.getName}: $other")
  }
}
