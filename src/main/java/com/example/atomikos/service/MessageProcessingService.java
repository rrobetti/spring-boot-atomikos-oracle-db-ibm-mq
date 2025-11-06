package com.example.atomikos.service;

import com.example.atomikos.entity.MessageData;
import com.example.atomikos.repository.MessageDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.jms.JMSException;
import javax.jms.TextMessage;

@Service
public class MessageProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(MessageProcessingService.class);
    private static final String OUTPUT_QUEUE = "DEV.QUEUE.2";

    private final MessageDataRepository messageDataRepository;
    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    public MessageProcessingService(MessageDataRepository messageDataRepository, 
                                   JmsTemplate jmsTemplate,
                                   ObjectMapper objectMapper) {
        this.messageDataRepository = messageDataRepository;
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void processMessage(TextMessage message) throws JMSException {
        String messageText = message.getText();
        logger.info("Processing message: {}", messageText);

        try {
            // Parse JSON message
            JsonNode jsonNode = objectMapper.readTree(messageText);
            
            // Extract fields from JSON
            String messageId = jsonNode.has("messageId") ? jsonNode.get("messageId").asText() : "UNKNOWN";
            String content = jsonNode.has("content") ? jsonNode.get("content").asText() : messageText;
            String status = jsonNode.has("status") ? jsonNode.get("status").asText() : "RECEIVED";

            // Save to database
            MessageData messageData = new MessageData(messageId, content, status);
            messageDataRepository.save(messageData);
            logger.info("Saved message to database: {}", messageData);

            // Publish to output queue
            String outputMessage = String.format("{\"messageId\":\"%s\",\"status\":\"PROCESSED\",\"timestamp\":\"%s\"}", 
                                                messageId, 
                                                messageData.getCreatedAt());
            jmsTemplate.convertAndSend(OUTPUT_QUEUE, outputMessage);
            logger.info("Published message to output queue: {}", outputMessage);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("Failed to parse JSON message: {}", messageText, e);
            throw new RuntimeException("Invalid JSON message format", e);
        } catch (org.springframework.dao.DataAccessException e) {
            logger.error("Database error while processing message", e);
            throw new RuntimeException("Failed to save message to database", e);
        } catch (org.springframework.jms.JmsException e) {
            logger.error("JMS error while publishing message", e);
            throw new RuntimeException("Failed to publish message to output queue", e);
        } catch (Exception e) {
            logger.error("Unexpected error processing message", e);
            throw new RuntimeException("Failed to process message", e);
        }
    }
}
