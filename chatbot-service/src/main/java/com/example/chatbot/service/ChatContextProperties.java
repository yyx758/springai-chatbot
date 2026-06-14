package com.example.chatbot.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.chatbot.context")
public class ChatContextProperties {

    private int recentWindowSize = 6;
    private int redisCacheSize = 30;
    private boolean relevantHistoryEnabled = true;
    private int relevantHistoryCandidateSize = 50;
    private int relevantHistoryTopK = 3;
    private boolean summaryEnabled = true;
    private int summaryTriggerRecords = 12;
    private int summaryRefreshEveryRecords = 6;
    private int summaryMaxChars = 1200;
    private int maxContextChars = 12000;
}
