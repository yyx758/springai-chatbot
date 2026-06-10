package com.example.chatbot.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryIntentAnalyzerTest {

    private final QueryIntentAnalyzer analyzer = new QueryIntentAnalyzer();

    @Test
    @DisplayName("精确查询：类名、方法名")
    void exactKeyword() {
        QueryIntent intent = analyzer.analyze("ChatMemory 怎么用");
        assertEquals(QueryType.EXACT_KEYWORD, intent.getQueryType());
        assertTrue(intent.getKeywordWeight() > intent.getVectorWeight());
    }

    @Test
    @DisplayName("精确查询：异常名")
    void exactKeywordException() {
        QueryIntent intent = analyzer.analyze("NullPointerException 为什么报错");
        assertEquals(QueryType.EXACT_KEYWORD, intent.getQueryType());
    }

    @Test
    @DisplayName("精确查询：snake_case")
    void exactKeywordSnakeCase() {
        QueryIntent intent = analyzer.analyze("vector_dims 是什么");
        assertEquals(QueryType.EXACT_KEYWORD, intent.getQueryType());
    }

    @Test
    @DisplayName("命令解释：docker 命令")
    void commandExplain() {
        QueryIntent intent = analyzer.analyze("docker exec 这条命令什么意思");
        assertEquals(QueryType.COMMAND_EXPLAIN, intent.getQueryType());
        assertTrue(intent.getKeywordWeight() > intent.getVectorWeight());
    }

    @Test
    @DisplayName("报错排查：包含报错")
    void errorDebug() {
        QueryIntent intent = analyzer.analyze("Redis 连接失败怎么排查");
        assertEquals(QueryType.ERROR_DEBUG, intent.getQueryType());
        assertTrue(intent.getKeywordWeight() > intent.getVectorWeight());
    }

    @Test
    @DisplayName("原理解释：是什么")
    void semanticExplain() {
        QueryIntent intent = analyzer.analyze("RAG 是什么");
        assertEquals(QueryType.SEMANTIC_EXPLAIN, intent.getQueryType());
        assertTrue(intent.getVectorWeight() > intent.getKeywordWeight());
    }

    @Test
    @DisplayName("实现方案：怎么实现")
    void solutionDesign() {
        QueryIntent intent = analyzer.analyze("怎么实现文档检索");
        assertEquals(QueryType.SOLUTION_DESIGN, intent.getQueryType());
        assertTrue(intent.getVectorWeight() > intent.getKeywordWeight());
    }

    @Test
    @DisplayName("对比区别：区别")
    void comparison() {
        QueryIntent intent = analyzer.analyze("SSE 和 WebSocket 有什么区别");
        assertEquals(QueryType.COMPARISON, intent.getQueryType());
        assertTrue(intent.getVectorWeight() > intent.getKeywordWeight());
    }

    @Test
    @DisplayName("默认类型")
    void defaultType() {
        QueryIntent intent = analyzer.analyze("你好");
        assertEquals(QueryType.DEFAULT, intent.getQueryType());
    }

    @Test
    @DisplayName("null 输入返回默认")
    void nullInput() {
        QueryIntent intent = analyzer.analyze(null);
        assertEquals(QueryType.DEFAULT, intent.getQueryType());
    }
}
