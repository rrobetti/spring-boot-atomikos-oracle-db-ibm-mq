package com.example.atomikos.config;

import com.atomikos.jms.AtomikosConnectionFactoryBean;
import com.ibm.mq.jms.MQXAConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.core.JmsTemplate;

@TestConfiguration
public class TestJmsConfig {

    /**
     * Separate Atomikos connection factory for test sends only.
     * Uses localTransactionMode=true so the test can call convertAndSend outside
     * a JTA transaction. This does NOT mutate the shared production factory, which
     * must remain in XA mode for the JmsListenerContainerFactory to perform proper
     * 2PC coordination between Oracle and IBM MQ.
     */
    @Bean(initMethod = "init", destroyMethod = "close")
    public AtomikosConnectionFactoryBean testConnectionFactory(MQXAConnectionFactory mqXAConnectionFactory) {
        AtomikosConnectionFactoryBean factory = new AtomikosConnectionFactoryBean();
        factory.setUniqueResourceName("ibmMqXATest");
        factory.setXaConnectionFactory(mqXAConnectionFactory);
        factory.setLocalTransactionMode(true);
        factory.setMinPoolSize(1);
        factory.setMaxPoolSize(2);
        return factory;
    }

    @Bean
    @Primary
    public JmsTemplate testJmsTemplate(
            @Qualifier("testConnectionFactory") AtomikosConnectionFactoryBean testConnectionFactory) {
        JmsTemplate template = new JmsTemplate(testConnectionFactory);
        template.setSessionTransacted(false);
        // Non-blocking receive: return null instead of waiting forever.
        // Required when polling with pollInSameThread() to avoid deadlock.
        template.setReceiveTimeout(2000);
        return template;
    }
}
