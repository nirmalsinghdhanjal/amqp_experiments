# amqp_experiments

# ActiveMQ PKCS#12 Certificate Configuration

## 1. Generate PKCS#12 Certificates (For Testing)

If you don't have certificates, create them for testing:

```bash
# Create a self-signed certificate and convert to PKCS#12
# Generate private key
openssl genrsa -out activemq-server.key 2048

# Generate certificate signing request
openssl req -new -key activemq-server.key -out activemq-server.csr \
  -subj "/C=US/ST=CA/L=San Francisco/O=Test/CN=localhost"

# Generate self-signed certificate
openssl x509 -req -in activemq-server.csr -signkey activemq-server.key \
  -out activemq-server.crt -days 365

# Convert to PKCS#12 format
openssl pkcs12 -export -in activemq-server.crt -inkey activemq-server.key \
  -out activemq-server.p12 -name "activemq-server" -password pass:changeit

# Create client certificate
openssl genrsa -out activemq-client.key 2048
openssl req -new -key activemq-client.key -out activemq-client.csr \
  -subj "/C=US/ST=CA/L=San Francisco/O=Test/CN=client"
openssl x509 -req -in activemq-client.csr -signkey activemq-client.key \
  -out activemq-client.crt -days 365
openssl pkcs12 -export -in activemq-client.crt -inkey activemq-client.key \
  -out activemq-client.p12 -name "activemq-client" -password pass:clientpass

# Create truststore with server certificate
keytool -import -file activemq-server.crt -keystore truststore.jks \
  -storepass trustpass -alias activemq-server -noprompt

# Create truststore in PKCS#12 format
keytool -importkeystore -srckeystore truststore.jks -srcstoretype JKS \
  -destkeystore truststore.p12 -deststoretype PKCS12 \
  -srcstorepass trustpass -deststorepass trustpass
```

## 2. ActiveMQ SSL Configuration

### Edit activemq.xml



        <sslContext>
        <sslContext keyStore="file:${activemq.conf}/ssl/activemq-server.p12"
                   keyStorePassword="cccc"
                   keyStoreType="PKCS12"
                   trustStore="file:${activemq.conf}/ssl/truststore.p12"
                   trustStorePassword="ccccc"
                   trustStoreType="PKCS12"/>
        </sslContext>

        <!-- AMQP connectors -->
            <transportConnector name="amqp+ssl" uri="amqp+ssl://0.0.0.0:5671?transport.transformer=jms;maximumConnections=1000&amp;wireFormat.maxFrameSize=104857600&amp;transport.enabledProtocols=TLSv1.3,TLSv1.2&amp;transport.enabledCipherSuites=TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256&amp;transport.needClientAuth=false&amp;transport.wantClientAuth=false"/>
            <transportConnector name="openwire" uri="tcp://0.0.0.0:61616?maximumConnections=1000&amp;wireFormat.maxFrameSize=104857600"/>
            <transportConnector name="amqp" uri="amqp://0.0.0.0:5672?transport.transformer=jms;maximumConnections=1000&amp;wireFormat.maxFrameSize=104857600"/>
            <transportConnector name="stomp" uri="stomp://0.0.0.0:61613?maximumConnections=1000&amp;wireFormat.maxFrameSize=104857600"/>
            <transportConnector name="mqtt" uri="mqtt://0.0.0.0:1883?maximumConnections=1000&amp;wireFormat.maxFrameSize=104857600"/>
            <transportConnector name="ws" uri="ws://0.0.0.0:61614?maximumConnections=1000&amp;wireFormat.maxFrameSize=104857600"/>
        </transportConnectors>

