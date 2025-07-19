//package com.example

import io.gatling.core.Predef._
import io.gatling.jms.Predef._
import javax.jms.ConnectionFactory
import org.apache.qpid.jms.JmsConnectionFactory
import scala.concurrent.duration._
import scala.io.Source
import com.example.db.AMQPSDatabase
import org.slf4j.LoggerFactory

class AMQPSSSLSimulation extends Simulation {
  private val logger = LoggerFactory.getLogger(this.getClass)

  // Test database connectivity on simulation start
  if (!AMQPSDatabase.testConnection()) {
    logger.error("Database connectivity failed - continuing without database logging")
  } else {
    logger.info("Database connectivity verified - message tracking enabled")
  }

  // Helper function to log message to database with error handling
  def logMessageToDB(messageId: String, corrId: String, scenario: String, subScenario: String, operation: String, timestamp: Long = System.currentTimeMillis()): Unit = {
    try {
      operation match {
        case "SEND" =>
          AMQPSDatabase.logMessageSent(messageId, scenario, subScenario, "GATLING_CLIENT", timestamp)
        case "RECEIVE" =>
          AMQPSDatabase.logMessageReceived(messageId, corrId, timestamp, timestamp) // Using timestamp as JMS timestamp for now
        case "FAILED" =>
          AMQPSDatabase.logMessageFailed(messageId, "Gatling simulation failure")
        case _ =>
          logger.warn(s"Unknown operation type: $operation")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to log to database: messageId=$messageId, operation=$operation", e)
    }
  }

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


  // SSL Send scenario with database logging
  val sslSendScenario = scenario("AMQPS SSL Send Test")
    .repeat(messageCount) {
      exec { session =>
        val xmlContent = Source.fromFile(file, "UTF-8").mkString
        val messageId = java.util.UUID.randomUUID().toString
        val corrId = s"corr-send-${messageId}"
        val threadId = Thread.currentThread().getId
        val pushTime = System.currentTimeMillis()
        val modified = xmlContent
          .replace("{MESSAGE_ID}", s"$messageId")
          .replace("{THREAD_ID}", threadId.toString)
          .replace("{TIMESTAMP}", pushTime.toString)
          .replace("{DATE}", java.time.LocalDateTime.now().toString)
        
        logger.info(s"[SEND_PREP] Generated messageId: $messageId, corrId: $corrId")
        
        session.set("messageContent", modified)
               .set("currentMessageId", messageId)
               .set("currentCorrId", corrId)
               .set("pushTime", pushTime)
               .set("scenario", "SSL_SEND")
               .set("subScenario", "SEND_ONLY")
      }
      .exec { session =>
        val messageId = session("currentMessageId").as[String]
        val corrId = session("currentCorrId").as[String]
        val pushTime = session("pushTime").as[Long]
        
        // Log message send to database
        logMessageToDB(messageId, corrId, "SSL_SEND", "SEND_ONLY", "SEND", pushTime)
        logger.info(s"[SEND_START] Message ID: $messageId | Corr ID: $corrId | Push Time: $pushTime")
        session
      }
      .exec(
        jms("SSL Send Message")
          .send
          .queue(testQueue)
          .textMessage(session => session("messageContent").as[String])
          .property("messageId", session => session("currentMessageId").as[String])
          .property("correlationId", session => session("currentCorrId").as[String])
          .property("secureTransport", "amqps")
          .property("certificateType", "PKCS12")
          .property("messageFormat", "XML")
          .property("pushTimestamp", session => session("pushTime").as[String])
          .property("scenario", "SSL_SEND")
          .property("subScenario", "SEND_ONLY")
      )
      .exec { session =>
        val messageId = session("currentMessageId").as[String]
        val corrId = session("currentCorrId").as[String]
        logger.info(s"[SEND_COMPLETE] Message ID: $messageId | Corr ID: $corrId")
        session
      }
      .pause(2.seconds)
    }

  // SSL Request-Reply scenario with database logging
  val sslRequestReplyScenario = scenario("AMQPS SSL Request-Reply Test")
    .repeat(1) { 
      val scenario = "CreateAccount"
      val subScenario = "CreateDirectAccount"
      exec { session =>
        val xmlContent = Source.fromFile(file, "UTF-8").mkString
        val messageId = java.util.UUID.randomUUID().toString
        val corrId = s"corr-req-${messageId}"
        val threadId = Thread.currentThread().getId
        val pushTime = System.currentTimeMillis()
        val modified = xmlContent
          .replace("{MESSAGE_ID}", s"$messageId")
          .replace("{THREAD_ID}", threadId.toString)
          .replace("{TIMESTAMP}", pushTime.toString)
          .replace("{DATE}", java.time.LocalDateTime.now().toString)
        
        logger.info(s"[REQUEST_PREP] Generated messageId: $messageId, corrId: $corrId")
        
        session.set("messageContent", modified)
               .set("currentMessageId", messageId)
               .set("currentCorrId", corrId)
               .set("requestStartTime", pushTime)
      }
      .exec { session =>
        val messageId = session("currentMessageId").as[String]
        val corrId = session("currentCorrId").as[String]
        val startTime = session("requestStartTime").as[Long]


        // Log request to database
        logMessageToDB(messageId, corrId, scenario, subScenario, "SEND", startTime)
        logger.info(s"[REQUEST_START] Message ID: $messageId | Corr ID: $corrId | Start Time: $startTime")
        session
      }
      .exec(
        jms("SSL Request-Reply")
          .requestReply
          .queue(testQueue)
          .replyQueue(testQueue)
          .textMessage(session => session("messageContent").as[String])
          .property("messageId", session => session("currentMessageId").as[String])
          .property("correlationId", session => session("currentCorrId").as[String])
          .property("secureTransport", "amqps")
          .property("certificateType", "PKCS12")
          .property("messageFormat", "XML")
          .property("requestTimestamp", session => session("requestStartTime").as[String])
          .check(
            // Extract the message body
            bodyString.saveAs("replyMessage"),
            // Enhanced reply processing with database logging
            simpleCheck(message => {
              val jmsMessage = message.asInstanceOf[javax.jms.Message]
              
              logger.info(s"=== REPLY MESSAGE RECEIVED ===")
              
              // Extract message details
              val replyMessageId = Option(jmsMessage.getJMSMessageID()).getOrElse("N/A")
              val replyCorrId = Option(jmsMessage.getJMSCorrelationID()).getOrElse("N/A")
              val jmsTimestamp = jmsMessage.getJMSTimestamp()
              
              logger.info(s"Reply Message ID: $replyMessageId")
              logger.info(s"Reply Correlation ID: $replyCorrId")
              logger.info(s"JMS Timestamp: $jmsTimestamp")
              
              // Extract custom messageId from properties
              val customMessageId = Option(jmsMessage.getStringProperty("messageId")).getOrElse("")
              logger.info(s"Custom Message ID extracted: $customMessageId")
              
              // Log the complete message body
              /*val fullMessageBody = jmsMessage match {
                case textMessage: javax.jms.TextMessage =>
                  val text = textMessage.getText()
                  logger.info(s"Text Message Body (${text.length} chars): ${text.take(200)}...")
                  text
                case bytesMessage: javax.jms.BytesMessage =>
                  val bodyLength = bytesMessage.getBodyLength().toInt
                  val bodyBytes = new Array[Byte](bodyLength)
                  bytesMessage.readBytes(bodyBytes)
                  val text = new String(bodyBytes, "UTF-8")
                  logger.info(s"Bytes Message Body (${bodyLength} bytes): ${text.take(200)}...")
                  text
                case _ =>
                  logger.info(s"Other message type: ${jmsMessage.getClass}")
                  jmsMessage.toString
              }*/
              
              logger.info(s"=== END REPLY MESSAGE ===")
              true
            }),
            // Process timing and update database with receive time
            simpleCheck(message => {
              val jmsMessage = message.asInstanceOf[javax.jms.Message]
              val receiveTime = System.currentTimeMillis()
              
              try {
                // Extract correlation ID and messageId for database lookup
                val replyCorrId = Option(jmsMessage.getJMSCorrelationID()).getOrElse("")
                val customMessageId = Option(jmsMessage.getStringProperty("messageId")).getOrElse("")
                
                // Use the jmsTimestamp from the message
                val jmsTimestamp = jmsMessage.getJMSTimestamp()
                
                // Extract request timestamp from message properties
                val requestTimestampStr = Option(jmsMessage.getStringProperty("requestTimestamp"))
                
                requestTimestampStr.foreach { reqTs =>
                  try {
                    val requestTime = reqTs.toLong
                    val roundTripTime = receiveTime - requestTime
                    
                    logger.info(s"[TIMING] Round-trip time: ${roundTripTime}ms | " +
                               s"Request Time: $requestTime | Receive Time: $receiveTime | JMS Timestamp: $jmsTimestamp")
                    
                    // Update database with receive time using original messageId
                    if (customMessageId.nonEmpty) {
                      AMQPSDatabase.logMessageReceived(customMessageId, replyCorrId, receiveTime, jmsTimestamp)
                      logger.info(s"[DB_UPDATE] Updated receive time for messageId: $customMessageId")
                    }
                    
                  } catch {
                    case _: NumberFormatException => 
                      logger.warn(s"Could not parse request timestamp: $reqTs")
                  }
                }
                
                // Log additional JMS properties
                logger.info(s"JMS Message ID: ${Option(jmsMessage.getJMSMessageID()).getOrElse("N/A")}")
                logger.info(s"JMS Correlation ID: ${Option(jmsMessage.getJMSCorrelationID()).getOrElse("N/A")}")
                logger.info(s"JMS Delivery Mode: ${jmsMessage.getJMSDeliveryMode()}")
                logger.info(s"JMS Priority: ${jmsMessage.getJMSPriority()}")
                
              } catch {
                case e: Exception => 
                  logger.error(s"Error processing reply timing: ${e.getMessage}", e)
                  // Log failure to database
                  val customMessageId = Option(jmsMessage.getStringProperty("messageId")).getOrElse("")
                  if (customMessageId.nonEmpty) {
                    logMessageToDB(customMessageId, "", scenario, subScenario, "FAILED")
                  }
              }
              true
            })
          )
      )
      .exec { session =>
        val messageId = session("currentMessageId").as[String]
        val corrId = session("currentCorrId").as[String]
        val startTime = session("requestStartTime").as[Long]
        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        
        logger.info(s"[REQUEST_REPLY_COMPLETE] Message ID: $messageId | Corr ID: $corrId | Total Time: ${totalTime}ms")
        session
      }
    }

  

  // Load test setup
  setUp(
    // Send messages to populate the reply queue
   /* sslSendScenario.inject(
      atOnceUsers(userCount),
      rampUsers(userCount + 1).during(20.seconds)
    )*/
    // Uncomment for request-reply testing
    
    sslRequestReplyScenario.inject(
      // Ramp up to 25 TPS over 30 seconds, then maintain 25 TPS constantly
      rampUsers(1).during(10.seconds),
      constantUsersPerSec(1).during(1.minutes)
    )
  ).protocols(jmsConfig)
    .maxDuration(3.minutes)
    .assertions(
      global.responseTime.max.lt(10000), // SSL handshake takes longer
      global.successfulRequests.percent.gt(95)
    )
}