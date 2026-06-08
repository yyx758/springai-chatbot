package com.example.chatbot.workspace;

import lombok.Data;

@Data
public class WorkspaceFileSaveToKnowledgeRequest {
    private String relativePath;
    private String title;
    private String tags;
    private Boolean enabled;
}
