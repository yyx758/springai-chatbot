package com.example.chatbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatResponse {
    
    private String message;
    private String sessionId;
    private Long responseTime;
    private boolean success;
    private String error;

} 