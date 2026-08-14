package com.example.atomikos.config;

import com.atomikos.jms.AtomikosConnectionFactoryBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.core.JmsTemplate;

/**
 * Test configuration that restores correct XA/transacted settings.
 *
 * The application JmsTemplate must use the Atomikos XA connection factory with
 * sessionTransacted=true so that the JMS send participates in the same Atomikos
 * JTA transaction as the Oracle write.  localTransactionMode must NOT be enabled
 * on the application factory – that would bypass XA.
 */
@TestConfiguration
public class TestJmsConfig {

    @Bean
    @Primary
    public JmsTemplate testJmsTemplate(AtomikosConnectionFactoryBean atomikosConnectionFactory) {
        // Ensure local-transaction mode is off so that the XA resource participates
        // in the global JTA transaction managed by Atomikos.
        atomikosConnectionFactory.setLocalTransactionMode(false);

        JmsTemplate template = new JmsTemplate();
        template.setConnectionFactory(atomikosConnectionFactory);
        template.setSessionTransacted(true);
        return template;
    }
}
