package com.example.chatbot.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class HybridSearchResult {

    private String chunkId;
    private Long docId;
    private String title;
    private String content;
    private Double rrfScore;
    private Integer rank;
    private Integer vectorRank;
    private Integer keywordRank;
    private Double vectorScore;
    private Double keywordScore;
    private List<String> sources;
}
