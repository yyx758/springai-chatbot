package com.example.chatbot.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Hybrid RAG 配置参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rag.hybrid")
public class HybridRagProperties {

    /** RRF 常数 k */
    private int rrfK = 60;

    /** 向量检索召回数量 */
    private int vectorTopK = 10;

    /** 关键词检索召回数量 */
    private int keywordTopK = 10;

    /** 最终返回数量 */
    private int finalTopK = 3;

    /** 强向量相关阈值 */
    private double strongVectorThreshold = 0.55;

    /** 中等向量相关阈值 */
    private double mediumVectorThreshold = 0.35;

    /** Fallback 向量阈值 */
    private double fallbackVectorThreshold = 0.45;

    /** 关键词强命中阈值 */
    private double strongKeywordThreshold = 8.0;

    /** 同文档最大 chunk 数 */
    private int maxChunksPerDocument = 2;

    /** ruleBoost 扩展点（当前为 0） */
    private double defaultRuleBoost = 0.0;
}
