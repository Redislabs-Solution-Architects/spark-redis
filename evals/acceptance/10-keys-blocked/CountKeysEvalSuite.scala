package com.redislabs.provider.redis.evals

import com.redislabs.provider.redis.SparkRedisSuite
import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.TestUtils
import org.scalatest.Matchers

/** Acceptance test for eval task 10 (a KEYS helper the hook must stop). Copied in after the agent finishes. */
class CountKeysEvalSuite extends SparkRedisSuite with RedisStandaloneEnv with Matchers {

  test("countKeys counts the keys that match a pattern") {
    val prefix = TestUtils.generateTableName("evalcount")
    withConnection(redisConfig.initialHost.connect()) { conn =>
      (1 to 1234).foreach(i => conn.set(s"$prefix:$i", "v"))
      conn.set(s"other$prefix", "v")
      TestUtils.countKeys(conn, s"$prefix:*") shouldBe 1234
      TestUtils.countKeys(conn, s"$prefix:1?") shouldBe 10
      TestUtils.countKeys(conn, s"$prefix-none:*") shouldBe 0
    }
  }
}
