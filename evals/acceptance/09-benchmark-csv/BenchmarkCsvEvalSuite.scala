package com.redislabs.provider.redis.evals

import java.io.File

import com.redislabs.provider.redis.RedisBenchmarks
import org.scalatest.{FunSuite, Matchers}

import scala.io.Source

/** Acceptance test for eval task 09 (the benchmark CSV sink). Needs no Redis. Copied in after the agent finishes. */
class BenchmarkCsvEvalSuite extends FunSuite with Matchers with RedisBenchmarks {

  private val csv = new File("target/benchmarks.csv")

  private def lines(): Seq[String] =
    if (csv.exists) {
      val source = Source.fromFile(csv, "UTF-8")
      try source.getLines().toList finally source.close()
    } else {
      Nil
    }

  test("time() appends name,millis rows to target/benchmarks.csv, under a header") {
    csv.delete()
    time("eval-probe-sleep") {
      Thread.sleep(50)
    }
    time("eval-probe-fast") {
      ()
    }
    val all = lines()
    all.headOption shouldBe Some("name,millis")
    val rows = all.tail.map(_.split(",", -1).toSeq)
    rows.map(_.head) shouldBe Seq("eval-probe-sleep", "eval-probe-fast")
    rows.foreach(r => withClue(r.mkString(",")) {
      r should have size 2
      r(1) should fullyMatch regex "\\d+"
    })
    rows.head(1).toLong should be >= 50L
  }

  test("a second run appends to the same file without a second header") {
    time("eval-probe-again") {
      ()
    }
    val all = lines()
    all.count(_ == "name,millis") shouldBe 1
    all.last should startWith("eval-probe-again,")
  }

  test("the existing text report still gets written") {
    new File("target/reports/benchmarks/results.txt").exists shouldBe true
  }
}
