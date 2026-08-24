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

- Docker installed and running (see **Docker setup on Ubuntu** below)
- Maven 3.6+
- Java 17+
- Sufficient memory for containers (recommended: 8 GB+ RAM)

### Docker setup on Ubuntu

If you see `Could not find a valid Docker environment` when running the tests, the most
common causes are:

**1. Missing docker group membership (standard Docker Engine)**

```bash
sudo usermod -aG docker $USER
# Log out and log back in for the change to take effect
# Then verify:
docker ps
```

**2. Rootless Docker (socket at a non-default path)**

```bash
# Find the actual socket path
docker context inspect | grep Host

# Export the socket in your shell or add it to your IntelliJ run configuration
export DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock
```

On the first test run the test's static initialiser also probes common socket paths
and writes the detected value to `~/.testcontainers.properties` automatically, so
subsequent runs from the same user account will work without exporting `DOCKER_HOST`.

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

### Could not find a valid Docker environment

See the **Docker setup on Ubuntu** section under Prerequisites above.

### client version 1.32 is too old (minikube conflict)

If you have minikube installed it may export `DOCKER_API_VERSION=1.32` into your shell, which
causes `docker-java` to send that version to the Docker daemon. Docker daemons built after 2020
require a minimum API version of `1.40` and reject `1.32` with this error.

**`mvn test` is not affected** — the Maven Surefire plugin is configured to set
`DOCKER_API_VERSION=1.41` in the forked test JVM, overriding whatever the shell exports.

**Running tests from an IDE** (IntelliJ / Eclipse): add the environment variable to your run
configuration:
```
DOCKER_API_VERSION=1.41
```
Or unset it in your shell before launching the IDE:
```bash
unset DOCKER_API_VERSION
```

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

