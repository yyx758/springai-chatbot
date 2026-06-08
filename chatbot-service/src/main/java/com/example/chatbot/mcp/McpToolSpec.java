package com.example.chatbot.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class McpToolSpec {
    private String name;
    private String description;
    private String riskLevel;
    private Map<String, String> arguments;
}
