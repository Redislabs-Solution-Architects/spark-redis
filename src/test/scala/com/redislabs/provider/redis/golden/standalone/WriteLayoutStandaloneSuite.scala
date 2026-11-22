package com.redislabs.provider.redis.golden.standalone

import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.golden.WriteLayoutSuite

class WriteLayoutStandaloneSuite extends WriteLayoutSuite with RedisStandaloneEnv {
  override def topology: String = "standalone"
}
