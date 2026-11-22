package com.redislabs.provider.redis.df.benchmark.cluster

import com.redislabs.provider.redis.df.benchmark.StreamBenchmarkSuite
import com.redislabs.provider.redis.env.RedisClusterEnv
import com.redislabs.provider.redis.util.BenchmarkTest

@BenchmarkTest
class StreamClusterBenchmarkSuite extends StreamBenchmarkSuite with RedisClusterEnv {
  override def topology: String = "cluster"
}
