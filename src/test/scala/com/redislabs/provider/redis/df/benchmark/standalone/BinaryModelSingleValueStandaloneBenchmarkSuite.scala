package com.redislabs.provider.redis.df.benchmark.standalone

import com.redislabs.provider.redis.df.benchmark.SingleValueBenchmarkSuite
import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.BenchmarkTest
import org.apache.spark.sql.redis.SqlOptionModelBinary

@BenchmarkTest
class BinaryModelSingleValueStandaloneBenchmarkSuite extends SingleValueBenchmarkSuite
  with RedisStandaloneEnv {

  override def suiteTags: String = s"${super.suiteTags}, Binary, Standalone"

  override def topology: String = "standalone"

  override def persistentModel: String = SqlOptionModelBinary
}
