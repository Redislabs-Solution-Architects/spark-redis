package com.redislabs.provider.redis

import com.redislabs.provider.redis.env.Env
import com.redislabs.provider.redis.rdd.Keys
import org.apache.spark.sql.{SparkSession, classic}
import org.scalatest.{BeforeAndAfterAll, FunSuite}

/**
  * @author The Viet Nguyen
  */
trait SparkRedisSuite extends FunSuite with Env with Keys with BeforeAndAfterAll {

  override def beforeAll() {
    super.beforeAll()
    spark = SparkSession.builder().config(conf).getOrCreate()
    sc = spark.sparkContext
  }

  override def afterAll(): Unit = {
    spark.stop
    System.clearProperty("spark.driver.port")
  }

  /** `spark.implicits`, for suites to import: `spark` is a `var`, so `import spark.implicits._` doesn't compile. */
  object TestSqlImplicits extends classic.SQLImplicits {

    // Spark 4's implicits take the classic SparkSession, which is what the tests run on.
    override protected def session: classic.SparkSession = spark.asInstanceOf[classic.SparkSession]
  }

}
