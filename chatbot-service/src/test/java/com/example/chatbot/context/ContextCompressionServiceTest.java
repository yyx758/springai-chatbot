package com.example.chatbot.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompressionServiceTest {

    @Test
    @DisplayName("Compaction preserves current equivalent char-budget behavior")
    void compactionPreservesEquivalentCharBudgetBehavior() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(false);
        properties.setMaxInputTokens(1000);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.SESSION_SUMMARY, "system", "summary that should be removed first"),
                segment(ContextSegmentType.RECENT_HISTORY, "user", "old user message"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 20);

        assertTrue(result.isTruncated());
        assertFalse(result.isTokenBudgetExceeded());
        assertTrue(result.isCharBudgetExceeded());
        assertEquals(2, result.getRemovedSegments());
        assertEquals(2, result.getSegments().size());
        assertEquals("system", result.getSegments().get(0).getContent());
        assertEquals("current", result.getSegments().get(1).getContent());
    }

    @Test
    @DisplayName("Token budget is applied before char budget")
    void tokenBudgetIsAppliedBeforeCharBudget() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(false);
        properties.setMaxInputTokens(6);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.SESSION_SUMMARY, "system", "alpha beta gamma delta"),
                segment(ContextSegmentType.RECENT_HISTORY, "user", "short"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1000);

        assertTrue(result.isTruncated());
        assertTrue(result.isTokenBudgetExceeded());
        assertFalse(result.isCharBudgetExceeded());
        assertTrue(result.getRemovedEstimatedTokens() > 0);
        assertEquals("system", result.getSegments().get(0).getContent());
        assertEquals("current", result.getSegments().get(result.getSegments().size() - 1).getContent());
    }

    @Test
    @DisplayName("Lower-value segment types are trimmed before summaries")
    void lowerValueSegmentTypesAreTrimmedBeforeSummaries() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(false);
        properties.setMaxInputTokens(8);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.SESSION_SUMMARY, "system", "important durable summary"),
                segment(ContextSegmentType.TOOL_RESULT, "user", "large old tool result with many many words"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1000);

        String joined = joined(result);
        assertTrue(result.isTokenBudgetExceeded());
        assertFalse(joined.contains("large old tool result"));
        assertTrue(joined.contains("important durable summary"));
    }

    @Test
    @DisplayName("Recent history is trimmed in conversation pairs")
    void recentHistoryIsTrimmedInConversationPairs() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(false);
        properties.setMaxInputTokens(10);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.RECENT_HISTORY, "user", "old user message with extra words"),
                segment(ContextSegmentType.RECENT_HISTORY, "assistant", "old assistant message with extra words"),
                segment(ContextSegmentType.RECENT_HISTORY, "user", "new user"),
                segment(ContextSegmentType.RECENT_HISTORY, "assistant", "new assistant"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1000);

        String joined = joined(result);
        assertTrue(result.isTokenBudgetExceeded());
        assertFalse(joined.contains("old user message"));
        assertFalse(joined.contains("old assistant message"));
        assertTrue(joined.contains("new user"));
        assertTrue(joined.contains("new assistant"));
    }

    @Test
    @DisplayName("Disabled compression only records metrics")
    void disabledCompressionOnlyRecordsMetrics() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setEnabled(false);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.SESSION_SUMMARY, "system", "summary"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1);

        assertFalse(result.isTruncated());
        assertEquals(0, result.getRemovedSegments());
        assertEquals(3, result.getSegments().size());
        assertTrue(result.getOriginalEstimatedTokens() > 0);
        assertEquals(result.getOriginalEstimatedTokens(), result.getFinalEstimatedTokens());
    }

    @Test
    @DisplayName("Large results are persisted as prompt placeholders")
    void largeResultsArePersistedAsPromptPlaceholders() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(false);
        properties.setLargeResultMaxChars(20);
        properties.setLargeResultPreviewChars(8);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.TOOL_RESULT, "user", "abcdefghijklmnopqrstuvwxyz"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1000);

        String joined = joined(result);
        assertTrue(result.isTruncated());
        assertEquals(1, result.getPersistedLargeResults());
        assertTrue(joined.contains("[Persisted large context result]"));
        assertTrue(joined.contains("preview:\nabcdefgh"));
        assertFalse(joined.contains("ijklmnopqrstuvwxyz"));
    }

    @Test
    @DisplayName("Old tool results are micro-compacted before budget trimming")
    void oldToolResultsAreMicroCompactedBeforeBudgetTrimming() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(false);
        properties.setKeepRecentToolResults(1);
        properties.setLargeResultMaxChars(1000);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                toolResult("readFile", "old-source", "old exact tool output"),
                toolResult("readFile", "new-source", "new exact tool output"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1000);

        String joined = joined(result);
        assertEquals(1, result.getMicroCompactedToolResults());
        assertTrue(joined.contains("[Earlier tool result compacted]"));
        assertFalse(joined.contains("old exact tool output"));
        assertTrue(joined.contains("new exact tool output"));
    }

    @Test
    @DisplayName("Long middle context is snipped while preserving head and tail")
    void longMiddleContextIsSnippedWhilePreservingHeadAndTail() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(false);
        properties.setSnipMaxMessages(6);
        properties.setSnipHeadMessages(2);
        properties.setSnipTailMessages(2);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.SESSION_SUMMARY, "system", "durable summary"),
                segment(ContextSegmentType.RECENT_HISTORY, "user", "middle one"),
                segment(ContextSegmentType.RECENT_HISTORY, "assistant", "middle two"),
                segment(ContextSegmentType.RECENT_HISTORY, "user", "middle three"),
                segment(ContextSegmentType.RECENT_HISTORY, "assistant", "tail assistant"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1000);

        String joined = joined(result);
        assertEquals(3, result.getSnippedSegments());
        assertTrue(joined.contains("[Middle context snipped]"));
        assertTrue(joined.contains("durable summary"));
        assertTrue(joined.contains("tail assistant"));
        assertTrue(joined.contains("current"));
        assertFalse(joined.contains("middle one"));
    }

    @Test
    @DisplayName("Auto compact replaces older context with a compacted summary")
    void autoCompactReplacesOlderContextWithCompactedSummary() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(true);
        properties.setAutoCompactThresholdRatio(0.2);
        properties.setMaxInputTokens(100);
        properties.setSnipMaxMessages(100);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.compact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.WEB_CONTEXT, "system", "old web context with a lot of words and details"),
                segment(ContextSegmentType.WORKSPACE_FILE, "system", "src/main/java/App.java class App details"),
                segment(ContextSegmentType.RECENT_HISTORY, "assistant", "recent assistant"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1000);

        String joined = joined(result);
        assertTrue(result.isAutoCompacted());
        assertTrue(joined.contains("[Auto compacted context]"));
        assertTrue(joined.contains("source=auto-compact") || joined.contains("Auto compacted"));
        assertTrue(joined.contains("current"));
    }

    @Test
    @DisplayName("Reactive compact keeps tail context and current input")
    void reactiveCompactKeepsTailContextAndCurrentInput() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setAutoCompactEnabled(false);
        properties.setReactiveCompactEnabled(true);
        properties.setMaxInputTokens(1000);
        ContextCompressionService service = new ContextCompressionService(new ContextTokenEstimator(), properties);

        ContextCompactionResult result = service.reactiveCompact(List.of(
                segment(ContextSegmentType.SYSTEM_FIXED, "system", "system"),
                segment(ContextSegmentType.WEB_CONTEXT, "system", "very old web context"),
                segment(ContextSegmentType.RECENT_HISTORY, "user", "old user"),
                segment(ContextSegmentType.RECENT_HISTORY, "assistant", "old assistant"),
                segment(ContextSegmentType.RECENT_HISTORY, "user", "tail user"),
                segment(ContextSegmentType.RECENT_HISTORY, "assistant", "tail assistant"),
                segment(ContextSegmentType.CURRENT_USER_INPUT, "user", "current")
        ), 1000);

        String joined = joined(result);
        assertTrue(result.isAutoCompacted());
        assertTrue(joined.contains("[Reactive compacted context]"));
        assertTrue(joined.contains("tail assistant"));
        assertTrue(joined.contains("current"));
    }

    private ContextSegment segment(ContextSegmentType type, String role, String content) {
        return ContextSegment.builder()
                .type(type)
                .role(role)
                .content(content)
                .build();
    }

    private ContextSegment toolResult(String toolName, String sourceRef, String content) {
        return ContextSegment.builder()
                .type(ContextSegmentType.TOOL_RESULT)
                .role("user")
                .toolName(toolName)
                .sourceRef(sourceRef)
                .content(content)
                .build();
    }

    private String joined(ContextCompactionResult result) {
        return result.getSegments().stream()
                .map(ContextSegment::getContent)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
