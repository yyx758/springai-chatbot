package com.example.chatbot.service;

import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.entity.ChatSessionSummary;
import com.example.chatbot.context.ContextCompressionProperties;
import com.example.chatbot.context.ContextCompressionService;
import com.example.chatbot.context.ContextTokenEstimator;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.mapper.ChatSessionSummaryMapper;
import com.example.chatbot.memory.MemoryIndexItem;
import com.example.chatbot.memory.MemoryIndexService;
import com.example.chatbot.memory.MemoryPromptBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatContextServiceTest {

    @Mock
    private ChatRecordMapper chatRecordMapper;
    @Mock
    private ChatSessionSummaryMapper chatSessionSummaryMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private RagService ragService;
    @Mock
    private ListOperations<String, Object> listOperations;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    private ChatContextProperties properties;
    private ChatContextService service;

    @BeforeEach
    void setUp() {
        properties = new ChatContextProperties();
        properties.setRecentWindowSize(2);
        properties.setRedisCacheSize(100);
        properties.setRecentMessageMaxChars(2000);
        properties.setSummaryEnabled(true);
        properties.setMaxContextChars(12000);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiChatModel> openAiProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OllamaChatModel> ollamaProvider = mock(ObjectProvider.class);

        service = new ChatContextService(
                chatRecordMapper,
                chatSessionSummaryMapper,
                redisTemplate,
                new ObjectMapper(),
                ragService,
                properties,
                new ContextCompressionService(new ContextTokenEstimator(), new ContextCompressionProperties()),
                openAiProvider,
                ollamaProvider
        );
    }

    @Test
    @DisplayName("Redis hit builds summary and recent history in order")
    void redisHitBuildsLayeredContext() {
        List<ChatRecord> history = List.of(
                record(1L, "I prefer Redis cache", "Redis preference noted", 1),
                record(2L, "Discuss Kafka", "Kafka handles async persistence", 2),
                record(3L, "Recent question A", "Recent answer A", 3),
                record(4L, "Recent question B", "Recent answer B", 4)
        );
        when(listOperations.range("chat:history:7_s1", -2, -1)).thenReturn(List.copyOf(history.subList(2, 4)));
        when(valueOperations.get("chat:summary:7_s1")).thenReturn("User cares about cache and async reliability.");
        when(ragService.retrieveReferences(7L, "How to optimize Redis", 3)).thenReturn(List.of());

        ConversationContext context = service.buildConversationContext(
                "7_s1", "How to optimize Redis", "7", "system", true, null, true, 3);

        String joined = joinedContent(context.messages());
        assertTrue(joined.indexOf("User cares about cache") < joined.indexOf("Recent question A"));
        assertFalse(joined.contains("I prefer Redis cache"));
        assertTrue(joined.contains("How to optimize Redis"));
    }

    @Test
    @DisplayName("RAG context is placed after conversation history and before current user input")
    void ragContextIsNearCurrentUserInput() {
        List<ChatRecord> history = List.of(
                record(1L, "Recent question A", "Recent answer A", 1),
                record(2L, "Recent question B", "Recent answer B", 2)
        );
        RagReference reference = RagReference.builder()
                .documentId(1L)
                .chunkId("1_0")
                .title("Gateway policy")
                .snippet("Production traffic must go through Gateway.")
                .score(0.9)
                .build();
        when(listOperations.range("chat:history:7_s6", -2, -1)).thenReturn(List.copyOf(history));
        when(valueOperations.get("chat:summary:7_s6")).thenReturn("");
        when(ragService.retrieveReferences(7L, "How should production traffic enter?", 3)).thenReturn(List.of(reference));
        when(ragService.buildKnowledgePrompt(List.of(reference))).thenReturn("Knowledge context: Gateway only.");

        ConversationContext context = service.buildConversationContext(
                "7_s6", "How should production traffic enter?", "7", "system", true, null, true, 3);

        String joined = joinedContent(context.messages());
        assertTrue(joined.indexOf("Recent answer B") < joined.indexOf("Knowledge context: Gateway only."));
        assertTrue(joined.indexOf("Knowledge context: Gateway only.") < joined.indexOf("How should production traffic enter?"));
    }

    @Test
    @DisplayName("Recent chat messages are capped per message before global budget trimming")
    void recentMessagesAreCappedPerMessage() {
        properties.setRecentMessageMaxChars(20);
        List<ChatRecord> history = List.of(
                record(1L, "1234567890123456789012345", "abcdefghijklmnopqrstuvwxyz", 1)
        );
        when(listOperations.range("chat:history:7_s7", -2, -1)).thenReturn(List.copyOf(history));
        when(valueOperations.get("chat:summary:7_s7")).thenReturn("");

        List<Message> messages = service.buildConversationMessages("7_s7", "Continue", "system");

        String joined = joinedContent(messages);
        assertTrue(joined.contains("12345678901234567890"));
        assertFalse(joined.contains("123456789012345678901"));
        assertTrue(joined.contains("abcdefghijklmnopqrst"));
        assertFalse(joined.contains("abcdefghijklmnopqrstu"));
    }

    @Test
    @DisplayName("Redis summary miss falls back to persisted MySQL summary")
    void summaryRedisMissFallsBackToPersistedSummary() {
        List<ChatRecord> history = List.of(
                record(1L, "Recent question A", "Recent answer A", 1),
                record(2L, "Recent question B", "Recent answer B", 2)
        );
        when(listOperations.range("chat:history:7_s5", -2, -1)).thenReturn(List.copyOf(history));
        when(valueOperations.get("chat:summary:7_s5")).thenReturn(null);
        when(chatSessionSummaryMapper.selectBySessionId("7_s5")).thenReturn(ChatSessionSummary.builder()
                .sessionId("7_s5")
                .summary("Persisted long-term memory from MySQL.")
                .lastSummarizedRecordId(2L)
                .build());

        List<Message> messages = service.buildConversationMessages("7_s5", "Continue", "system");

        String joined = joinedContent(messages);
        assertTrue(joined.contains("Persisted long-term memory from MySQL."));
        verify(valueOperations).set("chat:summary:7_s5", "Persisted long-term memory from MySQL.", 2, TimeUnit.HOURS);
        verify(valueOperations).set("chat:summary:meta:7_s5", 2L, 2, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("Redis miss falls back to MySQL history")
    void redisMissFallsBackToMysql() {
        when(listOperations.range("chat:history:7_s2", -2, -1)).thenReturn(List.of());
        when(valueOperations.get("chat:summary:7_s2")).thenReturn("");
        when(chatRecordMapper.selectList(any())).thenReturn(List.of(
                record(2L, "Older question", "Older answer", 2),
                record(1L, "Oldest question", "Oldest answer", 1)
        ));

        ConversationContext context = service.buildConversationContext(
                "7_s2", "Continue", "7", "system", false, null, true, 3);

        String joined = joinedContent(context.messages());
        assertTrue(joined.contains("Older question"));
        assertTrue(joined.contains("Oldest question"));
        verify(chatRecordMapper).selectList(any());
    }

    @Test
    @DisplayName("Persisted records are appended and Redis candidate window is trimmed")
    void appendPersistedRecordTrimsRedisWindow() {
        properties.setSummaryEnabled(false);
        ChatRecord record = record(9L, "New question", "New answer", 9);
        record.setSessionId("7_s3");
        when(zSetOperations.score("chat:history:ids:7_s3", 9L)).thenReturn(null);

        service.appendPersistedRecordToCache(record);

        verify(listOperations).rightPush("chat:history:7_s3", record);
        verify(listOperations).trim("chat:history:7_s3", -100, -1);
        verify(redisTemplate).expire("chat:history:7_s3", 2, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("Summary prompt merges existing summary with incremental new records")
    void summaryPromptMergesExistingSummaryWithIncrementalRecords() {
        List<Message> messages = service.buildSummaryPrompt("User prefers DeepSeek and Gateway-only access.",
                List.of(record(10L, "New requirement: Redis pool size should be 100", "Noted.", 10)));

        String joined = joinedContent(messages);
        assertTrue(joined.contains("Existing summary"));
        assertTrue(joined.contains("User prefers DeepSeek"));
        assertTrue(joined.contains("New chat history since last summary"));
        assertTrue(joined.contains("Redis pool size should be 100"));
    }

    @Test
    @DisplayName("First summary prompt uses only new chat history when no existing summary exists")
    void firstSummaryPromptUsesNewHistoryOnly() {
        List<Message> messages = service.buildSummaryPrompt("",
                List.of(record(11L, "First summary input", "First summary answer", 11)));

        String joined = joinedContent(messages);
        assertFalse(joined.contains("Existing summary"));
        assertTrue(joined.contains("New chat history since last summary"));
        assertTrue(joined.contains("First summary input"));
    }

    @Test
    @DisplayName("Incremental summary history is selected by current session records after meta id")
    void incrementalSummaryHistoryUsesSessionRecordsAfterMetaId() {
        List<ChatRecord> incrementalHistory = List.of(
                record(105L, "鏂板闂 A", "鏂板鍥炵瓟 A", 2),
                record(109L, "鏂板闂 B", "鏂板鍥炵瓟 B", 3)
        );

        String joined = joinedContent(incrementalHistory.stream()
                .map(record -> List.<Message>of(
                        new org.springframework.ai.chat.messages.UserMessage(record.getUserMessage()),
                        new org.springframework.ai.chat.messages.AssistantMessage(record.getBotResponse())))
                .flatMap(List::stream)
                .toList());
        assertFalse(joined.contains("宸叉憳瑕佹棫闂"));
        assertTrue(joined.contains("鏂板闂 A"));
        assertTrue(joined.contains("鏂板闂 B"));
    }

    @Test
    @DisplayName("Long-term memory index is injected after system prompt and before session summary")
    void longTermMemoryIndexIsBeforeSessionSummary() {
        MemoryIndexService memoryIndexService = mock(MemoryIndexService.class);
        when(memoryIndexService.loadIndex(7L, null)).thenReturn(List.of(MemoryIndexItem.builder()
                .id(18L)
                .scopeType("PROJECT")
                .memoryType("project")
                .name("Gateway production entry")
                .description("Production traffic must go through Gateway :9000.")
                .build()));
        service.setMemoryIndexService(memoryIndexService);
        service.setMemoryPromptBuilder(new MemoryPromptBuilder());
        when(listOperations.range("chat:history:7_s9", -2, -1)).thenReturn(List.of());
        when(valueOperations.get("chat:summary:7_s9")).thenReturn("Existing session summary.");

        List<Message> messages = service.buildConversationMessages("7_s9", "Continue", "system prompt");

        String joined = joinedContent(messages);
        assertTrue(joined.indexOf("system prompt") < joined.indexOf("Long-term memory index"));
        assertTrue(joined.indexOf("Long-term memory index") < joined.indexOf("Existing session summary."));
    }

    @Test
    @DisplayName("Existing summary refresh queries MySQL incrementally instead of Redis history window")
    void existingSummaryRefreshUsesMysqlIncrementalHistory() {
        properties.setSummaryRefreshEveryRecords(2);
        when(valueOperations.get("chat:summary:7_s8")).thenReturn("Existing summary.");
        when(valueOperations.get("chat:summary:meta:7_s8")).thenReturn(100L);
        when(chatRecordMapper.selectList(any())).thenReturn(List.of(
                record(105L, "New question A", "New answer A", 2),
                record(109L, "New question B", "New answer B", 3)
        ));

        service.refreshSummaryIfNeeded("7_s8");

        verify(chatRecordMapper).selectList(any());
        verify(listOperations, never()).range("chat:history:7_s8", 0, -1);
    }

    @Test
    @DisplayName("Summary read failure does not break context building")
    void summaryFailureDoesNotBreakContextBuild() {
        when(listOperations.range("chat:history:7_s4", -2, -1))
                .thenReturn(List.of(record(1L, "Question", "Answer", 1)));
        doThrow(new RuntimeException("Redis timeout"))
                .when(valueOperations).get("chat:summary:7_s4");

        assertDoesNotThrow(() -> service.buildConversationMessages("7_s4", "Continue", "system"));
    }

    private ChatRecord record(Long id, String userMessage, String botResponse, int minutes) {
        return ChatRecord.builder()
                .id(id)
                .sessionId("7_s1")
                .userMessage(userMessage)
                .botResponse(botResponse)
                .createdTime(LocalDateTime.of(2026, 6, 13, 10, 0).plusMinutes(minutes))
                .build();
    }

    private String joinedContent(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
