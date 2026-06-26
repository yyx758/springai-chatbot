package com.example.chatbot.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.chatbot.memory")
public class MemoryProperties {

    private boolean enabled = true;
    private boolean indexEnabled = true;
    private boolean detailSelectionEnabled = false;
    private String selectMode = "LLM_SIDE_QUERY";
    private int maxIndexItems = 30;
    private int maxIndexChars = 6000;
    private int maxSelectedDetails = 5;
    private int maxDetailCharsPerItem = 4096;
    private int maxTotalDetailChars = 12000;
    private boolean suggestionEnabled = false;
    private boolean consolidationEnabled = false;
    private int consolidationMinItems = 10;
    private String defaultProjectKey = "springaI-chatbot";
}
