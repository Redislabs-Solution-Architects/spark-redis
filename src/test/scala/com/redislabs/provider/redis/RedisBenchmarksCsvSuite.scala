package com.redislabs.provider.redis

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.JavaConverters._

import org.scalatest.{FunSuite, Matchers}

/**
  * The CSV sink behind `RedisBenchmarks.time`. Needs no Redis.
  */
class RedisBenchmarksCsvSuite extends FunSuite with Matchers {

  private def withCsvFile(body: File => Unit): Unit = {
    val dir = Files.createTempDirectory("benchmarks-csv").toFile
    val file = new File(dir, "nested/benchmarks.csv")
    try body(file)
    finally {
      file.delete()
      file.getParentFile.delete()
      dir.delete()
    }
  }

  private def lines(file: File): Seq[String] =
    Files.readAllLines(file.toPath, StandardCharsets.UTF_8).asScala.toSeq

  test("a new file gets the header, then one row per call") {
    withCsvFile { file =>
      RedisBenchmarks.appendCsv(file, "write", 12)
      RedisBenchmarks.appendCsv(file, "read", 0)
      lines(file) shouldBe Seq("name,millis", "write,12", "read,0")
    }
  }

  test("an existing file is appended to, without a second header") {
    withCsvFile { file =>
      RedisBenchmarks.appendCsv(file, "first", 1)
      RedisBenchmarks.appendCsv(file, "second", 2)
      lines(file).count(_ == "name,millis") shouldBe 1
      lines(file).last shouldBe "second,2"
    }
  }

  test("an empty file counts as new") {
    withCsvFile { file =>
      file.getParentFile.mkdirs()
      file.createNewFile() shouldBe true
      RedisBenchmarks.appendCsv(file, "x", 3)
      lines(file) shouldBe Seq("name,millis", "x,3")
    }
  }

  test("names with commas or quotes are quoted, so each row keeps two fields") {
    withCsvFile { file =>
      RedisBenchmarks.appendCsv(file, "1732300000000, Single, Hash, Write", 42)
      RedisBenchmarks.appendCsv(file, "say \"hi\"", 7)
      lines(file).tail shouldBe Seq("\"1732300000000, Single, Hash, Write\",42", "\"say \"\"hi\"\"\",7")
    }
  }

  test("calls from many threads write one header and every row whole") {
    withCsvFile { file =>
      val threads = (1 to 8).map { t =>
        new Thread(new Runnable {
          override def run(): Unit = (1 to 50).foreach(i => RedisBenchmarks.appendCsv(file, s"t$t-$i", i))
        })
      }
      threads.foreach(_.start())
      threads.foreach(_.join())
      val all = lines(file)
      all.head shouldBe "name,millis"
      all.tail should have size 400
      all.tail.foreach(_ should fullyMatch regex "t[0-9]+-[0-9]+,[0-9]+")
    }
  }
}
