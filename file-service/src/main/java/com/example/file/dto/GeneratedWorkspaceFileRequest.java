package com.example.file.dto;

import lombok.Data;

@Data
public class GeneratedWorkspaceFileRequest {
    private String relativePath;
    private String content;
    private String contentType;
    private String bizId;
}
