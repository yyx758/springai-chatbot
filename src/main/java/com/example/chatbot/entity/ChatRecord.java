package com.example.chatbot.entity;

import java.time.LocalDateTime;

/**
 * 聊天记录实体类
 * 
 * @author yyvb
 */
public class ChatRecord {
    
    private Long id;
    private String userMessage;
    private String botResponse;
    private LocalDateTime createdTime;
    private String sessionId;
    
    // 构造函数
    public ChatRecord() {
    }
    
    public ChatRecord(String userMessage, String botResponse, String sessionId) {
        this.userMessage = userMessage;
        this.botResponse = botResponse;
        this.sessionId = sessionId;
        this.createdTime = LocalDateTime.now();
    }
    
    // Getter和Setter方法
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getUserMessage() {
        return userMessage;
    }
    
    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }
    
    public String getBotResponse() {
        return botResponse;
    }
    
    public void setBotResponse(String botResponse) {
        this.botResponse = botResponse;
    }
    
    public LocalDateTime getCreatedTime() {
        return createdTime;
    }
    
    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
} 