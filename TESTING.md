# Testing Guide

## Overview

This project includes integration tests using Testcontainers to verify the distributed
transaction functionality between Oracle DB and IBM MQ via Atomikos.

---

## Correct XA test setup

The application under test uses **genuine XA resources**:

```
OracleXADataSource  →  AtomikosDataSourceBean  →  JTA (Atomikos)
MQXAConnectionFactory  →  AtomikosConnectionFactoryBean  →  JTA (Atomikos)
```

`localTransactionMode` is **NOT** enabled on the Atomikos MQ connection factory.
Both Oracle and IBM MQ participate in the same global Atomikos JTA transaction.

Test/admin connections are **separate and intentionally non-XA**.
They exist only to send test input and inspect results:

| Connection | Path | Participates in XA? |
|---|---|---|
| Application Oracle | via Atomikos AtomikosDataSourceBean | Yes |
| Application IBM MQ | via Atomikos AtomikosConnectionFactoryBean | Yes |
| Test admin Oracle | direct JDBC (DriverManager) | No |
| Test admin IBM MQ | MQConnectionFactory (non-XA) | No |

---

## Toxiproxy topology

All **application** traffic is routed through Toxiproxy containers to allow
controlled network fault injection:

```
Spring Boot / Atomikos  -->  Toxiproxy (Oracle proxy)  -->  Oracle container
Spring Boot / Atomikos  -->  Toxiproxy (MQ proxy)      -->  IBM MQ container

JUnit verifier  ----direct (no Toxiproxy)---->  Oracle container
JUnit verifier  ----direct (no Toxiproxy)---->  IBM MQ container
```

This separation is critical: when the application's Oracle connection is deliberately
broken via Toxiproxy, the test can still inspect both Oracle and IBM MQ directly to
determine their real committed state.

Spring Boot is configured dynamically via `@DynamicPropertySource`:

```
spring.datasource.url  →  jdbc:oracle:thin:@<toxiproxy-host>:<oracle-proxy-port>/XEPDB1
ibm.mq.connName        →  <toxiproxy-host>(<mq-proxy-port>)
```

---

## Current fault coverage

This PR contains **only the first Oracle connectivity failure scenario**.

| Test | Coverage |
|---|---|
| `proxySanityCheck_oracleProxyRoutesTraffic` | Proves the application really routes through Toxiproxy |
| `baselineXaFlow_messageProcessedAtomically` | Normal successful XA commit |
| `oracleNetworkFailureMustNotCommitMqMessageWithoutDbRecord` | Oracle unavailable → both Oracle and MQ must be absent (no partial commit) |

Exact `PREPARE` / `COMMIT` / recovery fault injection will be added in follow-up PRs.

---

## Prerequisites

- Docker installed and running
- Maven 3.6+
- Java 17+
- Sufficient memory for containers (recommended: 8 GB+ RAM)

## Running Tests

### All Tests

```bash
mvn test
```

### Single Test

```bash
mvn test -Dtest=MessageProcessingIntegrationTest#baselineXaFlow_messageProcessedAtomically
```

## Test Containers

| Container | Image | Purpose |
|---|---|---|
| Oracle | `gvenzl/oracle-free:23-slim-faststart` | Database |
| IBM MQ | `icr.io/ibm-messaging/mq:9.3.4.0-r1` | Message broker |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.7.0` | Network fault injection |

---

## Troubleshooting

### Tests Timeout

Pull images beforehand:

```bash
docker pull gvenzl/oracle-free:23-slim-faststart
docker pull icr.io/ibm-messaging/mq:9.3.4.0-r1
docker pull ghcr.io/shopify/toxiproxy:2.7.0
```

### Out of Memory

Oracle (~2 GB) and IBM MQ (~1 GB) require significant memory.
Increase Docker memory allocation or run tests individually.

---

## Follow-up (planned)

Later PRs will inject failures at specific XA phases:

- `PREPARE` phase failure
- `COMMIT` phase failure
- `ROLLBACK` failure
- Process crash/restart during 2PC
- Recovery from Atomikos transaction log

