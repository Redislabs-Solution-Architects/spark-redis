package com.redislabs.provider.redis.golden.standalone

import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.golden.ReadResultSuite

class ReadResultStandaloneSuite extends ReadResultSuite with RedisStandaloneEnv {
  override def topology: String = "standalone"
}
