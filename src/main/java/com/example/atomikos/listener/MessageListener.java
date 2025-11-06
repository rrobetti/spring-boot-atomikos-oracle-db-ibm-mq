package com.example.atomikos.listener;

import com.example.atomikos.service.MessageProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;

@Component
public class MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(MessageListener.class);

    private final MessageProcessingService messageProcessingService;

    public MessageListener(MessageProcessingService messageProcessingService) {
        this.messageProcessingService = messageProcessingService;
    }

    @JmsListener(destination = "DEV.QUEUE.1", containerFactory = "jmsListenerContainerFactory")
    public void receiveMessage(Message message) {
        logger.info("Received message from queue");
        
        try {
            if (message instanceof TextMessage textMessage) {
                messageProcessingService.processMessage(textMessage);
            } else {
                logger.warn("Received non-text message: {}", message.getClass().getName());
            }
        } catch (JMSException e) {
            logger.error("Error receiving message", e);
            throw new RuntimeException("Failed to receive message", e);
        }
    }
}
