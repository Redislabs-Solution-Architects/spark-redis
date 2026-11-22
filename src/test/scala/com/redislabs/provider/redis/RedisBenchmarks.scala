package com.redislabs.provider.redis

import java.io.{File, FileWriter, PrintWriter}
import java.time.{Duration => JDuration}

import com.redislabs.provider.redis.util.Logging

/**
  * @author The Viet Nguyen
  */
trait RedisBenchmarks extends Logging {

  val benchmarkReportDir = new File("target/reports/benchmarks/")
  benchmarkReportDir.mkdirs()

  def time[R](tag: String)(block: => R): R = time(tag, tag)(block)

  /**
    * Runs `block` and records how long it took: `tag, duration` in `target/reports/benchmarks/results.txt`, and
    * `name,millis` in `target/benchmarks.csv`. `name` should stay the same from run to run, so that runs can be
    * compared.
    */
  def time[R](tag: String, name: String)(block: => R): R = {
    val t0 = System.nanoTime()
    val result = block // call-by-name
    val t1 = System.nanoTime()
    new PrintWriter(new FileWriter(s"$benchmarkReportDir/results.txt", true)) {
      // scalastyle:off
      this.println(s"$tag, ${JDuration.ofNanos(t1 - t0)}")
      close()
    }
    RedisBenchmarks.appendCsv(name, JDuration.ofNanos(t1 - t0).toMillis)
    result
  }
}

object RedisBenchmarks {

  val CsvFile = new File("target/benchmarks.csv")

  /** Appends `name,millis`, writing the header first if the file is new. */
  def appendCsv(name: String, millis: Long): Unit = synchronized {
    val isNew = !CsvFile.exists()
    CsvFile.getParentFile.mkdirs()
    val writer = new PrintWriter(new FileWriter(CsvFile, true))
    try {
      if (isNew) writer.write("name,millis\n")
      writer.write(s"${csvField(name)},$millis\n")
    } finally {
      writer.close()
    }
  }

  private def csvField(s: String): String =
    if (s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
