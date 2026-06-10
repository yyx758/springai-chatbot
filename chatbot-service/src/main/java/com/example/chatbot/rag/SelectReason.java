package com.example.chatbot.rag;

/**
 * Rerank/Filter 的选中或过滤原因。
 */
public enum SelectReason {

    // ===== 保留原因 =====
    /** 向量分数 >= strongThreshold，强相关 */
    SELECTED_STRONG_VECTOR,
    /** 向量中等相关 + 关键词也命中 */
    SELECTED_VECTOR_AND_KEYWORD,
    /** 关键词强命中（高分 + 高质量词） */
    SELECTED_STRONG_KEYWORD,
    /** Fallback：全部过滤后保留最高向量分 */
    SELECTED_FALLBACK_BEST_VECTOR,
    /** 纯 RRF 排名最高，兜底保留 */
    SELECTED_TOP_RRF,

    // ===== 过滤原因 =====
    /** 内容为空或过短 */
    FILTERED_EMPTY_CONTENT,
    /** 无有效信号（向量低 + 关键词弱） */
    FILTERED_NO_VALID_SIGNAL,
    /** 向量分数太低 */
    FILTERED_LOW_VECTOR_SCORE,
    /** 关键词命中太弱（停用词/泛词） */
    FILTERED_KEYWORD_TOO_WEAK,
    /** 同文档 chunk 超过限制 */
    FILTERED_SAME_DOCUMENT_LIMIT,
    /** 超过 finalTopK 数量限制 */
    FILTERED_EXCEED_FINAL_TOP_K,
    /** 与已选 chunk 内容重复 */
    FILTERED_DUPLICATE_CHUNK
}
