package com.redislabs.provider.redis.df

import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.MonitoredCommands
import com.redislabs.provider.redis.util.MonitoredCommands.Command
import com.redislabs.provider.redis.util.TestUtils.{findCause, generateTableName}
import org.apache.spark.sql.redis.RedisSourceRelation.{dataKey, schemaKey}
import org.apache.spark.sql.redis.{RedisFormat, SqlOptionKeyColumn, SqlOptionModel, SqlOptionModelBinary,
  SqlOptionModelHash, SqlOptionTTL, SqlOptionTableName}
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.sql.{Row, SaveMode}
import redis.clients.jedis.Jedis

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
  * `write.mode` for the `hash` model (#351, spec §2.4): what an append does to a row whose key already holds a hash.
  *
  * `merge`, the default, is the 3.x behaviour: the new row's fields are set, and the stored fields it doesn't have
  * stay, whether its DataFrame has no such column or its value is `null`. `replace` makes the stored hash exactly the
  * new row. Each row is then one `MULTI`/`EXEC` on its key: `UNLINK`, `HSET` and, with a `ttl`, `EXPIRE`, so no
  * reader sees a row half replaced or without its TTL. Any other value fails before anything is written.
  *
  * The option's name and values are spelled out rather than taken from the connector's constants, so that the tests
  * pin what users type. The commands are recorded with `MONITOR`, see [[MonitoredCommands]].
  */
// scalastyle:off multiple.string.literals
trait HashWriteModeSuite extends RedisDataframeSuite with MonitoredCommands {

  private val WriteMode = "write.mode"
  private val Ttl = 600

  /** The stored rows' columns: id (the key column), name, age and city. */
  private val storedSchema = StructType(Seq(StructField("id", StringType), StructField("name", StringType),
    StructField("age", IntegerType), StructField("city", StringType)))

  /** The new rows' columns: no city. */
  private val newSchema = StructType(storedSchema.fields.take(3))

  private val storedRows = Seq(Row("john", "John", 30, "London"), Row("peter", "Peter", 45, "Paris"))

  /** John's age is null, Peter's fields are unchanged, and Ann's key is new. None of them has a city. */
  private val newRows = Seq(Row("john", "Johnny", null), Row("peter", "Peter", 45), Row("ann", "Ann", 25))

  private val ids = Seq("john", "peter", "ann")

  private def write(table: String, schema: StructType, rows: Seq[Row], options: Map[String, String]): Unit =
    spark.createDataFrame(sc.parallelize(rows, 2), schema).write.format(RedisFormat)
      .option(SqlOptionTableName, table)
      .option(SqlOptionKeyColumn, "id")
      .options(options)
      .mode(SaveMode.Append)
      .save()

  /** Writes `storedRows` with `storedOptions`, then `newRows` with `options`. Returns what the second write sent. */
  private def overwriteRows(table: String, options: Map[String, String],
                            storedOptions: Map[String, String] = Map.empty): Seq[Command] = {
    write(table, storedSchema, storedRows, storedOptions)
    monitored {
      write(table, newSchema, newRows, options)
    }
  }

  private def onKey[A](key: String)(f: Jedis => A): A = withConnection(redisConfig.connectionForKey(key))(f)

  private def hash(table: String, id: String): Map[String, String] = {
    val key = dataKey(table, id)
    onKey(key)(_.hgetAll(key).asScala.toMap)
  }

  private def ttl(table: String, id: String): Long = {
    val key = dataKey(table, id)
    onKey(key)(_.ttl(key))
  }

  /**
    * The `MULTI`/`EXEC` blocks that `key`'s commands ran in, each as the commands between the two, normalized. Fails
    * if a command on `key` ran outside them.
    */
  private def transactionsOn(commands: Seq[Command], key: String): Seq[Seq[Seq[Any]]] = {
    val blocks = commands.groupBy(_.client).values.toSeq.flatMap { stream =>
      val found = mutable.ListBuffer[Seq[Command]]()
      var current: Option[mutable.ListBuffer[Command]] = None
      stream.foreach { command =>
        command.name match {
          case "MULTI" => current = Some(mutable.ListBuffer())
          case "EXEC" =>
            current.foreach(block => found += block.toList)
            current = None
          case _ => current.foreach(_ += command)
        }
      }
      found.toList.filter(_.exists(_.key.contains(key)))
    }
    withClue(s"commands on $key outside MULTI/EXEC: ") {
      commands.count(_.key.contains(key)) shouldBe blocks.map(_.count(_.key.contains(key))).sum
    }
    blocks.map(_.map(_.normalized))
  }

  /** The commands on the table's row keys, normalized. */
  private def rowCommands(commands: Seq[Command], table: String): Seq[Seq[Any]] =
    commands.filter(_.key.exists(_.startsWith(s"$table:"))).map(_.normalized)

  test("write.mode merge, the default, keeps the stored fields that the new row doesn't have or has as null") {
    Seq(None, Some("merge")).foreach { mode =>
      withClue(s"$WriteMode $mode: ") {
        val table = generateTableName("write-mode-merge")
        val commands = overwriteRows(table, mode.map(WriteMode -> _).toMap)
        hash(table, "john") shouldBe Map("name" -> "Johnny", "age" -> "30", "city" -> "London")
        hash(table, "peter") shouldBe Map("name" -> "Peter", "age" -> "45", "city" -> "Paris")
        hash(table, "ann") shouldBe Map("name" -> "Ann", "age" -> "25")
        // as before: one HSET per row, no MULTI and no UNLINK
        rowCommands(commands, table).sortBy(_(1).toString) shouldBe Seq(
          Seq("HSET", dataKey(table, "ann"), Map("name" -> "Ann", "age" -> "25")),
          Seq("HSET", dataKey(table, "john"), Map("name" -> "Johnny")),
          Seq("HSET", dataKey(table, "peter"), Map("name" -> "Peter", "age" -> "45")))
        commands.filter(_.name == "MULTI") shouldBe empty
      }
    }
  }

  test("write.mode replace makes each stored hash exactly the new row") {
    val table = generateTableName("write-mode-replace")
    overwriteRows(table, Map(WriteMode -> "replace"))
    hash(table, "john") shouldBe Map("name" -> "Johnny")
    hash(table, "peter") shouldBe Map("name" -> "Peter", "age" -> "45")
    hash(table, "ann") shouldBe Map("name" -> "Ann", "age" -> "25")
    val loaded = spark.read.format(RedisFormat)
      .option(SqlOptionTableName, table)
      .option(SqlOptionKeyColumn, "id")
      .load()
      .collect()
      .map(row => (row.getAs[String]("id"), row.getAs[String]("name"), row.getAs[Any]("age")))
    loaded.sortBy(_._1) shouldBe Array(("ann", "Ann", 25), ("john", "Johnny", null), ("peter", "Peter", 45))
  }

  test("write.mode replace writes each row in one MULTI/EXEC on its key: UNLINK, then HSET") {
    val table = generateTableName("write-mode-replace-tx")
    val commands = overwriteRows(table, Map(WriteMode -> "replace"))
    val fields = Map("john" -> Map("name" -> "Johnny"), "peter" -> Map("name" -> "Peter", "age" -> "45"),
      "ann" -> Map("name" -> "Ann", "age" -> "25"))
    ids.foreach { id =>
      val key = dataKey(table, id)
      transactionsOn(commands, key) shouldBe Seq(Seq(Seq("UNLINK", key), Seq("HSET", key, fields(id))))
    }
  }

  test("write.mode replace with a ttl sets each row's TTL in the same MULTI/EXEC") {
    val table = generateTableName("write-mode-replace-ttl")
    val commands = overwriteRows(table, Map(WriteMode -> "replace", SqlOptionTTL -> Ttl.toString))
    val fields = Map("john" -> Map("name" -> "Johnny"), "peter" -> Map("name" -> "Peter", "age" -> "45"),
      "ann" -> Map("name" -> "Ann", "age" -> "25"))
    ids.foreach { id =>
      val key = dataKey(table, id)
      transactionsOn(commands, key) shouldBe
        Seq(Seq(Seq("UNLINK", key), Seq("HSET", key, fields(id)), Seq("EXPIRE", key, Ttl.toString)))
      withClue(s"TTL of $key: ") {
        ttl(table, id) should (be > 0L and be <= Ttl.toLong)
      }
    }
    hash(table, "john") shouldBe Map("name" -> "Johnny")
  }

  test("write.mode replace without a ttl leaves a replaced row without a TTL, like a new row") {
    val table = generateTableName("write-mode-replace-no-ttl")
    overwriteRows(table, Map(WriteMode -> "replace"), storedOptions = Map(SqlOptionTTL -> Ttl.toString))
    ids.foreach { id =>
      withClue(s"TTL of $id: ") {
        ttl(table, id) shouldBe -1L
      }
    }
  }

  test("write.mode replace removes a stored row whose new fields are all null") {
    val table = generateTableName("write-mode-replace-nulls")
    write(table, storedSchema, storedRows, Map.empty)
    val commands = monitored {
      write(table, newSchema, Seq(Row("john", null, null)), Map(WriteMode -> "replace", SqlOptionTTL -> Ttl.toString))
    }
    val key = dataKey(table, "john")
    onKey(key)(_.exists(key)) shouldBe false
    transactionsOn(commands, key) shouldBe Seq(Seq(Seq("UNLINK", key)))
    hash(table, "peter") shouldBe Map("name" -> "Peter", "age" -> "45", "city" -> "Paris")
  }

  test("any other write.mode fails before anything is written, with a message that names the option") {
    Seq(SqlOptionModelHash, SqlOptionModelBinary).foreach { model =>
      withClue(s"model $model: ") {
        val table = generateTableName("write-mode-invalid")
        val e = intercept[Exception] {
          write(table, storedSchema, storedRows, Map(SqlOptionModel -> model, WriteMode -> "upsert"))
        }
        val message = findCause[IllegalArgumentException](e).getMessage
        message should include(s"'$WriteMode'")
        message should include("'upsert'")
        message should include("'merge'")
        message should include("'replace'")
        onKey(schemaKey(table))(_.exists(schemaKey(table))) shouldBe false
        storedRows.foreach { row =>
          val key = dataKey(table, row.getString(0))
          onKey(key)(_.exists(key)) shouldBe false
        }
      }
    }
  }
}
