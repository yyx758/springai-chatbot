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

    /** 真正参与评分的高质量词 */
    private List<String> matchedTerms;

    /** 被长词覆盖的短碎片（不参与评分，仅用于调试） */
    private List<String> coveredTerms;

    /** 被过滤的低质量词（停用词、虚词等，不参与评分） */
    private List<String> ignoredTerms;

    public enum MatchType {
        TECH_IN_TITLE,
        TECH_IN_CONTENT,
        PHRASE_IN_TITLE,
        PHRASE_IN_CONTENT,
        TITLE_PHRASE_IN_QUERY,
        BIGRAM_IN_TITLE,
        BIGRAM_IN_CONTENT
    }
}
