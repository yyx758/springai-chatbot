package com.example.chatbot.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 关键词匹配结果：包含结构化的匹配详情。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeywordMatchResult {

    private String chunkId;
    private Long documentId;
    private String title;
    private String snippet;
    private double keywordScore;
    private List<String> matchedTerms;
    private List<MatchDetail> matchedDetails;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchDetail {
        /** 匹配的词 */
        private String term;
        /** 匹配类型：QUERY_PHRASE_IN_TITLE, TITLE_PHRASE_IN_QUERY, BIGRAM_IN_TITLE,
         *  BIGRAM_IN_CONTENT, TECHNICAL_IN_TITLE, TECHNICAL_IN_CONTENT */
        private String matchType;
        /** 贡献分数 */
        private double score;
    }
}
