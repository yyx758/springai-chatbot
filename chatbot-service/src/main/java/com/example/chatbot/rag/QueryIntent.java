package com.example.chatbot.rag;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 查询意图：包含问题类型和对应的检索权重。
 */
@Data
@AllArgsConstructor
public class QueryIntent {

    private QueryType queryType;
    private double vectorWeight;
    private double keywordWeight;
}
