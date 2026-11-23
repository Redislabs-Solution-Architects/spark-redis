package com.redislabs.provider.redis.env

import com.redislabs.provider.redis.{RedisConfig, RedisEndpoint}
import org.apache.spark.SparkConf

/**
 * Cluster with user/password authentication
 */
trait RedisClusterAclEnv extends Env {
  override def redisPort: Int = RedisContainers.clusterPort

  override val conf: SparkConf = new SparkConf()
    .setMaster("local[*]").setAppName(getClass.getName)
    .set("spark.redis.host", redisHost)
    .set("spark.redis.port", s"$redisPort")
    .set("spark.redis.user", user)
    .set("spark.redis.password", userPassword)
    .set("spark.driver.bindAddress", "127.0.0.1")
    // Spark 4 executors fetch classes from the driver during code generation, at spark.driver.host: advertise
    // the loopback address the driver binds to, or every lookup waits on a LAN address nothing listens on.
    .set("spark.driver.host", "127.0.0.1")

  override val redisConfig: RedisConfig =
    new RedisConfig(RedisEndpoint(
      host = redisHost,
      port = redisPort,
      user = user,
      auth = userPassword))
}
