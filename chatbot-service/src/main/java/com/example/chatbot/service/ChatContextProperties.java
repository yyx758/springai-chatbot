package com.example.chatbot.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.chatbot.context")
public class ChatContextProperties {

    private int recentWindowSize = 10;
    private int redisCacheSize = 100;
    private int recentMessageMaxChars = 2000;
    private boolean summaryEnabled = true;
    private int summaryTriggerRecords = 12;
    private int summaryRefreshEveryRecords = 6;
    private int summaryMaxChars = 2000;
    private int maxContextChars = 30000;
}
