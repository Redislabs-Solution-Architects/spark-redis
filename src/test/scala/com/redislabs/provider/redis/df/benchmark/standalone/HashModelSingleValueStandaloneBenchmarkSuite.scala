package com.redislabs.provider.redis.df.benchmark.standalone

import com.redislabs.provider.redis.df.benchmark.SingleValueBenchmarkSuite
import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.BenchmarkTest
import org.apache.spark.sql.redis.SqlOptionModelHash

@BenchmarkTest
class HashModelSingleValueStandaloneBenchmarkSuite extends SingleValueBenchmarkSuite
  with RedisStandaloneEnv {

  override def suiteTags: String = s"${super.suiteTags}, Hash, Standalone"

  override def topology: String = "standalone"

  override def persistentModel: String = SqlOptionModelHash
}
