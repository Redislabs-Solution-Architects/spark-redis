package com.redislabs.provider.redis

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import org.scalatest.{BeforeAndAfterEach, FunSuite, Matchers}

/**
  * `RedisBenchmarks.time` writes each timing to the text report and as a `<name>,<millis>` row of the CSV report.
  * The reports go to a temporary directory here, so the suite leaves the real ones in target/ alone.
  */
class RedisBenchmarksCsvSuite extends FunSuite with Matchers with BeforeAndAfterEach {

  private var dir: Path = _

  private class Benchmarks extends RedisBenchmarks {
    override def benchmarkTextReport: File = dir.resolve("results.txt").toFile
    override def benchmarkCsvReport: File = dir.resolve("reports/benchmarks.csv").toFile
  }

  private def csvLines: Seq[String] = Files.readAllLines(dir.resolve("reports/benchmarks.csv"), UTF_8).asScala.toSeq

  override def beforeEach(): Unit = dir = Files.createTempDirectory("redis-benchmarks")

  override def afterEach(): Unit =
    Files.walk(dir).iterator().asScala.toSeq.reverse.foreach(Files.delete)

  test("the default CSV report is target/benchmarks.csv") {
    new RedisBenchmarks {}.benchmarkCsvReport shouldBe new File("target/benchmarks.csv")
  }

  test("a new CSV report gets the header, then a row with the whole milliseconds") {
    val result = new Benchmarks().time("Write") {
      Thread.sleep(50)
      42
    }
    result shouldBe 42
    val lines = csvLines
    lines should have size 2
    lines.head shouldBe "name,millis"
    val Array(name, millis) = lines(1).split(",")
    name shouldBe "Write"
    millis should fullyMatch regex "[0-9]+"
    millis.toLong should be >= 50L
  }

  test("each timing appends a row, and the header is written once") {
    val benchmarks = new Benchmarks()
    benchmarks.time("Read")(())
    new Benchmarks().time("Take 10")(())
    csvLines.map(_.replaceAll(",[0-9]+$", ",<millis>")) shouldBe Seq("name,millis", "Read,<millis>", "Take 10,<millis>")
  }

  test("an empty CSV report gets the header") {
    val csv = new Benchmarks().benchmarkCsvReport
    csv.getParentFile.mkdirs()
    csv.createNewFile() shouldBe true
    new Benchmarks().time("Read")(())
    csvLines.head shouldBe "name,millis"
    csvLines should have size 2
  }

  test("a name with commas or quotes is quoted") {
    val benchmarks = new Benchmarks()
    benchmarks.time("1700000000000, Many:1000000, Hash, Write")(())
    benchmarks.time("""say "hi"""")(())
    csvLines.tail.map(_.replaceAll(",[0-9]+$", ",<millis>")) shouldBe
      Seq("\"1700000000000, Many:1000000, Hash, Write\",<millis>", "\"say \"\"hi\"\"\",<millis>")
  }

  test("the text report is still written") {
    new Benchmarks().time("Write")(())
    val lines = Files.readAllLines(dir.resolve("results.txt"), UTF_8).asScala
    lines should have size 1
    lines.head should fullyMatch regex "Write, PT[0-9.]+S"
  }
}
