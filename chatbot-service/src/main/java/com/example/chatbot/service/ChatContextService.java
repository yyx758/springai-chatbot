package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatContextService {

    private static final String HISTORY_KEY_PREFIX = "chat:history:";
    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final String SUMMARY_META_KEY_PREFIX = "chat:summary:meta:";
    private static final String SESSION_INDEX_PREFIX = "chat:sessions:";
    private static final long CACHE_TTL_HOURS = 2;

    private final ChatRecordMapper chatRecordMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RagService ragService;
    private final ChatContextProperties properties;
    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    public ChatContextService(
            ChatRecordMapper chatRecordMapper,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            RagService ragService,
            ChatContextProperties properties,
            ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider
    ) {
        this.chatRecordMapper = chatRecordMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.properties = properties;
        this.openAiChatModel = getIfAvailable(openAiChatModelProvider, "OpenAI/DeepSeek");
        this.ollamaChatModel = getIfAvailable(ollamaChatModelProvider, "Ollama");
    }

    public ConversationContext buildConversationContext(
            String sessionId,
            String userInput,
            String userId,
            String systemPrompt,
            Boolean useRag,
            Integer ragTopK,
            boolean ragEnabledDefault,
            int ragTopKDefault
    ) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        List<RagReference> references = resolveRagReferences(userId, userInput, useRag, ragTopK, ragEnabledDefault, ragTopKDefault);
        if (!references.isEmpty()) {
            messages.add(new SystemMessage(ragService.buildKnowledgePrompt(references)));
        }

        appendLayeredHistory(messages, sessionId, userInput);
        messages.add(new UserMessage(userInput));
        return new ConversationContext(trimToBudget(messages), references);
    }

    public List<Message> buildConversationMessages(String sessionId, String userInput, String systemPrompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        appendLayeredHistory(messages, sessionId, userInput);
        messages.add(new UserMessage(userInput));
        return trimToBudget(messages);
    }

    public void appendPersistedRecordToCache(ChatRecord chatRecord) {
        String key = historyKey(chatRecord.getSessionId());
        int cacheSize = positive(properties.getRedisCacheSize(), 30);
        try {
            redisTemplate.opsForList().rightPush(key, chatRecord);
            redisTemplate.opsForList().trim(key, -cacheSize, -1);
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
            indexSession(chatRecord.getSessionId());
            refreshSummaryIfNeededAsync(chatRecord.getSessionId());
            log.debug("Redis chat history appended, SessionId: {}, CacheSize: {}", chatRecord.getSessionId(), cacheSize);
        } catch (Exception e) {
            log.warn("Redis chat history append failed, evicting cache. SessionId: {}, Error: {}",
                    chatRecord.getSessionId(), e.getMessage());
            evictSessionContext(chatRecord.getSessionId());
        }
    }

    public void evictSessionContext(String sessionId) {
        try {
            redisTemplate.delete(historyKey(sessionId));
        } catch (Exception e) {
            log.warn("Redis history cache delete failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
        try {
            redisTemplate.delete(summaryKey(sessionId));
        } catch (Exception e) {
            log.warn("Redis summary cache delete failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
        try {
            redisTemplate.delete(summaryMetaKey(sessionId));
        } catch (Exception e) {
            log.warn("Redis summary meta cache delete failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
    }

    private void appendLayeredHistory(List<Message> messages, String sessionId, String userInput) {
        List<ChatRecord> history = loadCandidateHistory(sessionId);
        if (history.isEmpty()) {
            return;
        }

        String summary = readSummary(sessionId);
        if (summary != null && !summary.isBlank()) {
            messages.add(new SystemMessage("Earlier conversation summary for long-term context:\n" + summary));
        }

        int recentSize = positive(properties.getRecentWindowSize(), 6);
        int split = Math.max(0, history.size() - recentSize);
        List<ChatRecord> olderHistory = history.subList(0, split);
        List<ChatRecord> recentHistory = history.subList(split, history.size());
        Set<Long> recentIds = recentHistory.stream()
                .map(ChatRecord::getId)
                .collect(Collectors.toSet());

        List<ChatRecord> relevantHistory = selectRelevantHistory(olderHistory, recentIds, userInput);
        if (!relevantHistory.isEmpty()) {
            messages.add(new SystemMessage(buildHistoryBlock("Relevant earlier conversation snippets:", relevantHistory)));
        }

        for (ChatRecord record : recentHistory) {
            addRecordMessages(messages, record);
        }
    }

    private List<ChatRecord> loadCandidateHistory(String sessionId) {
        String key = historyKey(sessionId);
        try {
            long start = System.nanoTime();
            List<Object> rawHistory = redisTemplate.opsForList().range(key, 0, -1);
            long elapsedUs = (System.nanoTime() - start) / 1000;
            if (rawHistory != null && !rawHistory.isEmpty()) {
                List<ChatRecord> history = rawHistory.stream()
                        .map(this::toChatRecord)
                        .sorted(Comparator.comparing(ChatRecord::getCreatedTime, Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
                log.info("Context Redis hit, records: {}, elapsed: {} us", history.size(), elapsedUs);
                return history;
            }
            log.info("Context Redis miss, querying MySQL. SessionId: {}", sessionId);
        } catch (Exception e) {
            log.warn("Context Redis unavailable, falling back to MySQL. SessionId: {}, Error: {}", sessionId, e.getMessage());
            try {
                redisTemplate.delete(key);
            } catch (Exception deleteException) {
                log.warn("Redis history cache delete failed, SessionId: {}, Error: {}", sessionId, deleteException.getMessage());
            }
        }

        int limit = candidateLimit();
        long start = System.nanoTime();
        List<ChatRecord> history = new ArrayList<>(chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId)
                .orderByDesc(ChatRecord::getCreatedTime)
                .last("LIMIT " + limit)));
        Collections.reverse(history);
        long elapsedUs = (System.nanoTime() - start) / 1000;
        log.info("Context MySQL query completed, records: {}, elapsed: {} us", history.size(), elapsedUs);
        if (!history.isEmpty()) {
            writeHistoryCacheAsync(sessionId, history);
        }
        return history;
    }

    private void writeHistoryCacheAsync(String sessionId, List<ChatRecord> history) {
        List<ChatRecord> cacheRecords = new ArrayList<>(history);
        CompletableFuture.runAsync(() -> {
            String key = historyKey(sessionId);
            try {
                redisTemplate.delete(key);
                redisTemplate.opsForList().rightPushAll(key, cacheRecords.toArray());
                redisTemplate.opsForList().trim(key, -positive(properties.getRedisCacheSize(), 30), -1);
                redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
                indexSession(sessionId);
            } catch (Exception e) {
                log.warn("Redis history cache write-back failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
            }
        });
    }

    private void refreshSummaryIfNeededAsync(String sessionId) {
        if (!properties.isSummaryEnabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> refreshSummaryIfNeeded(sessionId));
    }

    private void refreshSummaryIfNeeded(String sessionId) {
        try {
            int trigger = positive(properties.getSummaryTriggerRecords(), 12);
            int refreshEvery = positive(properties.getSummaryRefreshEveryRecords(), 6);
            List<ChatRecord> history = loadCandidateHistory(sessionId);
            if (history.size() < trigger) {
                return;
            }
            Long latestRecordId = latestRecordId(history);
            if (latestRecordId == null) {
                return;
            }
            Long lastSummarizedRecordId = readLastSummarizedRecordId(sessionId);
            if (lastSummarizedRecordId != null && latestRecordId - lastSummarizedRecordId < refreshEvery) {
                return;
            }
            String summary = generateSummary(history);
            if (summary == null || summary.isBlank()) {
                return;
            }
            redisTemplate.opsForValue().set(summaryKey(sessionId), limit(summary, properties.getSummaryMaxChars()),
                    CACHE_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.opsForValue().set(summaryMetaKey(sessionId), latestRecordId,
                    CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Conversation summary refresh skipped. SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
    }

    private String generateSummary(List<ChatRecord> history) {
        ChatModel model = resolveSummaryModel();
        if (model == null) {
            return "";
        }
        try {
            String transcript = history.stream()
                    .map(this::toSummaryLine)
                    .collect(Collectors.joining("\n"));
            List<Message> messages = List.of(
                    new SystemMessage("Summarize customer-service chat history. Keep only goals, user preferences, facts, constraints, and open follow-ups."),
                    new UserMessage("Summarize the following chat history in Chinese within "
                            + positive(properties.getSummaryMaxChars(), 1200)
                            + " characters. Do not invent facts:\n\n" + transcript)
            );
            String text = model.call(new Prompt(messages)).getResult().getOutput().getText();
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            log.warn("Conversation summary generation failed: {}", e.getMessage());
            return "";
        }
    }

    private ChatModel resolveSummaryModel() {
        if (openAiChatModel != null && openAiApiKey != null && !openAiApiKey.isBlank()
                && !"sk-placeholder".equals(openAiApiKey)) {
            return openAiChatModel;
        }
        return ollamaChatModel;
    }

    private String readSummary(String sessionId) {
        if (!properties.isSummaryEnabled()) {
            return "";
        }
        try {
            Object value = redisTemplate.opsForValue().get(summaryKey(sessionId));
            return value == null ? "" : String.valueOf(value);
        } catch (Exception e) {
            log.warn("Redis summary read failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
            return "";
        }
    }

    private List<ChatRecord> selectRelevantHistory(List<ChatRecord> olderHistory, Set<Long> excludedIds, String userInput) {
        if (!properties.isRelevantHistoryEnabled() || olderHistory.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> queryTerms = extractTerms(userInput);
        if (queryTerms.isEmpty()) {
            return Collections.emptyList();
        }
        int candidateSize = positive(properties.getRelevantHistoryCandidateSize(), 50);
        int topK = positive(properties.getRelevantHistoryTopK(), 3);
        int start = Math.max(0, olderHistory.size() - candidateSize);
        return olderHistory.subList(start, olderHistory.size()).stream()
                .filter(record -> record.getId() == null || !excludedIds.contains(record.getId()))
                .map(record -> new ScoredRecord(record, relevanceScore(record, queryTerms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredRecord::score).reversed()
                        .thenComparing(scored -> scored.record().getCreatedTime(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topK)
                .map(ScoredRecord::record)
                .sorted(Comparator.comparing(ChatRecord::getCreatedTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private int relevanceScore(ChatRecord record, Set<String> queryTerms) {
        String text = normalize(record.getUserMessage() + " " + record.getBotResponse());
        int score = 0;
        for (String term : queryTerms) {
            if (text.contains(term)) {
                score += Math.max(1, term.length());
            }
        }
        return score;
    }

    private Set<String> extractTerms(String input) {
        String normalized = normalize(input);
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalized.split("[\\s\\p{Punct}]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        String compact = normalized.replaceAll("[\\s\\p{Punct}]+", "");
        for (int i = 0; i < compact.length() - 1; i++) {
            String gram = compact.substring(i, i + 2);
            if (!gram.isBlank()) {
                terms.add(gram);
            }
        }
        return terms;
    }

    private void addRecordMessages(List<Message> messages, ChatRecord record) {
        String userMessage = record.getUserMessage();
        if (record.getImageData() != null && !record.getImageData().isBlank()) {
            userMessage = userMessage + "\n[The user sent an image in this turn. Image bytes are not included in context cache.]";
        }
        messages.add(new UserMessage(nullToEmpty(userMessage)));
        messages.add(new AssistantMessage(nullToEmpty(record.getBotResponse())));
    }

    private String buildHistoryBlock(String title, List<ChatRecord> records) {
        StringBuilder builder = new StringBuilder(title);
        for (ChatRecord record : records) {
            builder.append("\n- User: ").append(limit(nullToEmpty(record.getUserMessage()), 500))
                    .append("\n  Assistant: ").append(limit(nullToEmpty(record.getBotResponse()), 500));
        }
        return builder.toString();
    }

    private String toSummaryLine(ChatRecord record) {
        String imageHint = record.getImageData() == null || record.getImageData().isBlank() ? "" : " [image]";
        return "User" + imageHint + ": " + limit(nullToEmpty(record.getUserMessage()), 600)
                + "\nAssistant: " + limit(nullToEmpty(record.getBotResponse()), 600);
    }

    private List<Message> trimToBudget(List<Message> messages) {
        int budget = positive(properties.getMaxContextChars(), 12000);
        List<Message> result = new ArrayList<>(messages);
        while (totalChars(result) > budget && result.size() > 2) {
            result.remove(1);
        }
        return result;
    }

    private int totalChars(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            String content = message.getText();
            total += content == null ? 0 : content.length();
        }
        return total;
    }

    private List<RagReference> resolveRagReferences(
            String userId,
            String userInput,
            Boolean useRag,
            Integer ragTopK,
            boolean ragEnabledDefault,
            int ragTopKDefault
    ) {
        boolean shouldUseRag = useRag == null ? ragEnabledDefault : useRag;
        if (!shouldUseRag) {
            return Collections.emptyList();
        }
        try {
            int topK = ragTopK == null || ragTopK <= 0 ? ragTopKDefault : ragTopK;
            return ragService.retrieveReferences(Long.valueOf(userId), userInput, topK);
        } catch (Exception e) {
            log.warn("RAG retrieval failed, falling back to plain chat: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private int candidateLimit() {
        return Math.max(positive(properties.getRedisCacheSize(), 30),
                Math.max(positive(properties.getRelevantHistoryCandidateSize(), 50),
                        positive(properties.getRecentWindowSize(), 6)));
    }

    private String normalize(String value) {
        return nullToEmpty(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private ChatRecord toChatRecord(Object value) {
        if (value instanceof ChatRecord record) {
            return record;
        }
        return objectMapper.convertValue(value, ChatRecord.class);
    }

    private String limit(String value, int maxChars) {
        String safe = nullToEmpty(value);
        if (maxChars <= 0 || safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, maxChars);
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String historyKey(String sessionId) {
        return HISTORY_KEY_PREFIX + sessionId;
    }

    private String summaryKey(String sessionId) {
        return SUMMARY_KEY_PREFIX + sessionId;
    }

    private String summaryMetaKey(String sessionId) {
        return SUMMARY_META_KEY_PREFIX + sessionId;
    }

    private Long latestRecordId(List<ChatRecord> history) {
        return history.stream()
                .map(ChatRecord::getId)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
    }

    private Long readLastSummarizedRecordId(String sessionId) {
        Object value = redisTemplate.opsForValue().get(summaryMetaKey(sessionId));
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void evictUserContext(Long userId) {
        if (userId == null) {
            return;
        }
        String indexKey = SESSION_INDEX_PREFIX + userId;
        try {
            Set<Object> sessions = redisTemplate.opsForSet().members(indexKey);
            if (sessions != null) {
                for (Object session : sessions) {
                    evictSessionContext(String.valueOf(session));
                }
            }
            redisTemplate.delete(indexKey);
        } catch (Exception e) {
            log.warn("Redis user context evict failed, userId: {}, Error: {}", userId, e.getMessage());
        }
    }

    private void indexSession(String sessionId) {
        try {
            Long userId = resolveUserId(sessionId);
            if (userId == null) {
                return;
            }
            String indexKey = SESSION_INDEX_PREFIX + userId;
            redisTemplate.opsForSet().add(indexKey, sessionId);
            redisTemplate.expire(indexKey, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis session index update failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
    }

    private Long resolveUserId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        int split = sessionId.indexOf('_');
        if (split <= 0) {
            return null;
        }
        try {
            return Long.valueOf(sessionId.substring(0, split));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private <T> T getIfAvailable(ObjectProvider<T> provider, String name) {
        try {
            return provider.getIfAvailable();
        } catch (Exception e) {
            log.warn("{} model unavailable for context service: {}", name, e.getMessage());
            return null;
        }
    }

    private record ScoredRecord(ChatRecord record, int score) {
    }
}
