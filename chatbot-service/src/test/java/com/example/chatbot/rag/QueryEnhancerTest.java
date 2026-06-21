package com.example.chatbot.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryEnhancerTest {

    @Test
    @DisplayName("Query enhancer appends configured synonyms without replacing original query")
    void enhancerAppendsSynonyms() {
        RagProperties properties = new RagProperties();
        properties.getQueryEnhancer().setMaxExpandedTerms(4);
        QueryEnhancer enhancer = new QueryEnhancer(properties);
        enhancer.loadSynonyms();

        QueryEnhancer.EnhancedQuery result = enhancer.enhance("自动续期为什么失效");

        assertEquals("自动续期为什么失效", result.originalQuery());
        assertTrue(result.enhancedQuery().startsWith("自动续期为什么失效"));
        assertTrue(result.enhancedQuery().contains("看门狗"));
        assertTrue(result.enhancedQuery().contains("watchdog"));
        assertTrue(result.enhancedQuery().contains("Redisson"));
        assertEquals("自动续期", result.matchedKeys().get(0));
    }

    @Test
    @DisplayName("Query enhancer can be disabled")
    void enhancerCanBeDisabled() {
        RagProperties properties = new RagProperties();
        properties.getQueryEnhancer().setEnabled(false);
        QueryEnhancer enhancer = new QueryEnhancer(properties);
        enhancer.loadSynonyms();

        QueryEnhancer.EnhancedQuery result = enhancer.enhance("混合检索怎么做");

        assertEquals("混合检索怎么做", result.enhancedQuery());
        assertTrue(result.expandedTerms().isEmpty());
    }
}
