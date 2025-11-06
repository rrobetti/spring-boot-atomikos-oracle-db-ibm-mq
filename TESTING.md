# Testing Guide

## Overview

This project includes comprehensive integration tests using Testcontainers to verify the distributed transaction functionality between Oracle DB and IBM MQ.

## Prerequisites

- Docker installed and running
- Maven 3.6+
- Java 17+
- Sufficient memory for running Oracle and IBM MQ containers (recommended: 8GB+ RAM)

## Running Tests

### All Tests

```bash
mvn test
```

### Single Test

```bash
mvn test -Dtest=MessageProcessingIntegrationTest#testMessageProcessingFlow
```

## Test Scenarios

### 1. testMessageProcessingFlow

Tests the complete end-to-end message processing flow:

1. Sends a JSON message to `DEV.QUEUE.1`
2. Message is received and processed
3. Data is saved to Oracle database
4. Confirmation message is sent to `DEV.QUEUE.2`

**Expected Result:** Message is successfully processed, database record is created, and output message is received.

### 2. testMultipleMessages

Tests concurrent message handling:

1. Sends 3 messages to the input queue
2. All messages are processed
3. All database records are created
4. All confirmation messages are sent

**Expected Result:** All 3 messages are processed successfully.

### 3. testTransactionRollback

Tests transactional integrity:

1. Sends a valid message
2. Verifies successful processing

**Note:** In a production scenario, this test would verify that on failure, both database and MQ operations are rolled back together.

## Test Containers

The integration tests use the following containers:

### Oracle Database
- Image: `gvenzl/oracle-free:23-slim-faststart`
- Database: XEPDB1
- Username: testuser
- Password: testpass

### IBM MQ
- Image: `icr.io/ibm-messaging/mq:latest`
- Queue Manager: QM1
- Admin Password: passw0rd
- Exposed Ports: 1414 (MQ), 9443 (Web Console)

## Test Configuration

Test-specific configuration is in `src/test/resources/application.properties`:

```properties
spring.jpa.hibernate.ddl-auto=create-drop
logging.level.com.example.atomikos=DEBUG
```

The containers are started automatically and configured via `@DynamicPropertySource` in the test class.

## Troubleshooting

### Tests Timeout

If tests timeout, it may be because:
- Docker daemon is slow to pull images
- Insufficient system resources
- Oracle container taking too long to start

**Solution:** Increase timeout in test configuration or pull images beforehand:

```bash
docker pull gvenzl/oracle-free:23-slim-faststart
docker pull icr.io/ibm-messaging/mq:latest
```

### Connection Refused

If you get connection refused errors:
- Ensure Docker is running
- Check that ports 1414 and 1521 are not in use
- Verify testcontainers can access Docker socket

### Out of Memory

Oracle and IBM MQ containers require significant memory:
- Oracle: ~2GB
- IBM MQ: ~1GB

**Solution:** Increase Docker memory allocation or run tests individually.

## Manual Testing

You can also run the application manually:

1. Start Oracle database:
```bash
docker run --name oracle-db -p 1521:1521 -e ORACLE_PASSWORD=oracle gvenzl/oracle-free:23-slim-faststart
```

2. Start IBM MQ:
```bash
docker run --name ibm-mq -p 1414:1414 -p 9443:9443 \
  -e LICENSE=accept \
  -e MQ_QMGR_NAME=QM1 \
  -e MQ_APP_PASSWORD=passw0rd \
  icr.io/ibm-messaging/mq:latest
```

3. Run the application:
```bash
mvn spring-boot:run
```

4. Send test messages using IBM MQ Explorer or command line tools.

## Viewing Test Results

Test results are available in:
- `target/surefire-reports/` - Detailed test reports
- Console output - Real-time test execution logs

## Continuous Integration

For CI/CD pipelines:

1. Ensure Docker-in-Docker or Docker socket mounting is available
2. Allocate sufficient resources to the CI job
3. Consider pulling images beforehand to reduce build time
4. Use test result reports for visualization

Example GitHub Actions configuration:

```yaml
steps:
  - uses: actions/checkout@v3
  - uses: actions/setup-java@v3
    with:
      java-version: '17'
  - name: Run tests
    run: mvn test
```
