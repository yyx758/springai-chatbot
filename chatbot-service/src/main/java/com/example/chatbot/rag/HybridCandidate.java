package com.example.chatbot.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 混合检索候选结果，包含来自两个检索器的完整信息。
 */
@Data
@Builder
public class HybridCandidate {

    /** 文档内唯一标识（chunkId = documentId_chunkIndex） */
    private String chunkId;

    /** 文档 ID */
    private Long documentId;

    /** 文档标题 */
    private String title;

    /** 文档内容片段 */
    private String snippet;

    /** 在向量检索结果中的排名（1-based），未命中为 null */
    private Integer vectorRank;

    /** 原始向量相似度分数 */
    private Double vectorScore;

    /** 在关键词检索结果中的排名（1-based），未命中为 null */
    private Integer keywordRank;

    /** 原始关键词评分 */
    private Double keywordScore;

    /** 加权 RRF 融合分数 */
    private double rrfScore;

    /** 最终分数（当前 = rrfScore） */
    private double finalScore;

    /** 是否被最终过滤选中（进入 prompt） */
    private boolean selected;

    /** 关键词匹配到的高价值词（参与评分） */
    private List<String> matchedTerms;

    /** 被长词覆盖的短碎片（不参与评分） */
    private List<String> coveredTerms;
}
