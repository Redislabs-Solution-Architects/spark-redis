package com.redislabs.provider.redis.util

import redis.clients.jedis.Jedis

/** Throwaway: proves .github/workflows/guardrails.yml rejects a new KEYS call. Never merge. */
object GuardrailProbe {
  def countAll(jedis: Jedis): Int = jedis.keys("*").size
}
