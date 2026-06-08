package com.example.chatbot.webtools;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.web-tools")
public class WebToolsProperties {
    private boolean enabled = false;
    private String provider = "firecrawl";
    private int maxResults = 5;
    private int fetchTimeoutMs = 15000;
    private int maxContentChars = 100000;
    private List<String> blockedHosts = new ArrayList<>(List.of("localhost", "127.0.0.1", "0.0.0.0", "169.254.169.254"));
    private Firecrawl firecrawl = new Firecrawl();

    @Data
    public static class Firecrawl {
        private String baseUrl = "https://api.firecrawl.dev";
        private String apiKey = "";
    }
}
