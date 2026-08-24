package com.example.atomikos.config;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.common.CommonConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.ConnectionFactory;

/**
 * Provides separate NON-XA JMS connection factory and JmsTemplate for test
 * verification purposes.
 *
 * These beans connect directly to IBM MQ (bypassing Toxiproxy) and do NOT
 * participate in the application's Atomikos JTA transaction.
 *
 * They are used only to:
 * - send initial test messages to DEV.QUEUE.1
 * - inspect DEV.QUEUE.2 to verify committed output
 * - clear queue state between tests
 *
 * Routing:
 *   Test admin JMS  ----direct---->  IBM MQ container (no Toxiproxy)
 */
@TestConfiguration
public class AdminJmsConfig {

    @Value("${test.admin.mq.host}")
    private String adminMqHost;

    @Value("${test.admin.mq.port}")
    private int adminMqPort;

    @Value("${ibm.mq.queueManager}")
    private String queueManager;

    @Value("${ibm.mq.channel}")
    private String channel;

    @Value("${ibm.mq.user:admin}")
    private String mqUser;

    @Value("${ibm.mq.password:passw0rd}")
    private String mqPassword;

    @Bean("adminMqConnectionFactory")
    public ConnectionFactory adminMqConnectionFactory() throws Exception {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName(adminMqHost);
        factory.setPort(adminMqPort);
        factory.setQueueManager(queueManager);
        factory.setChannel(channel);
        factory.setTransportType(CommonConstants.WMQ_CM_CLIENT);
        if (mqUser != null && !mqUser.isEmpty()) {
            factory.setStringProperty("XMSC_USERID", mqUser);
            factory.setStringProperty("XMSC_PASSWORD", mqPassword);
        }
        return factory;
    }

    @Bean("adminJmsTemplate")
    public JmsTemplate adminJmsTemplate() throws Exception {
        JmsTemplate template = new JmsTemplate(adminMqConnectionFactory());
        template.setSessionTransacted(false);
        template.setReceiveTimeout(5_000);
        return template;
    }
}
