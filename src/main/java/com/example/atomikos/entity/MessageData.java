package com.example.atomikos.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "MESSAGE_DATA")
public class MessageData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "MESSAGE_ID", nullable = false, length = 255)
    private String messageId;

    @Column(name = "MESSAGE_CONTENT", nullable = false, length = 4000)
    private String messageContent;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "STATUS", length = 50)
    private String status;

    public MessageData() {
    }

    public MessageData(String messageId, String messageContent, String status) {
        this.messageId = messageId;
        this.messageContent = messageContent;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getMessageContent() {
        return messageContent;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MessageData{" +
                "id=" + id +
                ", messageId='" + messageId + '\'' +
                ", messageContent='" + messageContent + '\'' +
                ", createdAt=" + createdAt +
                ", status='" + status + '\'' +
                '}';
    }
}
