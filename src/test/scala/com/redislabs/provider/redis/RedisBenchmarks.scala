package com.redislabs.provider.redis

import java.io.{File, FileWriter, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardOpenOption.{APPEND, CREATE}
import java.time.{Duration => JDuration}
import java.util.concurrent.TimeUnit

import com.redislabs.provider.redis.util.Logging

/**
  * @author The Viet Nguyen
  */
trait RedisBenchmarks extends Logging {

  val benchmarkReportDir = new File("target/reports/benchmarks/")
  benchmarkReportDir.mkdirs()

  /** The text report: one `<name>, <ISO-8601 duration>` line per timing. */
  def benchmarkTextReport: File = new File(benchmarkReportDir, "results.txt")

  /** The CSV report: a `name,millis` header, then one `<name>,<millis>` row per timing. */
  def benchmarkCsvReport: File = new File("target/benchmarks.csv")

  def time[R](tag: String)(block: => R): R = {
    val t0 = System.nanoTime()
    val result = block // call-by-name
    val t1 = System.nanoTime()
    new PrintWriter(new FileWriter(benchmarkTextReport, true)) {
      // scalastyle:off
      this.println(s"$tag, ${JDuration.ofNanos(t1 - t0)}")
      close()
    }
    RedisBenchmarks.appendCsvRow(benchmarkCsvReport, tag, TimeUnit.NANOSECONDS.toMillis(t1 - t0))
    result
  }
}

object RedisBenchmarks {

  val CsvHeader = "name,millis"

  /**
    * Appends a `<name>,<millis>` row to `file`, after the header if the file is new or empty. Benchmark names hold
    * commas (`<start>, Many:1000000, Hash, Write`), so the name is quoted as RFC 4180 says.
    */
  def appendCsvRow(file: File, name: String, millis: Long): Unit = synchronized {
    Option(file.getAbsoluteFile.getParentFile).foreach(_.mkdirs())
    val header = if (file.length() == 0) s"$CsvHeader\n" else ""
    Files.write(file.toPath, s"$header${csvField(name)},$millis\n".getBytes(UTF_8), CREATE, APPEND)
  }

  def csvField(value: String): String =
    if (value.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r')) {
      "\"" + value.replace("\"", "\"\"") + "\""
    } else {
      value
    }
}
