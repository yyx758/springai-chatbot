package com.example.chatbot.service;

import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.context.ContextCompressionProperties;
import com.example.chatbot.context.ContextCompressionService;
import com.example.chatbot.context.ContextTokenEstimator;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.mapper.ChatSessionSummaryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatContextServiceBenchmarkTest {

    private static final int RUNS = 50;
    private final ContextTokenEstimator tokenEstimator = new ContextTokenEstimator();

    @Test
    @DisplayName("Print deterministic context construction benchmark")
    void printContextConstructionBenchmark() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, Object> listOperations = mock(ListOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, Object> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(listOperations.range("chat:history:7_benchmark", -10, -1))
                .thenReturn(new ArrayList<>(sampleHistory().subList(90, 100)));
        when(valueOperations.get("chat:summary:7_benchmark")).thenReturn(sampleSummary());

        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiChatModel> openAiProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OllamaChatModel> ollamaProvider = mock(ObjectProvider.class);

        ChatContextProperties properties = new ChatContextProperties();
        properties.setRecentWindowSize(10);
        properties.setRedisCacheSize(100);
        properties.setSummaryEnabled(true);
        properties.setSummaryMaxChars(2000);
        properties.setMaxContextChars(30000);

        ChatContextService service = new ChatContextService(
                mock(ChatRecordMapper.class),
                mock(ChatSessionSummaryMapper.class),
                redisTemplate,
                new ObjectMapper(),
                mock(RagService.class),
                properties,
                new ContextCompressionService(new ContextTokenEstimator(), new ContextCompressionProperties()),
                openAiProvider,
                ollamaProvider
        );

        List<Message> messages = List.of();
        long startNs = System.nanoTime();
        for (int i = 0; i < RUNS; i++) {
            messages = service.buildConversationMessages(
                    "7_benchmark",
                    "请继续分析 Redis 缓存、Kafka outbox、上下文 token 成本和代码审查 Agent 的响应耗时。",
                    "你是 AI Studio 的代码审查专家 Agent。遵守安全边界，只基于可用上下文回答。"
            );
        }
        long elapsedNs = System.nanoTime() - startNs;

        assertFalse(messages.isEmpty());
        int chars = totalChars(messages);
        int estimatedTokens = estimateTokens(messages);
        int stablePrefixChars = messages.get(0).getText() == null ? 0 : messages.get(0).getText().length();
        double avgMs = elapsedNs / 1_000_000.0 / RUNS;

        System.out.printf(
                "%n[ContextBenchmark] runs=%d messages=%d chars=%d estimatedTokens=%d stablePrefixChars=%d stablePrefixRatio=%.2f%% avgBuildMs=%.3f%n",
                RUNS,
                messages.size(),
                chars,
                estimatedTokens,
                stablePrefixChars,
                chars == 0 ? 0.0 : stablePrefixChars * 100.0 / chars,
                avgMs
        );
    }

    private List<ChatRecord> sampleHistory() {
        List<ChatRecord> records = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 6, 25, 10, 0);
        for (int i = 1; i <= 100; i++) {
            String topic = i % 4 == 0 ? "Redis cache and context token budget"
                    : i % 4 == 1 ? "Kafka outbox and DLT retry"
                    : i % 4 == 2 ? "code review Agent pending action safety"
                    : "Gateway relative API path and workspace sync";
            records.add(ChatRecord.builder()
                    .id((long) i)
                    .sessionId("7_benchmark")
                    .userMessage("第 " + i + " 轮问题：请说明 " + topic
                            + " 的设计取舍，要求避免越权、避免直接写真实 Git，并给出验证方式。")
                    .botResponse("第 " + i + " 轮回答：围绕 " + topic
                            + "，应保持用户隔离、只读预览、Pending Action 二次确认、结构化日志和可重复测试。")
                    .createdTime(base.plusMinutes(i))
                    .build());
        }
        return records;
    }

    private String sampleSummary() {
        return "用户正在把 AI Studio 转型为代码审查专家 Agent。核心要求包括："
                + "Gateway 作为生产唯一入口；workspace 文件修改必须走 Pending Action；"
                + "GitReviewService 只能只读；上下文模块要控制 token 成本、保留长期约束并兼顾响应耗时。";
    }

    private int totalChars(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .mapToInt(text -> text == null ? 0 : text.length())
                .sum();
    }

    private int estimateTokens(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .mapToInt(this::estimateTokens)
                .sum();
    }

    private int estimateTokens(String text) {
        return tokenEstimator.estimate(text);
    }
}
