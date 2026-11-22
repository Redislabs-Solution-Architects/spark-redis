package com.redislabs.provider.redis.df.benchmark.cluster

import com.redislabs.provider.redis.df.benchmark.KeyListBenchmarkSuite
import com.redislabs.provider.redis.env.RedisClusterEnv
import com.redislabs.provider.redis.util.BenchmarkTest

@BenchmarkTest
class KeyListClusterBenchmarkSuite extends KeyListBenchmarkSuite with RedisClusterEnv {
  override def topology: String = "cluster"
}
