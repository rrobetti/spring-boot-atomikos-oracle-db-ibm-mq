package com.example.atomikos;

import com.example.atomikos.config.AdminJmsConfig;
import com.example.atomikos.config.TestJmsConfig;
import com.example.atomikos.repository.MessageDataRepository;
import com.example.atomikos.support.ToxiproxyTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;

import javax.jms.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for XA transactions between Oracle and IBM MQ via Atomikos.
 *
 * <h2>Transaction topology</h2>
 * <pre>
 *   Spring Boot / Atomikos  -->  Toxiproxy (Oracle)  -->  Oracle container
 *   Spring Boot / Atomikos  -->  Toxiproxy (IBM MQ)  -->  IBM MQ container
 *
 *   JUnit verifier  ----------direct (no Toxiproxy)-------->  Oracle container
 *   JUnit verifier  ----------direct (no Toxiproxy)-------->  IBM MQ container
 * </pre>
 *
 * Routing application traffic through Toxiproxy allows fault injection while
 * keeping the verifier connections always available.
 *
 * <h2>XA correctness</h2>
 * The application uses genuine XA resources:
 * <ul>
 *   <li>OracleXADataSource wrapped by AtomikosDataSourceBean</li>
 *   <li>MQXAConnectionFactory wrapped by AtomikosConnectionFactoryBean</li>
 * </ul>
 * localTransactionMode is NOT enabled – both resources participate in the global
 * JTA transaction managed by Atomikos.
 */
@SpringBootTest
@Testcontainers
@Import({TestJmsConfig.class, AdminJmsConfig.class})
class MessageProcessingIntegrationTest {

