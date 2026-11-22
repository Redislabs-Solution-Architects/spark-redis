package com.redislabs.provider.redis.golden

import java.sql.{Date, Timestamp}

import com.redislabs.provider.redis.util.Person
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._

// scalastyle:off magic.number multiple.string.literals

/**
  * The rows the write scenarios save, shared with the legacy-fixture checks. Dates and timestamps are `def`s: they
  * must be created while the golden suite has set the JVM time zone to UTC.
  */
object GoldenData {

  /** The flat schema: the `Person` case class the other suites use, so `age` and `salary` are not nullable. */
  val People: Seq[Person] = Person.data

  /** `Person` with every column nullable, for rows built with an explicit schema. */
  val PersonSchema: StructType = StructType(Seq(
    StructField("name", StringType),
    StructField("age", IntegerType),
    StructField("address", StringType),
    StructField("salary", DoubleType)))

  /** Overwrites and appends with a new column, and a null over an existing value. */
  val PersonTitleSchema: StructType = PersonSchema.add("title", StringType)

  val PeopleUpdate: Seq[Row] = Seq(
    Row("John", 31, null, 155.0, "CEO"),
    Row("Mary", 28, "1 Main Street", 99.9, "CTO"))

  val PeopleWithNulls: Seq[Row] = Seq(
    Row("John", null, "60 Wall Street", null),
    Row("Peter", 35, null, 200.3))

  val AllTypesSchema: StructType = StructType(Seq(
    StructField("id", StringType),
    StructField("string", StringType),
    StructField("byte", ByteType),
    StructField("short", ShortType),
    StructField("int", IntegerType),
    StructField("long", LongType),
    StructField("float", FloatType),
    StructField("double", DoubleType),
    StructField("decimal", DecimalType(10, 2)),
    StructField("boolean", BooleanType),
    StructField("date", DateType),
    StructField("timestamp", TimestampType),
    StructField("binary", BinaryType)))

  def allTypes: Seq[Row] = Seq(
    Row("a", "text", 1.toByte, 2.toShort, 3, 4000000000L, 1.5f, 2.25, new java.math.BigDecimal("12.34"), true,
      Date.valueOf("2020-02-29"), Timestamp.valueOf("2020-02-29 12:34:56.789"), Array[Byte](0, 1, 2, -1)),
    Row("b", "ünïcødé ✓", (-1).toByte, (-2).toShort, -3, -4L, -0.5f, 1.0E10, new java.math.BigDecimal("-0.01"),
      false, Date.valueOf("1970-01-01"), Timestamp.valueOf("1999-12-31 23:59:59"), Array.emptyByteArray))

  private val WideTypes = Seq(StringType, IntegerType, LongType, DoubleType, BooleanType)

  /** 50 columns: the key column `id` and `c01`..`c49`, cycling through five types. */
  val WideSchema: StructType = StructType(StructField("id", StringType) +:
    (1 to 49).map(i => StructField(f"c$i%02d", WideTypes(i % WideTypes.size))))

  def wideRow: Row = Row(("w1" +: (1 to 49).map(wideValue)): _*)

  private def wideValue(i: Int): Any = i % WideTypes.size match {
    case 0 => s"v$i"
    case 1 => i
    case 2 => i * 1000000000L
    case 3 => i + 0.5
    case _ => i % 2 == 0
  }

  /** The schema of the stream scenarios' rows: two entry fields and the entry ID. */
  val StreamSchema: StructType = StructType(Seq(
    StructField("name", StringType),
    StructField("age", IntegerType),
    StructField("_id", StringType)))

  /** The `stream.offsets` option for one stream. */
  def streamOffsets(streamKey: String, groupName: String, offset: String): String =
    s"""{"offsets":{"$streamKey":{"groupName":"$groupName","offset":"$offset"}}}"""
}
