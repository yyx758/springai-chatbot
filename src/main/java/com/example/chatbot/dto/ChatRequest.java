package com.example.chatbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求DTO 
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatRequest {
    
    private String message;
    private String sessionId;
    private String model ;

} 