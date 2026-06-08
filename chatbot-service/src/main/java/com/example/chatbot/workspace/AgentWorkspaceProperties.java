package com.example.chatbot.workspace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.agent.workspace")
public class AgentWorkspaceProperties {
    private int maxFiles = 300;
    private long maxFileSizeBytes = 2 * 1024 * 1024;
    private int maxPathLength = 512;
    private List<String> allowedExtensions = new ArrayList<>(List.of(
            ".md", ".txt", ".json", ".csv", ".html", ".css", ".js", ".ts", ".jsx", ".tsx",
            ".java", ".kt", ".py", ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".cs", ".php",
            ".rb", ".swift", ".sql", ".sh", ".bat", ".ps1", ".gradle", ".vue", ".scss",
            ".less", ".xml", ".yml", ".yaml", ".properties", ".toml", ".ini", ".conf",
            ".gitignore", ".gitattributes", ".dockerignore", ".editorconfig"
    ));
    private List<String> allowedFilenames = new ArrayList<>(List.of(
            "Dockerfile", "Containerfile", "Jenkinsfile", "Makefile", "mvnw", "gradlew",
            "README", "LICENSE", "NOTICE"
    ));
    private List<String> blockedPathSegments = new ArrayList<>(List.of(
            ".git", ".idea", ".vscode", "node_modules", "target", "dist", "build", ".gradle"
    ));
}
