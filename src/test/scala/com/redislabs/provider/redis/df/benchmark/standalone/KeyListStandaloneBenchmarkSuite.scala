package com.redislabs.provider.redis.df.benchmark.standalone

import com.redislabs.provider.redis.df.benchmark.KeyListBenchmarkSuite
import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.BenchmarkTest

@BenchmarkTest
class KeyListStandaloneBenchmarkSuite extends KeyListBenchmarkSuite with RedisStandaloneEnv {
  override def topology: String = "standalone"
}
