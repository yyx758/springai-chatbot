package com.example.chatbot.mcp;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class McpToolInvocationRequest {
    private String toolName;
    private String sessionId;
    private Map<String, Object> arguments = new HashMap<>();
}
