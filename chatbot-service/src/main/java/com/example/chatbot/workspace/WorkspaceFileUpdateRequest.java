package com.example.chatbot.workspace;

import lombok.Data;

@Data
public class WorkspaceFileUpdateRequest {
    private String relativePath;
    private String content;
    private Integer expectedVersion;
}
