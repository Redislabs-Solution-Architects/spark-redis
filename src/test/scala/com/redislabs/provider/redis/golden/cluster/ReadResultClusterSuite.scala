package com.redislabs.provider.redis.golden.cluster

import com.redislabs.provider.redis.env.RedisClusterEnv
import com.redislabs.provider.redis.golden.ReadResultSuite

class ReadResultClusterSuite extends ReadResultSuite with RedisClusterEnv {
  override def topology: String = "cluster"
}
