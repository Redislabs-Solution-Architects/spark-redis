package com.redislabs.provider.redis.util

import java.sql.{Date, Timestamp}

import org.apache.spark.sql.types._
import org.scalatest.{FunSuite, Matchers}

/**
  * #358: a value that can't be parsed as its column's type fails with an error that names the field, the value and
  * the type, and keeps the exception type the parser has always thrown.
  */
class ParseUtilsTest extends FunSuite with Matchers {

  private val Field = "col_358"

  private def parse(dataType: DataType, value: String): Any =
    ParseUtils.parseFields(Map(Field -> value), StructType(Array(StructField(Field, dataType))))(0)

  test("parses a valid value of every type") {
    parse(ByteType, "2") shouldBe (2: Byte)
    parse(ShortType, "7") shouldBe (7: Short)
    parse(IntegerType, "1") shouldBe 1
    parse(LongType, "3") shouldBe 3L
    parse(FloatType, "4.2") shouldBe 4.2f
    parse(DoubleType, "5.3") shouldBe 5.3d
    parse(BooleanType, "true") shouldBe true
    parse(BooleanType, "false") shouldBe false
    parse(DateType, "2022-10-14") shouldBe Date.valueOf("2022-10-14")
    parse(TimestampType, "2022-10-14 13:33:15.012345") shouldBe Timestamp.valueOf("2022-10-14 13:33:15.012345")
    parse(StringType, "str8") shouldBe "str8"
  }

  test("a missing value reads as null for every type") {
    Seq(ByteType, ShortType, IntegerType, LongType, FloatType, DoubleType, BooleanType, DateType, TimestampType,
      StringType).foreach { dataType =>
      parse(dataType, null) shouldBe (null: Any)
    }
  }

  // (type, a value it can't parse, the exception the parser throws for it)
  private val badValues = Seq[(DataType, String, Class[_ <: IllegalArgumentException])](
    (ByteType, "300", classOf[NumberFormatException]),
    (ShortType, "7s", classOf[NumberFormatException]),
    (IntegerType, "thirty", classOf[NumberFormatException]),
    (IntegerType, "", classOf[NumberFormatException]),
    (LongType, "3.5", classOf[NumberFormatException]),
    (FloatType, "4,2", classOf[NumberFormatException]),
    (DoubleType, "five", classOf[NumberFormatException]),
    (DateType, "2022/10/14", classOf[IllegalArgumentException]),
    (TimestampType, "2022-10-14T13:33:15.012345", classOf[IllegalArgumentException])
  )

  badValues.foreach { case (dataType, value, parserException) =>
    test(s"a bad ${dataType.typeName} value '$value' names the field, the value and the type") {
      val e = intercept[IllegalArgumentException] {
        parse(dataType, value)
      }
      withClue(s"${e.getClass.getName} is not a ${parserException.getName}:") {
        parserException.isInstance(e) shouldBe true
      }
      e.getMessage should include(s"'$Field'")
      e.getMessage should include(s"'$value'")
      e.getMessage should include(dataType.typeName)
      withClue("the parser's own exception is kept as the cause:") {
        parserException.isInstance(e.getCause) shouldBe true
      }
    }
  }
}
