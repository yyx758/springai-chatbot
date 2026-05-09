package com.example.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeSearchRequest {

    @NotBlank(message = "检索问题不能为空")
    @Size(max = 500, message = "检索问题长度不能超过500")
    private String query;

    private Integer topK;
}
