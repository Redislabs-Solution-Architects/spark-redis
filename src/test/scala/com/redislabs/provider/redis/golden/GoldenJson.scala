package com.redislabs.provider.redis.golden

import org.json4s._
import org.json4s.jackson.JsonMethods

/**
  * Deterministic JSON text for the golden fixtures, so that a regenerated fixture only differs where the data does.
  * An object or array is written on one line when it fits in [[LineLimit]] characters, and one item per line
  * otherwise.
  */
object GoldenJson {

  private val LineLimit = 110
  private val Indent = 2

  def compact(v: JValue): String = JsonMethods.compact(JsonMethods.render(v))

  def parse(text: String): JValue = JsonMethods.parse(text)

  def pretty(v: JValue): String = {
    val sb = new StringBuilder
    write(sb, v, 0, 0)
    sb.append('\n').toString
  }

  private def oneLine(v: JValue): String = v match {
    case JObject(fields) => fields.map { case (k, fv) => s"${compact(JString(k))}: ${oneLine(fv)}" }
      .mkString("{", ", ", "}")
    case JArray(items) => items.map(oneLine).mkString("[", ", ", "]")
    case other => compact(other)
  }

  private def write(sb: StringBuilder, v: JValue, indent: Int, used: Int): Unit = {
    val line = oneLine(v)
    v match {
      case JObject(fields) if fields.nonEmpty && indent + used + line.length > LineLimit =>
        sb.append("{\n")
        fields.zipWithIndex.foreach { case ((k, fv), i) =>
          val name = s"${compact(JString(k))}: "
          sb.append(" " * (indent + Indent)).append(name)
          write(sb, fv, indent + Indent, name.length)
          sb.append(if (i < fields.size - 1) ",\n" else "\n")
        }
        sb.append(" " * indent).append('}')
      case JArray(items) if items.nonEmpty && indent + used + line.length > LineLimit =>
        sb.append("[\n")
        items.zipWithIndex.foreach { case (item, i) =>
          sb.append(" " * (indent + Indent))
          write(sb, item, indent + Indent, 0)
          sb.append(if (i < items.size - 1) ",\n" else "\n")
        }
        sb.append(" " * indent).append(']')
      case _ => sb.append(line)
    }
  }
}
