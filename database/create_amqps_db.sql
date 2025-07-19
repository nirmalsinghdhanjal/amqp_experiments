-- PostgreSQL Database Schema for AMQPS Message Tracking
-- This schema tracks message lifecycle from push to receive

-- Create database (run as superuser if needed)
-- CREATE DATABASE amqps_performance;
-- CREATE USER amqps_user WITH PASSWORD 'amqps_password';
-- GRANT ALL PRIVILEGES ON DATABASE amqps_performance TO amqps_user;

-- Connect to the database and create the schema
-- \c amqps_performance;

-- Drop table if it exists (for development/testing)
DROP TABLE IF EXISTS amqps_messages CASCADE;

-- Create the main AMQPS message tracking table
CREATE TABLE amqps_messages (
    id SERIAL PRIMARY KEY,
    corr_id VARCHAR(255),                    -- Correlation ID (initially NULL, updated on receive)
    message_id VARCHAR(255) NOT NULL UNIQUE, -- Custom message ID from Gatling
    scenario VARCHAR(100) NOT NULL,          -- Test scenario name
    sub_scenario VARCHAR(100) NOT NULL,      -- Sub-scenario name
    client_type VARCHAR(50) NOT NULL,        -- Client type (e.g., 'GATLING_CLIENT')
    push_time TIMESTAMP WITH TIME ZONE NOT NULL, -- When message was pushed to queue
    receive_time TIMESTAMP WITH TIME ZONE,   -- When message was received from queue (initially NULL)
    jms_timestamp TIMESTAMP WITH TIME ZONE,  -- JMS timestamp from received message (as proper timestamp)
    response_time_ms DECIMAL(10,2),          -- Calculated response time in milliseconds
    status VARCHAR(20) DEFAULT 'SENT',       -- Message status: SENT, RECEIVED, FAILED
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for performance
CREATE INDEX idx_amqps_messages_message_id ON amqps_messages(message_id);
CREATE INDEX idx_amqps_messages_corr_id ON amqps_messages(corr_id);
CREATE INDEX idx_amqps_messages_scenario ON amqps_messages(scenario);
CREATE INDEX idx_amqps_messages_push_time ON amqps_messages(push_time);
CREATE INDEX idx_amqps_messages_status ON amqps_messages(status);
CREATE INDEX idx_amqps_messages_created_at ON amqps_messages(created_at);

-- Create a trigger to automatically update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_amqps_messages_updated_at 
    BEFORE UPDATE ON amqps_messages 
    FOR EACH ROW 
    EXECUTE FUNCTION update_updated_at_column();

-- Create a trigger to automatically calculate response time when receive_time is updated
CREATE OR REPLACE FUNCTION calculate_response_time()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.receive_time IS NOT NULL AND OLD.receive_time IS NULL THEN
        NEW.response_time_ms = EXTRACT(EPOCH FROM (NEW.receive_time - NEW.push_time)) * 1000;
        NEW.status = 'RECEIVED';
    END IF;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER calculate_response_time_trigger
    BEFORE UPDATE ON amqps_messages
    FOR EACH ROW
    EXECUTE FUNCTION calculate_response_time();



-- Grant permissions to the application user
GRANT ALL PRIVILEGES ON TABLE amqps_messages TO amqps_user;
GRANT ALL PRIVILEGES ON SEQUENCE amqps_messages_id_seq TO amqps_user;
GRANT SELECT ON amqps_performance_summary TO amqps_user;


