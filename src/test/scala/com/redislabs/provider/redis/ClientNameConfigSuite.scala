package com.redislabs.provider.redis

import com.redislabs.provider.redis.RedisConfig.{ClientNameConfKey, ClientNameOption}
import org.apache.spark.SparkConf
import org.scalatest.{FunSuite, Matchers}

/**
  * How `spark.redis.client.name` and the `client.name` source option become [[RedisEndpoint.clientName]] (#398).
  * No Redis server is needed: building an endpoint does not connect. [[ClientNameSuite]] checks that connections
  * carry the name.
  */
class ClientNameConfigSuite extends FunSuite with Matchers {

  private def conf(settings: (String, String)*): SparkConf = new SparkConf(false).setAll(settings)

  private val noOptions = Map.empty[String, String]

  test("without the setting or the option an endpoint has no client name") {
    new RedisEndpoint(conf()).clientName shouldBe null
    new RedisEndpoint(conf(), noOptions).clientName shouldBe null
    RedisEndpoint().clientName shouldBe null
    new RedisEndpoint("redis://localhost:6379").clientName shouldBe null
  }

  test("spark.redis.client.name sets the endpoint's client name") {
    new RedisEndpoint(conf(ClientNameConfKey -> "etl-job")).clientName shouldBe "etl-job"
    new RedisEndpoint(conf(ClientNameConfKey -> "etl-job"), noOptions).clientName shouldBe "etl-job"
  }

  test("the client.name option overrides spark.redis.client.name") {
    new RedisEndpoint(conf(ClientNameConfKey -> "conf"), Map(ClientNameOption -> "option")).clientName shouldBe
      "option"
    new RedisEndpoint(conf(), Map(ClientNameOption -> "option")).clientName shouldBe "option"
  }

  test("an empty setting means no client name, and an empty option removes the setting's name for that source") {
    new RedisEndpoint(conf(ClientNameConfKey -> "")).clientName shouldBe null
    new RedisEndpoint(conf(ClientNameConfKey -> ""), noOptions).clientName shouldBe null
    new RedisEndpoint(conf(ClientNameConfKey -> "conf"), Map(ClientNameOption -> "")).clientName shouldBe null
  }

  test("the other settings don't change with the client name") {
    val settings = Seq("spark.redis.host" -> "redis.example.com", "spark.redis.port" -> "6380",
      "spark.redis.timeout" -> "3000", "spark.redis.ssl" -> "true")
    new RedisEndpoint(conf(settings :+ (ClientNameConfKey -> "etl-job"): _*)) shouldBe
      new RedisEndpoint(conf(settings: _*)).copy(clientName = "etl-job")
  }

  test("endpoints that differ only by client name are different endpoints, so they don't share a pool") {
    RedisEndpoint(clientName = "a") should not equal RedisEndpoint(clientName = "b")
    RedisEndpoint(clientName = "a") should not equal RedisEndpoint()
    RedisEndpoint(clientName = "a") shouldBe RedisEndpoint(clientName = "a")
  }

  test("a client name Redis would refuse fails when the endpoint is built, naming the setting and the option") {
    Seq("etl job", "etl\njob", "naïve", "tab\there").foreach { name =>
      withClue(s"name ${name.toList}: ") {
        Seq(
          () => new RedisEndpoint(conf(ClientNameConfKey -> name)),
          () => new RedisEndpoint(conf(), Map(ClientNameOption -> name)),
          () => RedisEndpoint(clientName = name)
        ).foreach { build =>
          val e = intercept[IllegalArgumentException](build())
          e.getMessage should include(ClientNameConfKey)
          e.getMessage should include(s"'$ClientNameOption'")
        }
      }
    }
  }

  test("every printable ASCII character but the space is accepted") {
    val allowed = ('!' to '~').mkString
    RedisEndpoint(clientName = allowed).clientName shouldBe allowed
  }

  test("an endpoint's toString shows its client name") {
    RedisEndpoint(host = "h", port = 1, clientName = "etl-job").toString shouldBe
      "RedisEndpoint(h,1,null,null,0,2000,false,etl-job)"
  }
}
