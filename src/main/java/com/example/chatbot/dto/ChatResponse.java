package com.example.chatbot.dto;

/**
 * 聊天响应DTO
 * 
 * @author yyvb
 */
public class ChatResponse {
    
    private String message;
    private String sessionId;
    private Long responseTime;
    private boolean success;
    private String error;
    
    public ChatResponse() {}
    
    public ChatResponse(String message, String sessionId, Long responseTime) {
        this.message = message;
        this.sessionId = sessionId;
        this.responseTime = responseTime;
        this.success = true;
    }
    
    public static ChatResponse error(String error, String sessionId) {
        ChatResponse response = new ChatResponse();
        response.setError(error);
        response.setSessionId(sessionId);
        response.setSuccess(false);
        return response;
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
    
    public Long getResponseTime() {
        return responseTime;
    }
    
    public void setResponseTime(Long responseTime) {
        this.responseTime = responseTime;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
} 