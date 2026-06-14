package com.example.chatbot.service;

import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatContextServiceTest {

    @Mock
    private ChatRecordMapper chatRecordMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private RagService ragService;
    @Mock
    private ListOperations<String, Object> listOperations;
    @Mock
    private ValueOperations<String, Object> valueOperations;

    private ChatContextProperties properties;
    private ChatContextService service;

    @BeforeEach
    void setUp() {
        properties = new ChatContextProperties();
        properties.setRecentWindowSize(2);
        properties.setRedisCacheSize(30);
        properties.setRelevantHistoryCandidateSize(10);
        properties.setRelevantHistoryTopK(1);
        properties.setSummaryEnabled(true);
        properties.setMaxContextChars(12000);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiChatModel> openAiProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OllamaChatModel> ollamaProvider = mock(ObjectProvider.class);

        service = new ChatContextService(
                chatRecordMapper,
                redisTemplate,
                new ObjectMapper(),
                ragService,
                properties,
                openAiProvider,
                ollamaProvider
        );
    }

    @Test
    @DisplayName("Redis hit builds summary, relevant history, and recent history in order")
    void redisHitBuildsLayeredContext() {
        List<ChatRecord> history = List.of(
                record(1L, "I prefer Redis cache", "Redis preference noted", 1),
                record(2L, "Discuss Kafka", "Kafka handles async persistence", 2),
                record(3L, "Recent question A", "Recent answer A", 3),
                record(4L, "Recent question B", "Recent answer B", 4)
        );
        when(listOperations.range("chat:history:7_s1", 0, -1)).thenReturn(List.copyOf(history));
        when(valueOperations.get("chat:summary:7_s1")).thenReturn("User cares about cache and async reliability.");
        when(ragService.retrieveReferences(7L, "How to optimize Redis", 3)).thenReturn(List.of());

        ConversationContext context = service.buildConversationContext(
                "7_s1", "How to optimize Redis", "7", "system", true, null, true, 3);

        String joined = joinedContent(context.messages());
        assertTrue(joined.indexOf("User cares about cache") < joined.indexOf("Relevant earlier conversation snippets"));
        assertTrue(joined.indexOf("Relevant earlier conversation snippets") < joined.indexOf("Recent question A"));
        assertTrue(joined.contains("I prefer Redis cache"));
        assertTrue(joined.contains("How to optimize Redis"));
    }

    @Test
    @DisplayName("Redis miss falls back to MySQL history")
    void redisMissFallsBackToMysql() {
        when(listOperations.range("chat:history:7_s2", 0, -1)).thenReturn(List.of());
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

        service.appendPersistedRecordToCache(record);

        verify(listOperations).rightPush("chat:history:7_s3", record);
        verify(listOperations).trim("chat:history:7_s3", -30, -1);
        verify(redisTemplate).expire("chat:history:7_s3", 2, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("Summary read failure does not break context building")
    void summaryFailureDoesNotBreakContextBuild() {
        when(listOperations.range("chat:history:7_s4", 0, -1))
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
