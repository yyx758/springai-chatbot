package com.example.chatbot.workspace;

import lombok.Data;

@Data
public class WorkspaceFileCreateRequest {
    private String relativePath;
    private String content;
    private String contentType;
    private Boolean overwrite;
}
