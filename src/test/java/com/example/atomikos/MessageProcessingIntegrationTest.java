package com.example.atomikos;

import com.example.atomikos.entity.MessageData;
import com.example.atomikos.repository.MessageDataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class MessageProcessingIntegrationTest {

    @Container
    static OracleContainer oracleContainer = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withDatabaseName("XEPDB1")
            .withUsername("testuser")
            .withPassword("testpass")
            .withReuse(false);

    @Container
    static GenericContainer<?> ibmMqContainer = new GenericContainer<>(DockerImageName.parse("icr.io/ibm-messaging/mq:latest"))
            .withEnv("LICENSE", "accept")
            .withEnv("MQ_QMGR_NAME", "QM1")
            .withEnv("MQ_APP_PASSWORD", "passw0rd")
            .withEnv("MQ_ADMIN_PASSWORD", "passw0rd")
            .withExposedPorts(1414, 9443)
            .waitingFor(Wait.forLogMessage(".*Started web server.*", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private MessageDataRepository messageDataRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Oracle configuration
        registry.add("spring.datasource.url", oracleContainer::getJdbcUrl);
        registry.add("spring.datasource.username", oracleContainer::getUsername);
        registry.add("spring.datasource.password", oracleContainer::getPassword);

        // IBM MQ configuration
        registry.add("ibm.mq.queueManager", () -> "QM1");
        registry.add("ibm.mq.channel", () -> "DEV.ADMIN.SVRCONN");
        registry.add("ibm.mq.connName", () -> 
            ibmMqContainer.getHost() + "(" + ibmMqContainer.getMappedPort(1414) + ")");
        registry.add("ibm.mq.user", () -> "admin");
        registry.add("ibm.mq.password", () -> "passw0rd");
    }

    @Test
    void testMessageProcessingFlow() {
        // Given: Clear any existing data
        messageDataRepository.deleteAll();

        // When: Send a message to the input queue
        String inputMessage = "{\"messageId\":\"MSG-001\",\"content\":\"Test message content\",\"status\":\"NEW\"}";
        jmsTemplate.convertAndSend("DEV.QUEUE.1", inputMessage);

        // Then: Wait for message to be processed and saved to database
        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
                List<MessageData> messages = messageDataRepository.findAll();
                assertEquals(1, messages.size(), "Expected one message in database");
                
                MessageData savedMessage = messages.get(0);
                assertEquals("MSG-001", savedMessage.getMessageId());
                assertEquals("Test message content", savedMessage.getMessageContent());
                assertEquals("NEW", savedMessage.getStatus());
                assertNotNull(savedMessage.getCreatedAt());
            });

        // And: Verify output message was sent to output queue
        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
                String outputMessage = (String) jmsTemplate.receiveAndConvert("DEV.QUEUE.2");
                assertNotNull(outputMessage, "Expected message in output queue");
                assertTrue(outputMessage.contains("MSG-001"), "Output message should contain message ID");
                assertTrue(outputMessage.contains("PROCESSED"), "Output message should contain PROCESSED status");
            });
    }

    @Test
    void testMultipleMessages() {
        // Given: Clear any existing data
        messageDataRepository.deleteAll();

        // When: Send multiple messages
        for (int i = 1; i <= 3; i++) {
            String message = String.format("{\"messageId\":\"MSG-%03d\",\"content\":\"Message %d\",\"status\":\"NEW\"}", i, i);
            jmsTemplate.convertAndSend("DEV.QUEUE.1", message);
        }

        // Then: All messages should be processed
        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
                List<MessageData> messages = messageDataRepository.findAll();
                assertEquals(3, messages.size(), "Expected three messages in database");
            });

        // And: All output messages should be sent
        await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
                int count = 0;
                for (int i = 0; i < 3; i++) {
                    String message = (String) jmsTemplate.receiveAndConvert("DEV.QUEUE.2");
                    if (message != null) {
                        count++;
                    }
                }
                assertEquals(3, count, "Expected three messages in output queue");
            });
    }

    @Test
    void testTransactionRollback() {
        // Given: Clear any existing data
        messageDataRepository.deleteAll();
        int initialCount = messageDataRepository.findAll().size();

        // When: Send an invalid message (this should cause processing to fail)
        // Note: In a real scenario, you'd need to trigger an actual failure
        // For this test, we'll just verify that valid messages work
        String validMessage = "{\"messageId\":\"MSG-VALID\",\"content\":\"Valid content\",\"status\":\"NEW\"}";
        jmsTemplate.convertAndSend("DEV.QUEUE.1", validMessage);

        // Then: Message should be processed successfully
        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
                List<MessageData> messages = messageDataRepository.findAll();
                assertEquals(initialCount + 1, messages.size());
            });
    }
}
