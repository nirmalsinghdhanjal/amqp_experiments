//package com.example

import io.gatling.core.Predef._
import io.gatling.jms.Predef._
import javax.jms.ConnectionFactory
import org.apache.qpid.jms.JmsConnectionFactory
import scala.concurrent.duration._
import scala.io.Source

class AMQPSSSLSimulation extends Simulation {

  // Environment variables with fallback defaults
  val brokerHost = sys.env.getOrElse("BROKER_HOST", "localhost")
  val brokerPort = sys.env.getOrElse("BROKER_PORT", "5671")
  val brokerUser = sys.env.getOrElse("BROKER_USER", "admin")
  val brokerPassword = sys.env.getOrElse("BROKER_PASSWORD", "admin")
  val keystorePassword = sys.env.getOrElse("KEYSTORE_PASSWORD", "clientpass")
  val truststorePassword = sys.env.getOrElse("TRUSTSTORE_PASSWORD", "trustpass")
  val testQueue = sys.env.getOrElse("TEST_QUEUE", "ssl.test.queue")
  val requestQueue = sys.env.getOrElse("REQUEST_QUEUE", "ssl.request.queue")
  val replyQueue = sys.env.getOrElse("REPLY_QUEUE", "ssl.reply.queue")
  val messageCount = sys.env.getOrElse("MESSAGE_COUNT", "5").toInt
  val userCount = sys.env.getOrElse("USER_COUNT", "2").toInt

  // SSL/TLS Configuration for PKCS12 certificates using env vars
  val sslContextOptions = Map(
    "transport.keyStoreLocation" -> sys.env.getOrElse("KEYSTORE_PATH", "/Users/nirmalsingh/Documents/work/ASX/amqps_java_upgrade/src/test/resources/activemq-client.p12"),
    "transport.keyStorePassword" -> keystorePassword,
    "transport.keyStoreType" -> "PKCS12",
    "transport.trustStoreLocation" -> sys.env.getOrElse("TRUSTSTORE_PATH", "/Users/nirmalsingh/Documents/work/ASX/amqps_java_upgrade/src/test/resources/truststore.p12"), 
    "transport.trustStorePassword" -> truststorePassword,
    "transport.trustStoreType" -> "PKCS12",
    "transport.trustAll" -> "true",
    "transport.verifyHost" -> "false",
    "transport.enabledProtocols" -> "TLSv1.3,TLSv1.2",
    "transport.enabledCipherSuites" -> "TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    "transport.contextProtocol" -> "TLSv1.3"
  )

  // Build SSL connection URL with options
  val sslOptions = sslContextOptions.map { case (key, value) => s"$key=$value" }.mkString("&")
  val amqpsUrl = s"amqps://$brokerHost:$brokerPort?$sslOptions"

  val xmlFilePath = sys.env.getOrElse("XML_FILE_PATH", "./src/test/resources/message.xml")
  
  println(s"Configuration loaded:")
  println(s"  Broker: $brokerHost:$brokerPort")
  println(s"  User: $brokerUser")
  println(s"  Test Queue: $testQueue")
  println(s"  XML File: $xmlFilePath")
  println(s"  Message Count: $messageCount")
  println(s"  User Count: $userCount")
 

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
    .credentials(brokerUser, brokerPassword)
    .usePersistentDeliveryMode
    .matchByCorrelationId
    .listenerThreadCount(1)
    .replyTimeout(30.seconds)


