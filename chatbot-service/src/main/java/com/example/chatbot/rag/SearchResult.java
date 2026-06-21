package com.example.chatbot.rag;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchResult {

    private String chunkId;
    private Long docId;
    private String title;
    private String content;
    private Double score;
    private Integer rank;
    private String source;
}
