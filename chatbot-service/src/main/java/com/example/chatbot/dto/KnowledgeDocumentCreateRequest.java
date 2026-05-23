package com.example.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeDocumentCreateRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题长度不能超过128")
    private String title;

    @NotBlank(message = "内容不能为空")
    @Size(max = 100000, message = "内容长度不能超过100000")
    private String content;

    private String fileKey;

    @Size(max = 256, message = "标签长度不能超过256")
    private String tags;

    private Boolean enabled;
}
