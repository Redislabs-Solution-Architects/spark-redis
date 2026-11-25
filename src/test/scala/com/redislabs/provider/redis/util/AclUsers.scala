package com.redislabs.provider.redis.util

import com.redislabs.provider.redis.env.Env
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import redis.clients.jedis.Jedis

/**
  * ACL users for one test. Cluster nodes don't share ACL users, so each one is created on every primary.
  *
  * A user with `-command` can't run `COMMAND INFO`, so the capability probe (`RedisCapabilities`) counts every command
  * as missing and a write takes its fallback (spec §1). That runs the fallback on a Redis line that has the command.
  */
trait AclUsers { this: Env =>

  /**
    * Runs `body` while every primary has an extra ACL user with the test password and `rules`, then removes it.
    *
    * Give each test its own `user`. Removing a user closes its connections, and the connector's pool for that user's
    * endpoint lives for the whole JVM, so a later test with the same name would borrow a closed connection.
    */
  def withAclUser[A](user: String, rules: String*)(body: => A): A = {
    val primaries = redisConfig.hosts.map(_.endpoint).distinct.toSeq
    def onPrimaries(f: Jedis => Unit): Unit = primaries.foreach(endpoint => withConnection(endpoint.connect())(f))
    onPrimaries(_.aclSetUser(user, Seq("reset", "on", s">$userPassword", "~*", "&*", "+@all") ++ rules: _*))
    try body
    finally onPrimaries(_.aclDelUser(user))
  }
}
