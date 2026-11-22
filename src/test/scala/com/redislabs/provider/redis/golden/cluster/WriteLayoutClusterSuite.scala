package com.redislabs.provider.redis.golden.cluster

import com.redislabs.provider.redis.env.RedisClusterEnv
import com.redislabs.provider.redis.golden.WriteLayoutSuite

class WriteLayoutClusterSuite extends WriteLayoutSuite with RedisClusterEnv {
  override def topology: String = "cluster"
}
