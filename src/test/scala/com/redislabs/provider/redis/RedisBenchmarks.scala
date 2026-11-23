package com.redislabs.provider.redis

import java.io.{File, FileOutputStream, FileWriter, OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets
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

  /** Appends `name,millis` to `target/benchmarks.csv`, writing the header first if the file is new or empty. */
  def appendCsv(name: String, millis: Long): Unit = appendCsv(CsvFile, name, millis)

  /**
    * Appends `name,millis` to `file`, writing the header first if the file is new or empty. The file is UTF-8.
    *
    * The size check and the write run under this object's lock and a file lock, so writers in this JVM or in another
    * one sharing `target/` can't both write the header or interleave their rows.
    */
  private[redis] def appendCsv(file: File, name: String, millis: Long): Unit = synchronized {
    Option(file.getAbsoluteFile.getParentFile).foreach(_.mkdirs())
    val stream = new FileOutputStream(file, true)
    try {
      stream.getChannel.lock()
      val writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)
      if (stream.getChannel.size() == 0) writer.write("name,millis\n")
      writer.write(s"${csvField(name)},$millis\n")
      writer.flush()
    } finally {
      stream.close() // also releases the lock
    }
  }

  private def csvField(s: String): String =
    if (s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
