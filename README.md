# spring-boot-atomikos-oracle-db-ibm-mq
Spring Boot application configured with Atomikos for distributed transactions between Oracle DB and IBM MQ

## Overview

This application demonstrates a distributed transaction scenario where:
1. Messages are received from an IBM MQ input queue (`DEV.QUEUE.1`)
2. Message data is saved to an Oracle database table
3. A confirmation message is sent to an IBM MQ output queue (`DEV.QUEUE.2`)

All operations are wrapped in a distributed transaction managed by Atomikos, ensuring ACID properties across both the database and message queue.

## Architecture

- **Spring Boot 2.7.18**: Application framework
- **Atomikos 6.0.0**: JTA/XA transaction manager for distributed transactions
- **Oracle Database**: Persistence layer
- **IBM MQ**: Message queuing system
- **Hibernate 5.6**: ORM for database operations
- **Testcontainers**: Integration testing with Oracle and IBM MQ containers

## Database Schema

The application uses a single table `MESSAGE_DATA`:

| Column | Type | Description |
|--------|------|-------------|
| ID | NUMBER | Primary key (auto-increment) |
| MESSAGE_ID | VARCHAR2(255) | Unique message identifier |
| MESSAGE_CONTENT | VARCHAR2(4000) | Message payload |
| CREATED_AT | TIMESTAMP | Record creation timestamp |
| STATUS | VARCHAR2(50) | Message status |

## Message Flow

1. **Input**: JSON message on `DEV.QUEUE.1`
   ```json
   {
     "messageId": "MSG-001",
     "content": "Test message content",
     "status": "NEW"
   }
   ```

2. **Processing**: 
   - Message is parsed and mapped to `MessageData` entity
   - Entity is persisted to Oracle database
   - Confirmation message is sent to `DEV.QUEUE.2`

3. **Output**: JSON confirmation on `DEV.QUEUE.2`
   ```json
   {
     "messageId": "MSG-001",
     "status": "PROCESSED",
     "timestamp": "2025-11-06T09:00:00"
   }
   ```

## Configuration

### Application Properties

```properties
# Datasource
spring.datasource.url=jdbc:oracle:thin:@localhost:1521:XEPDB1
spring.datasource.username=system
spring.datasource.password=oracle

# IBM MQ
ibm.mq.queueManager=QM1
ibm.mq.channel=DEV.ADMIN.SVRCONN
ibm.mq.connName=localhost(1414)
ibm.mq.user=admin
ibm.mq.password=passw0rd
```

## Building the Application

```bash
mvn clean install
```

## Running Tests

The application includes comprehensive integration tests using Testcontainers:

```bash
mvn test
```

### Test Scenarios

1. **testMessageProcessingFlow**: Validates end-to-end message processing
2. **testMultipleMessages**: Tests concurrent message handling
3. **testTransactionRollback**: Verifies transactional integrity

## Key Components

### MessageListener
Listens to `DEV.QUEUE.1` and delegates message processing to `MessageProcessingService`.

### MessageProcessingService
Orchestrates the transaction:
- Parses JSON message
- Saves to database
- Publishes confirmation to output queue

### AtomikosConfig
Configures distributed transactions:
- Oracle XA DataSource
- IBM MQ XA Connection Factory
- JTA Transaction Manager

## Distributed Transaction Management

Atomikos ensures that both database and MQ operations succeed or fail together:

- If database save fails, MQ message is rolled back
- If MQ send fails, database changes are rolled back
- Provides exactly-once processing semantics

## License

This project is licensed under the MIT License.

