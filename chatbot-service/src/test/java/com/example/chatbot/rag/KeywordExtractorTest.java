package com.example.chatbot.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordExtractorTest {

    private final KeywordExtractor extractor = new KeywordExtractor();

    @Test
    @DisplayName("2-gram 过滤停用词和虚词")
    void bigramsFiltered() {
        List<String> bigrams = extractor.extractBigrams("什么样的健身计划是一个好的健身计划");
        // 不应包含: 什么、是一个、一个好的
        assertFalse(bigrams.contains("什么"), "应该过滤停用词");
        assertFalse(bigrams.contains("是一个"), "应该过滤停用词");
        // 应包含: 健身、计划
        assertTrue(bigrams.contains("健身"));
        assertTrue(bigrams.contains("计划"));
    }

    @Test
    @DisplayName("2-gram 过滤 badBigrams")
    void badBigramsFiltered() {
        List<String> bigrams = extractor.extractBigrams("健身计划");
        assertFalse(bigrams.contains("身计"), "应该过滤 badBigram");
        assertFalse(bigrams.contains("划健"), "应该过滤 badBigram");
    }

    @Test
    @DisplayName("短语提取：3~6 字中文短语")
    void phrasesExtracted() {
        List<String> phrases = extractor.extractPhrases("Redis缓存策略详解");
        assertTrue(phrases.contains("缓存策略"));
        assertTrue(phrases.contains("缓存策略详解"));
    }

    @Test
    @DisplayName("英文技术术语提取")
    void technicalTermsExtracted() {
        List<String> terms = extractor.extractTechnicalTerms("ChatMemory 怎么用 NullPointerException 报错");
        assertTrue(terms.contains("ChatMemory"));
        assertTrue(terms.contains("NullPointerException"));
    }

    @Test
    @DisplayName("查询短语提取：过滤问句成分")
    void queryPhrasesExtracted() {
        List<String> phrases = extractor.extractQueryPhrases("什么样的健身计划是一个好的健身计划");
        assertTrue(phrases.contains("健身计划"), "应该提取出'健身计划'");
        assertTrue(phrases.contains("健身"));
        assertTrue(phrases.contains("计划"));
        // 不应包含停用词
        assertFalse(phrases.contains("什么"));
        assertFalse(phrases.contains("一个好的"));
    }

    @Test
    @DisplayName("停用词判断")
    void stopWords() {
        assertTrue(extractor.isStopWord("什么"));
        assertTrue(extractor.isStopWord("怎么"));
        assertFalse(extractor.isStopWord("健身"));
    }

    @Test
    @DisplayName("badBigram 判断")
    void badBigramCheck() {
        assertTrue(extractor.isBadBigram("身计"));
        assertTrue(extractor.isBadBigram("的健"));
        assertFalse(extractor.isBadBigram("健身"));
    }
}
