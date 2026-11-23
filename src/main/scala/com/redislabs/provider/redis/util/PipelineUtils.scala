package com.redislabs.provider.redis.util

import java.util.{List => JList}

import com.redislabs.provider.redis.{ReadWriteConfig, RedisEndpoint}
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import redis.clients.jedis.exceptions.{
  JedisAskDataException,
  JedisClusterOperationException,
  JedisDataException,
  JedisMovedDataException,
  JedisRedirectionException
}
import redis.clients.jedis.{Jedis, Pipeline, Response}

import scala.collection.JavaConverters._
import scala.collection.{TraversableOnce, mutable}

object PipelineUtils {

  /**
    * Executes a pipeline function for each item in the sequence, returns the server response.
    *
    * Ensures that a new pipeline is created if the number of operations exceeds the given maxPipelineSize
    * while iterating over the items.
    *
    * @param conn            jedis connection
    * @param readWriteConfig read/write config
    * @param items           a sequence of elements (typically keys)
    * @param f               function to applied for each item in the sequence
    * @return response from the server
    */
  def mapWithPipeline[A](conn: Jedis, items: TraversableOnce[A])(f: (Pipeline, A) => Unit)(
      implicit readWriteConfig: ReadWriteConfig): Seq[AnyRef] = {
    val totalResp = mutable.ListBuffer[JList[AnyRef]]()

    // iterate over items and create new pipelines periodically
    var i = 0
    var pipeline = conn.pipelined()
    for (x <- items) {
      f(pipeline, x)
      i = i + 1
      if (i % readWriteConfig.maxPipelineSize == 0) {
        val resp = pipeline.syncAndReturnAll()
        totalResp += resp
        pipeline = conn.pipelined()
      }
    }

    // sync remaining items
    if (i % readWriteConfig.maxPipelineSize != 0) {
      val resp = pipeline.syncAndReturnAll()
      totalResp += resp
    }

    totalResp.flatMap(_.asScala)
  }

  /**
    * Executes a pipeline function for each item in the sequence. No response is returned.
    *
    * Ensures that a new pipeline is created if the number of operations exceeds the given maxPipelineSize
    * while iterating over the items.
    *
    * @param conn            jedis connection
    * @param readWriteConfig read/write config
    * @param items           a sequence of elements (typically keys)
    * @param f               function to applied for each item in the sequence
    */
  def foreachWithPipeline[A](conn: Jedis, items: TraversableOnce[A])(f: (Pipeline, A) => Unit)(
      implicit readWriteConfig: ReadWriteConfig): Unit = {
    // iterate over items and create new pipelines periodically
    var i = 0
    var pipeline = conn.pipelined()
    for (x <- items) {
      f(pipeline, x)
      i = i + 1
      if (i % readWriteConfig.maxPipelineSize == 0) {
        pipeline.sync()
        pipeline = conn.pipelined()
      }
    }

    // sync remaining items
    if (i % readWriteConfig.maxPipelineSize != 0) {
      pipeline.sync()
    }
  }

  /**
    * Executes a pipeline function for each item in the sequence. Doesn't sync and return the last pipeline after
    * all operations are executed. Allows to execute more operations with the returned pipeline.
    * The client is responsible of syncing the returned pipeline.
    *
    * Ensures that a new pipeline is created if the number of operations exceeds the given maxPipelineSize
    * while iterating over the items.
    *
    * @param conn            jedis connection
    * @param readWriteConfig read/write config
    * @param items           a sequence of elements (typically keys)
    * @param f               function to applied for each item in the sequence
    * @return the last pipeline
    */
  def foreachWithPipelineNoLastSync[A](conn: Jedis, items: TraversableOnce[A])(f: (Pipeline, A) => Unit)(
      implicit readWriteConfig: ReadWriteConfig): Pipeline = {
    // iterate over items and create new pipelines periodically
    var i = 0
    var pipeline = conn.pipelined()
    for (x <- items) {
      f(pipeline, x)
      i = i + 1
      if (i % readWriteConfig.maxPipelineSize == 0) {
        pipeline.sync()
        pipeline = conn.pipelined()
      }
    }

    // return pipeline, the client should sync pipeline
    pipeline
  }