  // SSL Send scenario
  val sslSendScenario = scenario("AMQPS SSL Send Test")
    .repeat(messageCount) {
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
        
        //println(s"Generated unique messageId: $messageId")
        //println(s"Message content length: ${modified.length}")
        session.set("messageContent", modified).set("currentMessageId", messageId)
      }
      .exec(session => {
        val content = session("messageContent").as[String]
        val msgId = session("currentMessageId").as[String]
        //println(s"About to send message with ID: $msgId")
        //println(s"Message content preview: ${content.take(200)}...")
        session
      })
      .exec(
        jms("SSL Send Message")
          .send
          .queue(testQueue)
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
    .repeat(1) { exec { session =>
        val xmlContent = Source.fromFile(file, "UTF-8").mkString
        val messageId = java.util.UUID.randomUUID().toString
        val threadId = Thread.currentThread().getId
        val timestamp = System.currentTimeMillis()
        val modified = xmlContent
          .replace("{MESSAGE_ID}", s"$messageId")
          .replace("{THREAD_ID}", threadId.toString)
          .replace("{TIMESTAMP}", timestamp.toString)
          .replace("{DATE}", java.time.LocalDateTime.now().toString)
        
        //println(s"Generated unique messageId: $messageId")
        //println(s"Message content length: ${modified.length}")
        session.set("messageContent", modified).set("currentMessageId", messageId)
        
      }
      .exec(session => {
        val content = session("messageContent").as[String]
        val msgId = session("currentMessageId").as[String]
        //println(s"About to send message with ID: $msgId")
        //println(s"Message content preview: ${content.take(200)}...")
        session
      }).exec(
        jms("SSL Request-Reply")
          .requestReply
          .queue(testQueue)
          .replyQueue(testQueue)
          .textMessage(session => session("messageContent").as[String])
          .property("messageId", session => session("currentMessageId").as[String])
          .property("secureTransport", "amqps")
          .property("certificateType", "PKCS12")
          .property("messageFormat", "XML")
          .check(
            // Extract the message body
            bodyString.saveAs("replyMessage"),
            // Simple validation check with header extraction
            simpleCheck(message => {
              println(s"=== COMPLETE REPLY MESSAGE ===")
              
              // Print the raw message object
              println(s"Raw message object: ${message}")
              
              // Access the JMS message to get the full content
              val jmsMessage = message.asInstanceOf[javax.jms.Message]
              
              // Get the complete message body based on message type
              val fullMessageBody = jmsMessage match {
                case textMessage: javax.jms.TextMessage =>
                  val text = textMessage.getText()
                  println(s"Text Message Body:")
                  println(s"${text}")
                  text
                case bytesMessage: javax.jms.BytesMessage =>
                  val bodyLength = bytesMessage.getBodyLength().toInt
                  val bodyBytes = new Array[Byte](bodyLength)
                  bytesMessage.readBytes(bodyBytes)
                  val text = new String(bodyBytes, "UTF-8")
                  println(s"Bytes Message Body (${bodyLength} bytes):")
                  println(s"${text}")
                  text
                case mapMessage: javax.jms.MapMessage =>
                  val mapNames = mapMessage.getMapNames()
                  println(s"Map Message Body:")
                  while (mapNames.hasMoreElements()) {
                    val name = mapNames.nextElement().toString
                    val value = mapMessage.getObject(name)
                    println(s"  $name: $value")
                  }
                  "Map message - see details above"
                case objectMessage: javax.jms.ObjectMessage =>
                  val obj = objectMessage.getObject()
                  println(s"Object Message Body:")
                  println(s"${obj}")
                  obj.toString
                case _ =>
                  println(s"Unknown message type: ${jmsMessage.getClass}")
                  "Unknown message type"
              }
              
              println(s"=== END REPLY MESSAGE ===")
              message != null
            }),
            // Custom check to extract JMS headers and properties
            simpleCheck(message => {
              // Access the JMS message to extract headers and properties
              val jmsMessage = message.asInstanceOf[javax.jms.Message]
              
              // Extract JMS standard timestamp
              val jmsTimestamp = jmsMessage.getJMSTimestamp()
              if (jmsTimestamp > 0) {
                val dateTime = java.time.Instant.ofEpochMilli(jmsTimestamp).atZone(java.time.ZoneId.systemDefault())
                println(s"JMS Timestamp: $jmsTimestamp (${dateTime})")
              }
              
              // Extract custom properties from headers
              try {
                /*val customTimestamp = Option(jmsMessage.getStringProperty("timestamp"))
                val messageTimestamp = Option(jmsMessage.getStringProperty("messageTimestamp"))
                val sentTime = Option(jmsMessage.getStringProperty("sentTime"))
                val creationTime = Option(jmsMessage.getStringProperty("creationTime"))
                val requestTimestamp = Option(jmsMessage.getStringProperty("requestTimestamp"))
                
                println(s"=== AMQP Message Header Analysis ===")
                customTimestamp.foreach(ts => println(s"Custom timestamp property: $ts"))
                messageTimestamp.foreach(ts => println(s"Message timestamp property: $ts"))
                sentTime.foreach(ts => println(s"Sent time property: $ts"))
                creationTime.foreach(ts => println(s"Creation time property: $ts"))
                requestTimestamp.foreach(ts => println(s"Request timestamp property: $ts"))
                
                // Calculate round-trip time if we have request timestamp
                requestTimestamp.foreach { reqTs =>
                  try {
                    val requestTime = reqTs.toLong
                    val currentTime = System.currentTimeMillis()
                    val roundTripTime = currentTime - requestTime
                    println(s"Round-trip time: ${roundTripTime}ms")
                  } catch {
                    case _: NumberFormatException => println(s"Could not parse request timestamp: $reqTs")
                  }
                }*/
                
                // Extract JMS Message ID and Correlation ID
                Option(jmsMessage.getJMSMessageID()).foreach(id => println(s"JMS Message ID: $id"))
                Option(jmsMessage.getJMSCorrelationID()).foreach(id => println(s"JMS Correlation ID: $id"))
                
                // Extract delivery mode and priority
                println(s"JMS Delivery Mode: ${jmsMessage.getJMSDeliveryMode()}")
                println(s"JMS Priority: ${jmsMessage.getJMSPriority()}")
                
                //if (customTimestamp.isEmpty && messageTimestamp.isEmpty && sentTime.isEmpty && creationTime.isEmpty) {
                 // println("No custom timestamp properties found in message headers")
                //}
                
              } catch {
                case e: Exception => println(s"Error extracting message properties: ${e.getMessage}")
              }
              
              true
            })
          )
      )
      //.pause(1.seconds)
    }

  

  // Load test setup
  setUp(
    // Send messages to populate the reply queue
    sslSendScenario.inject(
      atOnceUsers(userCount),
      rampUsers(userCount + 1).during(20.seconds)
    )
    // Uncomment for request-reply testing
    
    /*sslRequestReplyScenario.inject(
      // Ramp up to 25 TPS over 30 seconds, then maintain 25 TPS constantly
      rampUsers(25).during(10.seconds),
      constantUsersPerSec(25).during(1.minutes)
    )*/
  ).protocols(jmsConfig)
    .maxDuration(3.minutes)
    .assertions(
      global.responseTime.max.lt(10000), // SSL handshake takes longer
      global.successfulRequests.percent.gt(95)
    )
}