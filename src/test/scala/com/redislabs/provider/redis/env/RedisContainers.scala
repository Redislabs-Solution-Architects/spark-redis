package com.redislabs.provider.redis.env

import java.net.{InetAddress, ServerSocket}
import java.time.Duration

import scala.util.Try

import org.testcontainers.containers.FixedHostPortGenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

/**
  * Runs the Redis servers that the test suites expect, in Docker, on the fixed host ports the suites already use:
  * 6379 (standalone), 6380 (TLS) and 7379-7381 (cluster). Each server starts once per test JVM, the first time a
  * suite needs it, and Testcontainers removes it when the JVM exits.
  *
  * Because the host ports are fixed, only one test run per machine can use the containers at a time.
  *
  * System properties:
  *  - `redis.image`: the Redis image to run (default `redis:8.2`)
  *  - `redis.containers`: set to `false` to use Redis servers started outside the tests instead
  */
object RedisContainers {

  val Image: String = sys.props.getOrElse("redis.image", "redis:8.2")

  private val Enabled: Boolean = sys.props.get("redis.containers").forall(_.toBoolean)
  private val ConfDir = "/usr/local/etc/redis"
  private val StartupTimeout = Duration.ofSeconds(90)
  private val ReadOnly: Int = Integer.parseInt("644", 8)
  private val Executable: Int = Integer.parseInt("755", 8)

  private class RedisContainer extends FixedHostPortGenericContainer[RedisContainer](Image)

  private def copy(container: RedisContainer, resource: String, target: String, mode: Int = ReadOnly): Unit = {
    container.withCopyFileToContainer(MountableFile.forClasspathResource(resource, mode), target)
  }

  /** Fails with a clear message, instead of a Docker stack trace, when another process already holds a port. */
  private def requireFree(ports: Int*): Unit = ports.foreach { port =>
    val free = Try(new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()).isSuccess
    require(free, s"Port $port is already in use, so the test Redis ($Image) cannot start. Stop whatever holds " +
      "it, or run with -Dredis.containers=false to test against the Redis already listening there.")
  }

  private def startedWhen(container: RedisContainer, logLine: String): RedisContainer = {
    container.waitingFor(Wait.forLogMessage(s".*$logLine.*\\n", 1).withStartupTimeout(StartupTimeout))
    container.start()
    container
  }

  private lazy val standalone: RedisContainer = {
    val c = new RedisContainer
    copy(c, "containers/standalone.conf", s"$ConfDir/redis.conf")
    copy(c, "containers/users.acl", s"$ConfDir/users.acl")
    requireFree(6379)
    c.withFixedExposedPort(6379, 6379)
    c.withCommand("redis-server", s"$ConfDir/redis.conf")
    startedWhen(c, "Ready to accept connections")
  }

  private lazy val tls: RedisContainer = {
    val c = new RedisContainer
    copy(c, "containers/standalone-tls.conf", s"$ConfDir/redis.conf")
    copy(c, "containers/users.acl", s"$ConfDir/users.acl")
    Seq("redis.crt", "redis.key", "ca.crt", "redis.dh").foreach { name =>
      copy(c, s"tls/$name", s"$ConfDir/tls/$name")
    }
    requireFree(6380)
    c.withFixedExposedPort(6380, 6380)
    c.withCommand("redis-server", s"$ConfDir/redis.conf")
    startedWhen(c, "Ready to accept connections")
  }

  private lazy val cluster: RedisContainer = {
    val c = new RedisContainer
    copy(c, "containers/users-cluster.acl", s"$ConfDir/users-cluster.acl")
    copy(c, "containers/start-cluster.sh", s"$ConfDir/start-cluster.sh", Executable)
    val ports = Seq(7379, 7380, 7381)
    requireFree(ports: _*)
    ports.foreach(port => c.withFixedExposedPort(port, port))
    c.withCommand("sh", s"$ConfDir/start-cluster.sh")
    startedWhen(c, "CLUSTER READY")
  }

  /** Standalone Redis on 6379, with ACL users `default`/`passwd` and `alice`/`p1pp0`. */
  def ensureStandalone(): Unit = if (Enabled) require(standalone.isRunning, s"standalone $Image is not running")

  /** TLS-only Redis on 6380, with the same ACL users, using the certificates in `src/test/resources/tls`. */
  def ensureTls(): Unit = if (Enabled) require(tls.isRunning, s"TLS $Image is not running")

  /** Three-primary cluster on 7379-7381, with `default` (no password) and `alice`/`p1pp0`. */
  def ensureCluster(): Unit = if (Enabled) require(cluster.isRunning, s"cluster $Image is not running")
}