  /** How many times one write follows a `MOVED` or `ASK` redirect before it fails. */
  val MaxRedirects = 5

  /** Where to send a write: an endpoint, and whether to send `ASKING` first. */
  private case class WriteTarget(endpoint: RedisEndpoint, asking: Boolean)

  /**
    * Sends one command per item, pipelined to the endpoint paired with the item, and checks every reply.
    *
    * An item the server answers with `MOVED` (its slot has moved since the topology was read) is sent again,
    * pipelined, to the node the redirect names. An item answered with `ASK` (its slot is being migrated) is sent
    * there on its own, after `ASKING`. Each item follows at most [[MaxRedirects]] redirects. Any other error reply
    * fails the write with an exception, so a partial write never passes silently.
    *
    * @param items           items, each paired with the endpoint that owns its key in the cached topology
    * @param write           sends exactly one command for the item and returns its response
    * @param readWriteConfig read/write config
    */
  def foreachWithRedirects[A](items: Seq[(RedisEndpoint, A)])(write: (Pipeline, A) => Response[_])(
      implicit readWriteConfig: ReadWriteConfig): Unit = {
    // Strict collections throughout: a lazy one could send its writes after the connection is closed.
    var pending: List[(WriteTarget, A)] = items.toList.map { case (endpoint, item) =>
      (WriteTarget(endpoint, asking = false), item)
    }
    var redirects = 0
    while (pending.nonEmpty) {
      if (redirects > MaxRedirects) {
        throw new JedisClusterOperationException(
          s"${pending.size} writes were still redirected after $MaxRedirects redirects")
      }
      pending = pending.groupBy(_._1).toList.flatMap { case (target, group) =>
        writeTo(target, group.map(_._2), write)
      }
      redirects += 1
    }
  }

  /** Writes `items` to `target`, and returns the items the server redirected, each with its next target. */
  private def writeTo[A](target: WriteTarget, items: Seq[A], write: (Pipeline, A) => Response[_])(
      implicit readWriteConfig: ReadWriteConfig): Seq[(WriteTarget, A)] = {
    withConnection(target.endpoint.connect()) { conn =>
      val responses = if (target.asking) {
        // ASKING applies only to the command that follows it, so each item goes in a pipeline of its own.
        items.map { item =>
          conn.asking()
          val pipeline = conn.pipelined()
          val response = write(pipeline, item)
          pipeline.sync()
          (item, response)
        }
      } else {
        items.grouped(readWriteConfig.maxPipelineSize).toList.flatMap { batch =>
          val pipeline = conn.pipelined()
          val batchResponses = batch.map(item => (item, write(pipeline, item)))
          pipeline.sync()
          batchResponses
        }
      }
      responses.flatMap { case (item, response) =>
        try {
          response.get
          None
        } catch {
          case e: JedisMovedDataException => Some((WriteTarget(redirected(target.endpoint, e), asking = false), item))
          case e: JedisAskDataException => Some((WriteTarget(redirected(target.endpoint, e), asking = true), item))
          case e: JedisDataException =>
            // Only host and port: the endpoint's toString would include its password.
            val node = s"${target.endpoint.host}:${target.endpoint.port}"
            throw new JedisDataException(s"Write to $node failed: ${e.getMessage}", e)
        }
      }
    }
  }

  /** The endpoint a redirect names, with the same user, password, timeout and TLS settings as `from`. */
  private def redirected(from: RedisEndpoint, e: JedisRedirectionException): RedisEndpoint =
    from.copy(host = e.getTargetNode.getHost, port = e.getTargetNode.getPort)

}
