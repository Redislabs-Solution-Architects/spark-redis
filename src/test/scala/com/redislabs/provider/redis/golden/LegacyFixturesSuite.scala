package com.redislabs.provider.redis.golden

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.Base64

import com.redislabs.provider.redis.SchemaMigration
import com.redislabs.provider.redis.SchemaMigration.{Outcome, Result}
import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.golden.GoldenData._
import com.redislabs.provider.redis.golden.KeyspaceSnapshot.{Entry, NoTtl, Snapshot}
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.JavaSerializedSchema
import org.apache.commons.lang3.SerializationUtils
import org.apache.spark.sql.Row
import org.apache.spark.sql.redis._
import org.apache.spark.sql.types._
import org.json4s._

// scalastyle:off multiple.string.literals

/**
  * Legacy fixtures for `spark-redis-migrate` (Phase 0, step B4): Java-serialized `_spark:<table>:schema` blobs and
  * `binary` model rows in `src/test/resources/legacy/`, exactly as 3.x wrote them in the B2 captures (Spark 3.2.1,
  * Scala 2.12). The suite checks that the current build reads every blob, that each file still matches its B2 capture,
  * and that after `SchemaMigration` the connector reads them back as a table.
  *
  * The schema blobs are read with `JavaSerializedSchema`, the version-independent reader the migration uses: Java
  * deserialization reads them only on the Spark and Scala versions that wrote them. The `binary` rows hold JDK types
  * only, so Java deserialization reads them on any version.
  *
  * A `binary` row must equal its capture byte for byte. A schema blob must decode to the same `StructType` as its
  * capture: the bytes of a serialized `StructType` depend on which of its lazy fields Spark evaluated before writing
  * it, so a later capture of the same schema can differ (`df-hash-key-column` and `df-binary-key-column` do), and
  * since spark-redis writes schemas as JSON, a capture holds the schema's JSON.
  *
  * The files are frozen once written. With `-Dgolden.update=true` the suite only writes the files that are missing,
  * from the write fixtures; to capture one again, the owner deletes it first.
  */
class LegacyFixturesSuite extends GoldenSuite with RedisStandaloneEnv {

  override def topology: String = "standalone"

  private val Dir = new File("src/test/resources/legacy")

  /** The flat schema as Spark derives it from the `Person` case class: `age` and `salary` are not nullable. */
  private val FlatSchema = StructType(Seq(
    StructField("name", StringType),
    StructField("age", IntegerType, nullable = false),
    StructField("address", StringType),
    StructField("salary", DoubleType, nullable = false)))

  /** file -> (write fixture, key in it) */
  private val Sources: Seq[(String, (String, String))] = Seq(
    "schema-flat.bin" -> ("df-hash-key-column" -> "_spark:golden_df_hash_key_column:schema"),
    "schema-all-types.bin" -> ("df-hash-all-types" -> "_spark:golden_df_hash_all_types:schema"),
    "schema-wide-50.bin" -> ("df-schema-wide" -> "_spark:golden_df_schema_wide:schema"),
    "binary-row-person-john.bin" -> ("df-binary-key-column" -> "golden_df_binary_key_column:John"),
    "binary-row-nulls-john.bin" -> ("df-binary-nulls" -> "golden_df_binary_nulls:John"),
    "binary-row-all-types-a.bin" -> ("df-binary-all-types" -> "golden_df_binary_all_types:a"),
    "binary-row-all-types-b.bin" -> ("df-binary-all-types" -> "golden_df_binary_all_types:b"))

  private def captured(scenario: String, key: String): Array[Byte] = {
    val file = GoldenSuite.fixture("write", scenario)
    val snapshot = KeyspaceSnapshot.fromJson(GoldenJson.parse(new String(Files.readAllBytes(file.toPath), UTF_8)))
    val entry = snapshot.entries.find(_.key == key).getOrElse(fail(s"$file has no key $key"))
    Base64.getDecoder.decode((entry.value \ "base64").asInstanceOf[JString].s)
  }

  private def legacy(name: String): Array[Byte] = Files.readAllBytes(new File(Dir, name).toPath)

  private def isJavaSerialized(bytes: Array[Byte]): Boolean =
    bytes.length >= 2 && bytes(0) == 0xAC.toByte && bytes(1) == 0xED.toByte

