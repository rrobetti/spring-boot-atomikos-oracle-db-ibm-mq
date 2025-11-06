package com.example.atomikos.config;

import com.atomikos.jms.AtomikosConnectionFactoryBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.core.JmsTemplate;

@TestConfiguration
public class TestJmsConfig {

    @Bean
    @Primary
    public JmsTemplate testJmsTemplate(AtomikosConnectionFactoryBean atomikosConnectionFactory) {
        JmsTemplate template = new JmsTemplate();
        template.setConnectionFactory(atomikosConnectionFactory);
        template.setSessionTransacted(false); // Disable transaction requirement for test
        atomikosConnectionFactory.setLocalTransactionMode(true); // Enable local transaction mode for testing
        return template;
    }
}
