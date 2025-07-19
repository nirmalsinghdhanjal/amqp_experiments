package com.example.db

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, Timestamp}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

/**
 * Database utility for tracking AMQPS message lifecycle
 * Handles connection management and CRUD operations for message tracking
 */
object AMQPSDatabase {
  private val logger = LoggerFactory.getLogger(this.getClass)
  
  // Database configuration from environment variables
  private val dbHost = sys.env.getOrElse("DB_HOST", "localhost")
  private val dbPort = sys.env.getOrElse("DB_PORT", "5432")
  private val dbName = sys.env.getOrElse("DB_NAME", "amqps_performance")
  private val dbUser = sys.env.getOrElse("DB_USER", "amqps_user")
  private val dbPassword = sys.env.getOrElse("DB_PASSWORD", "amqps_password")
  
  private val jdbcUrl = s"jdbc:postgresql://$dbHost:$dbPort/$dbName"
  
  // Load PostgreSQL JDBC driver
  try {
    Class.forName("org.postgresql.Driver")
    logger.info(s"PostgreSQL JDBC driver loaded successfully")
  } catch {
    case e: ClassNotFoundException =>
      logger.error("PostgreSQL JDBC driver not found. Make sure postgresql dependency is in classpath.", e)
  }
  
  /**
   * Get database connection with error handling
   */
  private def getConnection(): Try[Connection] = {
    Try {
      val conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)
      conn.setAutoCommit(true)
      logger.debug(s"Database connection established to $jdbcUrl")
      conn
    }
  }
  
  /**
   * Test database connectivity
   */
  def testConnection(): Boolean = {
    getConnection() match {
      case Success(conn) =>
        try {
          val stmt = conn.createStatement()
          val rs = stmt.executeQuery("SELECT 1")
          val result = rs.next() && rs.getInt(1) == 1
          rs.close()
          stmt.close()
          conn.close()
          logger.info("Database connectivity test: SUCCESS")
          result
        } catch {
          case e: Exception =>
            logger.error("Database connectivity test failed", e)
            conn.close()
            false
        }
      case Failure(e) =>
        logger.error("Failed to establish database connection", e)
        false
    }
  }
  
  /**
   * Log message sent to queue (initial record with push time)
   */
  def logMessageSent(messageId: String, scenario: String, subScenario: String, clientType: String, pushTimeMs: Long): Unit = {
    val pushTime = Timestamp.from(Instant.ofEpochMilli(pushTimeMs))
    
    val sql = """
      INSERT INTO amqps_messages (message_id, scenario, sub_scenario, client_type, push_time, status)
      VALUES (?, ?, ?, ?, ?, 'SENT')
      ON CONFLICT (message_id) DO UPDATE SET
        scenario = EXCLUDED.scenario,
        sub_scenario = EXCLUDED.sub_scenario,
        client_type = EXCLUDED.client_type,
        push_time = EXCLUDED.push_time,
        status = 'SENT',
        updated_at = CURRENT_TIMESTAMP
    """
    
    executeUpdate(sql, List(messageId, scenario, subScenario, clientType, pushTime)) match {
      case Success(rows) =>
        logger.info(s"✓ Message sent logged: messageId=$messageId, scenario=$scenario, pushTime=$pushTime")
      case Failure(e) =>
        logger.error(s"✗ Failed to log message sent: messageId=$messageId", e)
    }
  }
  
  /**
   * Log message received from queue (update with correlation ID, receive time, and JMS timestamp)
   */
  def logMessageReceived(messageId: String, corrId: String, receiveTimeMs: Long, jmsTimestamp: Long): Unit = {
    val receiveTime = Timestamp.from(Instant.ofEpochMilli(receiveTimeMs))
    val jmsTimestampConverted = Timestamp.from(Instant.ofEpochMilli(jmsTimestamp))
    
    val sql = """
      UPDATE amqps_messages 
      SET corr_id = ?, receive_time = ?, jms_timestamp = ?, status = 'RECEIVED', updated_at = CURRENT_TIMESTAMP
      WHERE message_id = ?
    """
    
    executeUpdate(sql, List(corrId, receiveTime, jmsTimestampConverted, messageId)) match {
      case Success(rows) if rows > 0 =>
        logger.info(s"✓ Message received logged: messageId=$messageId, corrId=$corrId, receiveTime=$receiveTime, jmsTimestamp=$jmsTimestampConverted")
      case Success(0) =>
        logger.warn(s"⚠ No matching message found for update: messageId=$messageId")
      case Failure(e) =>
        logger.error(s"✗ Failed to log message received: messageId=$messageId", e)
    }
  }
  
  /**
   * Log message failure
   */
  def logMessageFailed(messageId: String, reason: String): Unit = {
    val sql = """
      UPDATE amqps_messages 
      SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
      WHERE message_id = ?
    """
    
    executeUpdate(sql, List(messageId)) match {
      case Success(rows) if rows > 0 =>
        logger.info(s"✓ Message failure logged: messageId=$messageId, reason=$reason")
      case Success(0) =>
        logger.warn(s"⚠ No matching message found for failure update: messageId=$messageId")
      case Failure(e) =>
        logger.error(s"✗ Failed to log message failure: messageId=$messageId", e)
    }
  }
  
  /**
   * Get performance statistics for a scenario
   */
  def getScenarioStats(scenario: String): List[Map[String, Any]] = {
    val sql = """
      SELECT 
        sub_scenario,
        client_type,
        COUNT(*) as total_messages,
        COUNT(receive_time) as received_messages,
        AVG(response_time_ms) as avg_response_time_ms,
        MIN(response_time_ms) as min_response_time_ms,
        MAX(response_time_ms) as max_response_time_ms
      FROM amqps_messages 
      WHERE scenario = ? AND created_at >= CURRENT_TIMESTAMP - INTERVAL '1 hour'
      GROUP BY sub_scenario, client_type
      ORDER BY sub_scenario, client_type
    """
    
    executeQuery(sql, List(scenario)) match {
      case Success(results) =>
        logger.debug(s"Retrieved ${results.length} stats records for scenario: $scenario")
        results
      case Failure(e) =>
        logger.error(s"Failed to get scenario stats: scenario=$scenario", e)
        List.empty
    }
  }
  
  /**
   * Execute SQL update statement
   */
  private def executeUpdate(sql: String, params: List[Any]): Try[Int] = {
    getConnection().flatMap { conn =>
      Try {
        val stmt = conn.prepareStatement(sql)
        try {
          setParameters(stmt, params)
          val rows = stmt.executeUpdate()
          rows
        } finally {
          stmt.close()
          conn.close()
        }
      }
    }
  }
  
  /**
   * Execute SQL query statement
   */
  private def executeQuery(sql: String, params: List[Any]): Try[List[Map[String, Any]]] = {
    getConnection().flatMap { conn =>
      Try {
        val stmt = conn.prepareStatement(sql)
        try {
          setParameters(stmt, params)
          val rs = stmt.executeQuery()
          val results = resultSetToList(rs)
          rs.close()
          results
        } finally {
          stmt.close()
          conn.close()
        }
      }
    }
  }
  
  /**
   * Set prepared statement parameters
   */
  private def setParameters(stmt: PreparedStatement, params: List[Any]): Unit = {
    params.zipWithIndex.foreach { case (param, index) =>
      param match {
        case s: String => stmt.setString(index + 1, s)
        case i: Int => stmt.setInt(index + 1, i)
        case l: Long => stmt.setLong(index + 1, l)
        case d: Double => stmt.setDouble(index + 1, d)
        case t: Timestamp => stmt.setTimestamp(index + 1, t)
        case null => stmt.setNull(index + 1, java.sql.Types.NULL)
        case other => stmt.setString(index + 1, other.toString)
      }
    }
  }
  
  /**
   * Convert ResultSet to List of Maps
   */
  private def resultSetToList(rs: ResultSet): List[Map[String, Any]] = {
    val metaData = rs.getMetaData
    val columnCount = metaData.getColumnCount
    val columnNames = (1 to columnCount).map(metaData.getColumnName)
    
    var results = List.empty[Map[String, Any]]
    while (rs.next()) {
      val row = columnNames.map { colName =>
        val value = rs.getObject(colName)
        colName -> value
      }.toMap
      results = row :: results
    }
    results.reverse
  }
}