  /** A captured schema key's value: Java-serialized, as 3.x wrote it, or JSON, as spark-redis writes it now. */
  private def capturedSchema(bytes: Array[Byte]): DataType =
    if (isJavaSerialized(bytes)) JavaSerializedSchema.read(bytes) else DataType.fromJson(new String(bytes, UTF_8))

  private def values(row: Row): Seq[Any] = row.toSeq.map {
    case b: Array[Byte] => b.toSeq
    case other => other
  }

  test("each legacy file matches what 3.x wrote in its B2 capture") {
    info(s"build: Scala ${scala.util.Properties.versionNumberString}, Spark ${org.apache.spark.SPARK_VERSION}, " +
      s"Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
    Sources.foreach { case (name, (scenario, key)) =>
      val bytes = captured(scenario, key)
      val file = new File(Dir, name)
      if (GoldenSuite.UpdateMode && !file.exists()) {
        assert(!name.startsWith("schema-") || isJavaSerialized(bytes),
          s"write/$scenario.json holds $key as JSON, not Java-serialized as 3.x wrote it, so legacy/$name can't be " +
            "captured from it")
        Dir.mkdirs()
        Files.write(file.toPath, bytes)
      }
      val matches =
        if (name.startsWith("schema-")) {
          JavaSerializedSchema.read(legacy(name)) == capturedSchema(bytes)
        } else {
          legacy(name).sameElements(bytes)
        }
      assert(matches, s"legacy/$name does not match $key in write/$scenario.json")
    }
  }

  test("the schema blobs read as their StructType") {
    Seq("schema-flat.bin" -> FlatSchema, "schema-all-types.bin" -> AllTypesSchema, "schema-wide-50.bin" -> WideSchema)
      .foreach { case (name, expected) =>
        assert(JavaSerializedSchema.read(legacy(name)) == expected, name)
      }
  }

  test("the binary rows deserialize to their values") {
    Seq(
      "binary-row-person-john.bin" -> Row("John", 30, "60 Wall Street", 150.5),
      "binary-row-nulls-john.bin" -> PeopleWithNulls.head,
      "binary-row-all-types-a.bin" -> allTypes.head,
      "binary-row-all-types-b.bin" -> allTypes(1)
    ).foreach { case (name, expected) =>
      val actual = SerializationUtils.deserialize[Array[AnyRef]](legacy(name))
      assert(values(Row(actual: _*)) == values(expected), name)
    }
  }

  test("after SchemaMigration, the connector reads the legacy blobs as a binary-model table") {
    val table = "golden_legacy"
    val dataset = Snapshot(Seq(
      Entry(s"_spark:$table:schema", "string", NoTtl, KeyspaceSnapshot.stringValue(legacy("schema-all-types.bin"))),
      Entry(s"$table:a", "string", NoTtl, KeyspaceSnapshot.stringValue(legacy("binary-row-all-types-a.bin"))),
      Entry(s"$table:b", "string", NoTtl, KeyspaceSnapshot.stringValue(legacy("binary-row-all-types-b.bin")))))
    val patterns = Seq(s"$table:*", s"_spark:$table:schema")
    KeyspaceSnapshot.delete(redisConfig, patterns)
    try {
      KeyspaceSnapshot.load(redisConfig, dataset)
      val schemaKey = s"_spark:$table:schema"
      assert(SchemaMigration.migrate(redisConfig, table) == Seq(Result(schemaKey, Outcome.Migrated)))
      val migrated = withConnection(redisConfig.connectionForKey(schemaKey))(_.get(schemaKey))
      assert(DataType.fromJson(migrated) == AllTypesSchema)
      val df = spark.read.format(RedisFormat)
        .option(SqlOptionModel, SqlOptionModelBinary)
        .option(SqlOptionTableName, table)
        .load()
      assert(df.schema == AllTypesSchema)
      assert(df.collect().map(GoldenSuite.json).map(GoldenJson.compact).sorted.toSeq ==
        allTypes.map(GoldenSuite.json).map(GoldenJson.compact).sorted)
    } finally {
      KeyspaceSnapshot.delete(redisConfig, patterns)
    }
  }
}
