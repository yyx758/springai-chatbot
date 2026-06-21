package com.example.chatbot.agent.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.agent.runtime")
public class AgentRuntimeProperties {
    private int maxIterations = 6;
}
