package com.example.chatbot.dto;

/**
 * 聊天请求DTO
 * 
 * @author yyvb
 */
public class ChatRequest {
    
    private String message;
    private String sessionId;
    private String model ;
    public ChatRequest() {}
    
    public ChatRequest(String message, String sessionId, String modle) {
        this.message = message;
        this.sessionId = sessionId;
        this.model = model;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
} 