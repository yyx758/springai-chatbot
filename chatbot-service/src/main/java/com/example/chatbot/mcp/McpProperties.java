package com.example.chatbot.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    private boolean enabled = false;
    private Server server = new Server();
    private Client client = new Client();

    @Data
    public static class Server {
        private boolean enabled = false;
        private List<String> allowedTools = new ArrayList<>(List.of(
                "knowledge.search",
                "files.info",
                "chat.history",
                "workspace.files.list",
                "workspace.files.read"
        ));
    }

    @Data
    public static class Client {
        private boolean enabled = false;
        private List<String> allowedTools = new ArrayList<>();
    }
}
