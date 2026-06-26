package com.example.chatbot.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.chatbot.context.compression")
public class ContextCompressionProperties {

    private boolean enabled = true;
    private int maxInputTokens = 24000;
    private int reserveOutputTokens = 4000;
    private int reserveSafetyBufferTokens = 2000;
    private int snipMaxMessages = 60;
    private int snipHeadMessages = 6;
    private int snipTailMessages = 40;
    private int keepRecentToolResults = 3;
    private int largeResultMaxChars = 200000;
    private int largeResultPreviewChars = 2000;
    private boolean autoCompactEnabled = true;
    private double autoCompactThresholdRatio = 0.80;
    private int maxConsecutiveAutoCompactFailures = 3;
    private boolean reactiveCompactEnabled = true;
    private int maxReactiveCompactRetries = 1;
}
