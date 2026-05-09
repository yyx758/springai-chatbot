package com.example.chatbot.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagReference {

    private Long documentId;
    private String title;
    private String snippet;
    private Integer score;
}