    // ------------------------------------------------------------------
    // Docker socket auto-detection for rootless / non-standard Ubuntu installs.
    //
    // Testcontainers always tries TestcontainersHostPropertyClientProviderStrategy
    // FIRST. That strategy reads "tc.host" from ~/.testcontainers.properties.
    // Setting it here (before any @Container field is initialised) makes
    // Testcontainers use the detected socket even when DOCKER_HOST is not set
    // in the environment (common for rootless Docker on Ubuntu).
    // ------------------------------------------------------------------
    static {
        // Only auto-detect when neither the environment variable nor the user
        // property has already been configured.
        if (System.getenv("DOCKER_HOST") == null
                && TestcontainersConfiguration.getInstance().getUserProperty("tc.host", null) == null) {
            String uid = "1000"; // safe default
            try {
                uid = Files.readString(Paths.get("/proc/self/status"))
                        .lines()
                        .filter(l -> l.startsWith("Uid:"))
                        .findFirst()
                        .map(l -> l.split("\\s+")[1])
                        .orElse(uid);
            } catch (Exception ignored) { /* non-Linux: keep default */ }

            List<String> candidates = List.of(
                    "/var/run/docker.sock",
                    "/run/user/docker.sock",
                    System.getProperty("user.home") + "/.docker/run/docker.sock",
                    System.getProperty("user.home") + "/.docker/desktop/docker.sock",
                    "/run/user/" + uid + "/docker.sock"
            );

            for (String path : candidates) {
                if (Files.exists(Paths.get(path))) {
                    // tc.host is the officially supported property for
                    // TestcontainersHostPropertyClientProviderStrategy.
                    // updateUserConfig updates in-memory state AND persists
                    // the value to ~/.testcontainers.properties so subsequent
                    // runs also benefit from the detection.
                    TestcontainersConfiguration.getInstance()
                            .updateUserConfig("tc.host", "unix://" + path);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Shared Docker network so Toxiproxy can reach Oracle and MQ by hostname
    // ------------------------------------------------------------------
    static final Network NETWORK = Network.newNetwork();

    @Container
    static final OracleContainer oracleContainer =
            new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("oracle")
                    .withDatabaseName("XEPDB1")
                    .withUsername("testuser")
                    .withPassword("testpass")
                    .withStartupTimeout(Duration.ofMinutes(5))
                    .withReuse(false);

    @Container
    static final GenericContainer<?> ibmMqContainer =
            new GenericContainer<>(DockerImageName.parse("icr.io/ibm-messaging/mq:9.3.4.0-r1"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("ibmmq")
                    .withEnv("LICENSE", "accept")
                    .withEnv("MQ_QMGR_NAME", "QM1")
                    .withEnv("MQ_APP_PASSWORD", "passw0rd")
                    .withEnv("MQ_ADMIN_PASSWORD", "passw0rd")
                    .withExposedPorts(1414, 9443)
                    .waitingFor(Wait.forLogMessage(".*Started web server.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static final ToxiproxyContainer toxiproxyContainer =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.7.0"))
                    .withNetwork(NETWORK)
                    .withExposedPorts(8474, 8666, 8667); // 8474=control, 8666=Oracle proxy, 8667=MQ proxy

    static ToxiproxyTestSupport toxiproxy;

    // ------------------------------------------------------------------
    // Spring-injected beans
    // ------------------------------------------------------------------

    /** Admin (non-XA) JmsTemplate – bypasses Toxiproxy, connects directly to MQ. */
    @Autowired
    @Qualifier("adminJmsTemplate")
    private JmsTemplate adminJmsTemplate;

    /** Admin (non-XA) MQ connection factory – for QueueBrowser. */
    @Autowired
    @Qualifier("adminMqConnectionFactory")
    private ConnectionFactory adminMqConnectionFactory;

    @Autowired
    private MessageDataRepository messageDataRepository;

    // ------------------------------------------------------------------
    // Container lifecycle
    // ------------------------------------------------------------------

    @BeforeAll
    static void initToxiproxy() throws IOException {
        toxiproxy = new ToxiproxyTestSupport(toxiproxyContainer);
        // Oracle internal address within the shared Docker network
        toxiproxy.init(
                "oracle", oracleContainer.getExposedPorts().get(0),
                "ibmmq", 1414);
    }

    @AfterEach
    void resetProxies() throws IOException {
        toxiproxy.resetAll();
    }

    // ------------------------------------------------------------------
    // Dynamic Spring properties
    // ------------------------------------------------------------------

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Application Oracle path goes through Toxiproxy
        registry.add("spring.datasource.url", () ->
                "jdbc:oracle:thin:@" + toxiproxyContainer.getHost() +
                ":" + toxiproxyContainer.getMappedPort(8666) +
                "/XEPDB1");
        registry.add("spring.datasource.username", oracleContainer::getUsername);
        registry.add("spring.datasource.password", oracleContainer::getPassword);

        // Application IBM MQ path goes through Toxiproxy
        registry.add("ibm.mq.queueManager", () -> "QM1");
        registry.add("ibm.mq.channel", () -> "DEV.ADMIN.SVRCONN");
        registry.add("ibm.mq.connName", () ->
                toxiproxyContainer.getHost() + "(" + toxiproxyContainer.getMappedPort(8667) + ")");
        registry.add("ibm.mq.user", () -> "admin");
        registry.add("ibm.mq.password", () -> "passw0rd");

        // Admin/verifier connections bypass Toxiproxy (directly to the containers)
        registry.add("test.admin.mq.host", ibmMqContainer::getHost);
        registry.add("test.admin.mq.port", () -> ibmMqContainer.getMappedPort(1414));
    }

    // ------------------------------------------------------------------
    // Toxiproxy sanity check – proves the application really uses the proxy
    // ------------------------------------------------------------------

    @Test
    void proxySanityCheck_oracleProxyRoutesTraffic() throws Exception {
        // With Oracle proxy enabled the application datasource should be reachable
        toxiproxy.enableOracleProxy();
        assertDoesNotThrow(() -> messageDataRepository.count(),
                "Oracle should be reachable through Toxiproxy");

        // When Oracle proxy is disabled the application datasource should fail
        toxiproxy.disableOracleProxy();
        assertThrows(Exception.class, () -> messageDataRepository.count(),
                "Oracle should NOT be reachable when Toxiproxy is disabled");
    }

    // ------------------------------------------------------------------
    // Baseline successful XA flow
    // ------------------------------------------------------------------

    @Test
    void baselineXaFlow_messageProcessedAtomically() throws Exception {
        String messageId = "BASELINE-" + UUID.randomUUID();
        clearState(messageId);

        // Send input via the admin (non-XA) connection so it is not part of
        // any application transaction.
        adminJmsTemplate.convertAndSend("DEV.QUEUE.1", inputJson(messageId));

        // Wait for the listener to process the message
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() ->
                        assertTrue(oracleContains(messageId),
                                "Oracle row should be present for messageId=" + messageId));

        assertTrue(outputQueueContains(messageId),
                "DEV.QUEUE.2 should contain the output for messageId=" + messageId);
    }

    // ------------------------------------------------------------------
    // Oracle network-failure fault test
    // ------------------------------------------------------------------

    /**
     * Scenario: Oracle connectivity through Toxiproxy is deliberately broken.
     * <p>
     * The XA transaction cannot commit because Oracle is unavailable.
     * The invariant is:
     * <pre>
     *   Oracle row: absent
     *   DEV.QUEUE.2: absent
     * </pre>
     * The forbidden result is:
     * <pre>
     *   Oracle row: absent
     *   DEV.QUEUE.2: PRESENT  ← XA atomicity violation
     * </pre>
     * After Oracle connectivity is restored the input message from DEV.QUEUE.1
     * may be redelivered and eventually succeed, which is acceptable behaviour.
     * What must NEVER happen is a committed MQ output without an Oracle row.
     */
    @Test
    void oracleNetworkFailureMustNotCommitMqMessageWithoutDbRecord() throws Exception {
        String messageId = "FAULT-ORACLE-" + UUID.randomUUID();
        clearState(messageId);

        try {
            // Break Oracle access through the proxy
            toxiproxy.disableOracleProxy();

            // Submit the message for processing; the listener will receive it and
            // attempt the XA transaction which will fail due to Oracle being unavailable.
            adminJmsTemplate.convertAndSend("DEV.QUEUE.1", inputJson(messageId));

            // Allow time for the listener to attempt processing and fail
            await()
                    .atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofSeconds(2))
                    .untilAsserted(() ->
                            assertFalse(oracleContains(messageId),
                                    "Oracle should not contain the record while the proxy is disabled"));

            // Critical assertion: MQ output must NOT be committed either
            assertFalse(outputQueueContains(messageId),
                    "XA ATOMICITY VIOLATION: IBM MQ output message was committed " +
                    "while the Oracle record is absent for messageId=" + messageId);

        } finally {
            // Always restore Toxiproxy so subsequent tests start healthy
            toxiproxy.enableOracleProxy();
        }

        // After restoration the input may be redelivered and succeed.
        // This section documents (but does not mandate) that eventual recovery is fine.
        // The key invariant above has already been asserted.
    }

    // ------------------------------------------------------------------
    // Verification helpers
    // ------------------------------------------------------------------

    /** Returns true if Oracle contains a row for the given messageId. */
    boolean oracleContains(String messageId) {
        String url = "jdbc:oracle:thin:@" + oracleContainer.getHost() +
                     ":" + oracleContainer.getMappedPort(1521) + "/XEPDB1";
        try (Connection conn = DriverManager.getConnection(
                     url, oracleContainer.getUsername(), oracleContainer.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM MESSAGE_DATA WHERE MESSAGE_ID = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if DEV.QUEUE.2 contains an output message referencing messageId.
     * Uses JMS QueueBrowser (non-destructive).
     */
    boolean outputQueueContains(String messageId) throws JMSException {
        try (javax.jms.Connection conn = adminMqConnectionFactory.createConnection("admin", "passw0rd");
             Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            conn.start();
            Queue queue = session.createQueue("DEV.QUEUE.2");
            try (QueueBrowser browser = session.createBrowser(queue)) {
                java.util.Enumeration<?> msgs = browser.getEnumeration();
                while (msgs.hasMoreElements()) {
                    Object msg = msgs.nextElement();
                    if (msg instanceof TextMessage tm && tm.getText().contains(messageId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // State management helpers
    // ------------------------------------------------------------------

    private void clearState(String messageId) throws Exception {
        // Clear Oracle rows for this messageId via direct admin JDBC
        String url = "jdbc:oracle:thin:@" + oracleContainer.getHost() +
                     ":" + oracleContainer.getMappedPort(1521) + "/XEPDB1";
        try (Connection conn = DriverManager.getConnection(
                     url, oracleContainer.getUsername(), oracleContainer.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM MESSAGE_DATA WHERE MESSAGE_ID = ?")) {
            ps.setString(1, messageId);
            ps.executeUpdate();
            conn.commit();
        } catch (Exception ignored) {
            // Table may not yet exist on first run; createDrop will handle it.
        }
        // DEV.QUEUE.2 browsing is non-destructive; residual messages with other
        // IDs do not affect per-messageId assertions.
    }

    private static String inputJson(String messageId) {
        return String.format(
                "{\"messageId\":\"%s\",\"content\":\"Test content for %s\",\"status\":\"NEW\"}",
                messageId, messageId);
    }
}

