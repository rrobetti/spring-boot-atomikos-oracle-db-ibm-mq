# Quick Start Guide

## Getting Started

This guide will help you quickly set up and run the Spring Boot Atomikos Oracle DB IBM MQ application.

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Docker (for running tests and local development)

## Building the Application

```bash
# Clone the repository
git clone https://github.com/rrobetti/spring-boot-atomikos-oracle-db-ibm-mq.git
cd spring-boot-atomikos-oracle-db-ibm-mq

# Build the application
mvn clean install
```

## Running Integration Tests

Integration tests use testcontainers and will automatically start Oracle and IBM MQ containers:

```bash
mvn test
```

**Note:** First run will take longer as Docker images need to be downloaded.

## Running the Application Locally

### Option 1: With Docker Containers

1. **Start Oracle Database:**
```bash
docker run -d --name oracle-db \
  -p 1521:1521 \
  -e ORACLE_PASSWORD=oracle \
  gvenzl/oracle-free:23-slim-faststart
```

2. **Start IBM MQ:**
```bash
docker run -d --name ibm-mq \
  -p 1414:1414 -p 9443:9443 \
  -e LICENSE=accept \
  -e MQ_QMGR_NAME=QM1 \
  -e MQ_APP_PASSWORD=passw0rd \
  -e MQ_ADMIN_PASSWORD=passw0rd \
  icr.io/ibm-messaging/mq:latest
```

3. **Wait for containers to be ready:**
```bash
# Oracle takes about 1-2 minutes
docker logs -f oracle-db

# IBM MQ takes about 30-60 seconds
docker logs -f ibm-mq
```

4. **Run the Spring Boot application:**
```bash
mvn spring-boot:run
```

### Option 2: With Existing Infrastructure

Update `src/main/resources/application.properties` with your database and MQ connection details:

```properties
# Oracle Database
spring.datasource.url=jdbc:oracle:thin:@your-host:1521:XEPDB1
spring.datasource.username=your-username
spring.datasource.password=your-password

# IBM MQ
ibm.mq.queueManager=YOUR_QM
ibm.mq.channel=YOUR_CHANNEL
ibm.mq.connName=your-host(1414)
ibm.mq.user=your-user
ibm.mq.password=your-password
```

Then run:
```bash
mvn spring-boot:run
```

## Testing the Message Flow

### Using IBM MQ Web Console

1. Open https://localhost:9443/ibmmq/console
2. Login with admin/passw0rd
3. Navigate to Queue Manager QM1
4. Put a message on DEV.QUEUE.1:

```json
{
  "messageId": "TEST-001",
  "content": "Hello from IBM MQ",
  "status": "NEW"
}
```

5. Check DEV.QUEUE.2 for the confirmation message
6. Verify database record was created

### Using Command Line

```bash
# Send message to input queue (requires IBM MQ client tools)
echo '{"messageId":"TEST-001","content":"Test message","status":"NEW"}' | \
  /opt/mqm/samp/bin/amqsput DEV.QUEUE.1 QM1

# Receive message from output queue
/opt/mqm/samp/bin/amqsget DEV.QUEUE.2 QM1
```

### Verify Database Records

```bash
# Connect to Oracle
docker exec -it oracle-db sqlplus system/oracle@XEPDB1

# Query the table
SQL> SELECT * FROM MESSAGE_DATA ORDER BY CREATED_AT DESC;
```

## Monitoring

### Application Logs

Application logs show the message processing flow:

```
INFO  c.e.a.listener.MessageListener - Received message from queue
INFO  c.e.a.service.MessageProcessingService - Processing message: {"messageId":"TEST-001",...}
INFO  c.e.a.service.MessageProcessingService - Saved message to database: MessageData{id=1,...}
INFO  c.e.a.service.MessageProcessingService - Published message to output queue: {"messageId":"TEST-001","status":"PROCESSED",...}
```

### Atomikos Transaction Logs

Transaction logs are created in the working directory:
- `tmlog*.log` - Transaction manager logs
- `*.epoch` - Transaction recovery files

## Troubleshooting

### Application won't start

**Issue:** Bean creation errors
**Solution:** Ensure Oracle and IBM MQ are running and accessible

### Messages not being processed

**Issue:** Listener not receiving messages
**Solution:** 
1. Check IBM MQ connection in logs
2. Verify DEV.QUEUE.1 exists in Queue Manager
3. Check queue permissions

### Database connection errors

**Issue:** Cannot connect to Oracle
**Solution:**
1. Ensure Oracle container is fully started (check logs)
2. Verify connection string in application.properties
3. Test connection with SQL client

### Transaction failures

**Issue:** XA transaction errors
**Solution:**
1. Check Atomikos logs for details
2. Verify both Oracle and IBM MQ support XA transactions
3. Ensure proper transaction manager configuration

## Next Steps

- Review `README.md` for architecture details
- Read `TESTING.md` for comprehensive testing guide
- Explore the code in `src/main/java/com/example/atomikos`
- Customize the application for your use case

## Clean Up

Stop and remove containers:

```bash
docker stop oracle-db ibm-mq
docker rm oracle-db ibm-mq
```

## Support

For issues or questions:
1. Check existing documentation
2. Review application logs
3. Examine Atomikos transaction logs
4. Consult Spring Boot and Atomikos documentation

## References

- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/2.7.x/reference/html/)
- [Atomikos Documentation](https://www.atomikos.com/Documentation/)
- [IBM MQ Documentation](https://www.ibm.com/docs/en/ibm-mq)
- [Oracle Documentation](https://docs.oracle.com/en/database/)
