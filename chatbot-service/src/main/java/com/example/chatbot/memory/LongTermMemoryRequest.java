package com.example.chatbot.memory;

import lombok.Data;

@Data
public class LongTermMemoryRequest {
    private String scopeType;
    private String scopeKey;
    private String memoryType;
    private String name;
    private String description;
    private String content;
    private String loadHint;
    private String sourceType;
    private String status;
}
