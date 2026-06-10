package com.example.chatbot.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagReference {

    /** 文档内唯一标识（如 "14_0" 表示文档 14 的第 0 个 chunk） */
    private String chunkId;
    private Long documentId;
    private String title;
    private String snippet;
    private Double score;
}
