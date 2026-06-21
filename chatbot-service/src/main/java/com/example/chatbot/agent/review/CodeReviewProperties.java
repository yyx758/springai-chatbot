package com.example.chatbot.agent.review;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.agent.review")
public class CodeReviewProperties {
    private boolean enabled = true;
    private int maxFileChars = 30000;
    private int defaultMaxIssues = 8;
}
