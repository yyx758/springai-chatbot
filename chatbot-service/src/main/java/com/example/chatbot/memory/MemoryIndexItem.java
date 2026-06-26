package com.example.chatbot.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemoryIndexItem {
    private Long id;
    private String scopeType;
    private String scopeKey;
    private String memoryType;
    private String name;
    private String description;
    private String loadHint;
}
