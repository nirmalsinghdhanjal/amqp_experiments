//package com.example

import io.gatling.core.Predef._
import io.gatling.jms.Predef._
import javax.jms.ConnectionFactory
import org.apache.qpid.jms.JmsConnectionFactory
import scala.concurrent.duration._
import scala.io.Source

class AMQPSSSLSimulation extends Simulation {

  // SSL/TLS Configuration for PKCS12 certificates
  val sslContextOptions = Map(
    "transport.keyStoreLocation" -> "/Users/nirmalsingh/Documents/work/ASX/amqps_java_upgrade/src/test/resources/activemq-client.p12",
    "transport.keyStorePassword" -> "ccccc",
    "transport.keyStoreType" -> "PKCS12",
    "transport.trustStoreLocation" -> "/Users/nirmalsingh/Documents/work/ASX/amqps_java_upgrade/src/test/resources/truststore.p12", 
    "transport.trustStorePassword" -> "cccc",
    "transport.trustStoreType" -> "PKCS12",
    "transport.trustAll" -> "true",
    "transport.verifyHost" -> "false",
    "transport.enabledProtocols" -> "TLSv1.3,TLSv1.2",
    "transport.enabledCipherSuites" -> "TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    "transport.contextProtocol" -> "TLSv1.3"
  )

  // Build SSL connection URL with options
  val sslOptions = sslContextOptions.map { case (key, value) => s"$key=$value" }.mkString("&")
  val amqpsUrl = s"amqps://localhost:5671?$sslOptions"

  val xmlFilePath = System.getProperty("xml.file.path", "./src/test/resources/message.xml")
 

  val file = new java.io.File(xmlFilePath)
  if (file.exists()) {
    //val content = Source.fromFile(file, "UTF-8").mkString
    println(s"file found: $xmlFilePath, using it for message content")
  } else {
    println(s"XML file not found: $xmlFilePath, exiting simulation")
    System.exit(1)
  }

 
  val connectionFactory: ConnectionFactory = new JmsConnectionFactory(amqpsUrl)
  
  // JMS Protocol Configuration for SSL
  val jmsConfig = jms
    .connectionFactory(connectionFactory)
    .credentials("admin", "admin")
    .usePersistentDeliveryMode
    .matchByCorrelationId
    .listenerThreadCount(1)
    .replyTimeout(30.seconds)


  // SSL Send scenario
  val sslSendScenario = scenario("AMQPS SSL Send Test")
    .repeat(5) {
      exec { session =>
        val xmlContent = Source.fromFile(file, "UTF-8").mkString
        val messageId = java.util.UUID.randomUUID().toString
        val threadId = Thread.currentThread().getId
        val timestamp = System.currentTimeMillis()
        val modified = xmlContent
          .replace("{MESSAGE_ID}", s"$messageId")
          .replace("{THREAD_ID}", threadId.toString)
          .replace("{TIMESTAMP}", timestamp.toString)
          .replace("{DATE}", java.time.LocalDateTime.now().toString)
        
        println(s"Generated unique messageId: $messageId")
        println(s"Message content length: ${modified.length}")
        session.set("messageContent", modified).set("currentMessageId", messageId)
      }
      .exec(session => {
        val content = session("messageContent").as[String]
        val msgId = session("currentMessageId").as[String]
        println(s"About to send message with ID: $msgId")
        println(s"Message content preview: ${content.take(200)}...")
        session
      })
      .exec(
        jms("SSL Send Message")
          .send
          .queue("ssl.test.queue")
          .textMessage(session => session("messageContent").as[String])
          .property("messageId", session => session("currentMessageId").as[String])
          .property("secureTransport", "amqps")
          .property("certificateType", "PKCS12")
          .property("messageFormat", "XML")
      )
      .pause(2.seconds)
    }

  // SSL Request-Reply scenario
  val sslRequestReplyScenario = scenario("AMQPS SSL Request-Reply Test")
    .repeat(3) {
      exec(
        jms("SSL Request-Reply")
          .requestReply
          .queue("ssl.request.queue")
          //.queue("ssl.test.queue")
          //.replyQueue("ssl.test.queue")
          .replyQueue("ssl.reply.queue")
          .textMessage("Secure request from Gatling: ${#currentTimeMillis}")
          .property("messageType", "secure-request")
          .property("encryption", "TLS")
          .check(simpleCheck(message => {
            println(s"Received secure reply: ${message}")
            message != null
          }))
      )
      .pause(3.seconds)
    }

  // Load test setup
  setUp(
    sslSendScenario.inject(
      atOnceUsers(2),
      rampUsers(3).during(20.seconds)
    ),
    sslRequestReplyScenario.inject(
      atOnceUsers(1),
      rampUsers(2).during(15.seconds)
    )
  ).protocols(jmsConfig)
    .maxDuration(3.minutes)
    .assertions(
      global.responseTime.max.lt(10000), // SSL handshake takes longer
      global.successfulRequests.percent.gt(95)
    )
}