package com.example.atomikos.config;

import com.atomikos.icatch.jta.UserTransactionImp;
import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.ibm.mq.jms.MQXAConnectionFactory;
import com.ibm.msg.client.wmq.common.CommonConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.jta.JtaTransactionManager;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
public class AtomikosConfig {

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${ibm.mq.queueManager}")
    private String queueManager;

    @Value("${ibm.mq.channel}")
    private String channel;

    @Value("${ibm.mq.connName}")
    private String connName;

    @Value("${ibm.mq.user:#{null}}")
    private String mqUser;

    @Value("${ibm.mq.password:#{null}}")
    private String mqPassword;

    @Bean(initMethod = "init", destroyMethod = "close")
    public UserTransactionManager atomikosTransactionManager() {
        UserTransactionManager userTransactionManager = new UserTransactionManager();
        userTransactionManager.setForceShutdown(false);
        return userTransactionManager;
    }

    @Bean
    public UserTransactionImp atomikosUserTransaction() throws Exception {
        UserTransactionImp userTransactionImp = new UserTransactionImp();
        userTransactionImp.setTransactionTimeout(300);
        return userTransactionImp;
    }

    @Bean
    @DependsOn({"atomikosTransactionManager", "atomikosUserTransaction"})
    public PlatformTransactionManager transactionManager() throws Exception {
        UserTransactionManager utm = atomikosTransactionManager();
        UserTransactionImp uti = atomikosUserTransaction();
        
        JtaTransactionManager jtaTransactionManager = new JtaTransactionManager();
        jtaTransactionManager.setTransactionManager(utm);
        jtaTransactionManager.setUserTransaction(uti);
        jtaTransactionManager.setAllowCustomIsolationLevels(true);
        return jtaTransactionManager;
    }

    @Bean(initMethod = "init", destroyMethod = "close")
    public DataSource dataSource() {
        AtomikosDataSourceBean dataSource = new AtomikosDataSourceBean();
        dataSource.setUniqueResourceName("oracleDataSource");
        dataSource.setXaDataSourceClassName("oracle.jdbc.xa.client.OracleXADataSource");
        
        Properties properties = new Properties();
        properties.setProperty("URL", dbUrl);
        properties.setProperty("user", dbUsername);
        properties.setProperty("password", dbPassword);
        
        dataSource.setXaProperties(properties);
        dataSource.setMinPoolSize(1);
        dataSource.setMaxPoolSize(5);
        dataSource.setTestQuery("SELECT 1 FROM DUAL");
        
        return dataSource;
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory() {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource());
        emf.setPackagesToScan("com.example.atomikos.entity");
        
        JpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        emf.setJpaVendorAdapter(vendorAdapter);
        
        Properties jpaProperties = new Properties();
        jpaProperties.setProperty("hibernate.hbm2ddl.auto", "update");
        jpaProperties.setProperty("hibernate.dialect", "org.hibernate.dialect.Oracle12cDialect");
        jpaProperties.setProperty("hibernate.transaction.jta.platform", 
                                  "com.atomikos.icatch.jta.hibernate5.AtomikosPlatform");
        jpaProperties.setProperty("javax.persistence.transactionType", "JTA");
        jpaProperties.setProperty("hibernate.current_session_context_class", "jta");
        jpaProperties.setProperty("hibernate.show_sql", "true");
        
        emf.setJpaProperties(jpaProperties);
        
        return emf;
    }

    @Bean
    public MQXAConnectionFactory mqXAConnectionFactory() throws Exception {
        MQXAConnectionFactory factory = new MQXAConnectionFactory();
        String host = connName.split("\\(")[0];
        int port = 1414;
        if (connName.contains("(")) {
            port = Integer.parseInt(connName.split("\\(")[1].replace(")", ""));
        }
        factory.setHostName(host);
        factory.setPort(port);
        factory.setQueueManager(queueManager);
        factory.setChannel(channel);
        factory.setTransportType(CommonConstants.WMQ_CM_CLIENT);
        
        if (mqUser != null && !mqUser.isEmpty()) {
            factory.setStringProperty("XMSC_USERID", mqUser);
            factory.setStringProperty("XMSC_PASSWORD", mqPassword);
        }
        
        return factory;
    }

    @Bean
    public com.atomikos.jms.AtomikosConnectionFactoryBean atomikosConnectionFactory() throws Exception {
        com.atomikos.jms.AtomikosConnectionFactoryBean bean = new com.atomikos.jms.AtomikosConnectionFactoryBean();
        bean.setUniqueResourceName("ibmMqXA");
        bean.setXaConnectionFactory(mqXAConnectionFactory());
        bean.setMinPoolSize(1);
        bean.setMaxPoolSize(5);
        return bean;
    }

    @Bean
    public JmsTemplate jmsTemplate() throws Exception {
        JmsTemplate template = new JmsTemplate();
        template.setConnectionFactory(atomikosConnectionFactory());
        template.setSessionTransacted(true);
        return template;
    }

    @Bean
    public DefaultMessageListenerContainer jmsListenerContainerFactory() throws Exception {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(atomikosConnectionFactory());
        container.setSessionTransacted(true);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        return container;
    }
}
