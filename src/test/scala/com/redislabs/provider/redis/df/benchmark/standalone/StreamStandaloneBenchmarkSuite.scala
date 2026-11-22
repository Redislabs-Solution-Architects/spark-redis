package com.redislabs.provider.redis.df.benchmark.standalone

import com.redislabs.provider.redis.df.benchmark.StreamBenchmarkSuite
import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.BenchmarkTest

@BenchmarkTest
class StreamStandaloneBenchmarkSuite extends StreamBenchmarkSuite with RedisStandaloneEnv {
  override def topology: String = "standalone"
}
