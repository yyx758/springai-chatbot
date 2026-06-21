package com.example.chatbot.agent.review;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.agent.review.git")
public class GitReviewProperties {
    private boolean enabled = true;
    private String repositoryPath = ".";
    private long commandTimeoutMs = 10000;
    private int maxDiffChars = 60000;
}
