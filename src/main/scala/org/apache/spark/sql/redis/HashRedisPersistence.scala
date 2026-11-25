package org.apache.spark.sql.redis

import java.util.{List => JList}

import com.redislabs.provider.redis.util.ParseUtils
import com.redislabs.provider.redis.util.PipelineUtils.WriteCommand
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types._
import redis.clients.jedis.{Pipeline, PipelineBase}

import scala.jdk.CollectionConverters._

/**
  * Stores each row as a hash of its non-null fields, without the key column.
  *
  * @param replace what a row does to a hash already stored under its key (`write.mode`): false (`merge`) sets the
  *                row's fields and keeps the others, true (`replace`) makes the stored hash exactly the row
  * @author The Viet Nguyen
  */
class HashRedisPersistence(val replace: Boolean = false) extends RedisPersistence[Any] {

  /**
    * Sends [[saveCommands]] on `pipeline`. They aren't a transaction: with `replace`, or with a `ttl`, a reader can see
    * the row between two of them unless the caller wraps them in `MULTI`/`EXEC`.
    */
  override def save(pipeline: Pipeline, key: String, value: Any, ttl: Int): Unit =
    saveCommands(key, value, ttl).foreach(command => command(pipeline))

  /**
    * With `replace`, `UNLINK` of the stored hash first. Then `HSET` of the row's fields, and `EXPIRE` when `ttl` is
    * given. A row with no non-null fields sends no `HSET`, which Redis would reject; with `replace` it sends no
    * `EXPIRE` either, since its key is gone.
    */
  override def saveCommands(key: String, value: Any, ttl: Int): List[WriteCommand] = {
    val fields = value.asInstanceOf[Map[String, String]]
    // the type ascription fixes an ambiguous reference to an overloaded definition
    val remove: List[WriteCommand] =
      if (replace) List((pipeline: Pipeline) => (pipeline: PipelineBase).unlink(key)) else Nil
    val set: List[WriteCommand] =
      if (fields.nonEmpty) List((pipeline: Pipeline) => pipeline.hset(key, fields.asJava)) else Nil
    // with replace, a row with no fields leaves no key to expire
    val expires = ttl > 0 && (fields.nonEmpty || !replace)
    val expire: List[WriteCommand] =
      if (expires) List((pipeline: Pipeline) => pipeline.expire(key, ttl.toLong)) else Nil
    remove ++ set ++ expire
  }

  override def load(pipeline: Pipeline, key: String, requiredColumns: Seq[String]): Unit = {
    pipeline.hmget(key, requiredColumns: _*)
  }

  override def encodeRow(keyName: String, value: Row): Map[String, String] = {
    val fields = value.schema.fields.map(_.name)
    val kvMap = value.getValuesMap[Any](fields)
    kvMap
      .filter { case (_, v) =>
        // don't store null values
        v != null
      }
      .filter { case (k, _) =>
        // don't store key values
        k != keyName
      }
      .map { case (k, v) =>
        k -> String.valueOf(v)
      }
  }

  override def decodeRow(keyMap: (String, String), value: Any, schema: StructType,
                         requiredColumns: Seq[String]): Row = {
    val scalaValue = value.asInstanceOf[JList[String]].asScala
    val values = requiredColumns.zip(scalaValue)
    val results = values :+ keyMap
    val fieldsValue = ParseUtils.parseFields(results.toMap, schema)
    new GenericRowWithSchema(fieldsValue, schema)
  }
}
