package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.entity.ChatSessionSummary;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.mapper.ChatSessionSummaryMapper;
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
    private static final String HISTORY_ID_KEY_PREFIX = "chat:history:ids:";
    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final String SUMMARY_META_KEY_PREFIX = "chat:summary:meta:";
    private static final String SESSION_INDEX_PREFIX = "chat:sessions:";
    private static final long CACHE_TTL_HOURS = 2;

    private final ChatRecordMapper chatRecordMapper;
    private final ChatSessionSummaryMapper chatSessionSummaryMapper;
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
            ChatSessionSummaryMapper chatSessionSummaryMapper,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            RagService ragService,
            ChatContextProperties properties,
            ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider
    ) {
        this.chatRecordMapper = chatRecordMapper;
        this.chatSessionSummaryMapper = chatSessionSummaryMapper;
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
        appendPersistedRecordToCache(chatRecord, false);
    }

    public void appendPersistedRecordToCacheStrict(ChatRecord chatRecord) {
        appendPersistedRecordToCache(chatRecord, true);
    }

    private void appendPersistedRecordToCache(ChatRecord chatRecord, boolean failFast) {
        String key = historyKey(chatRecord.getSessionId());
        String idKey = historyIdKey(chatRecord.getSessionId());
        int cacheSize = positive(properties.getRedisCacheSize(), 30);
        try {
            if (isRecordAlreadyCached(key, idKey, chatRecord)) {
                redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
                redisTemplate.expire(idKey, CACHE_TTL_HOURS, TimeUnit.HOURS);
                indexSession(chatRecord.getSessionId());
                log.debug("Redis chat history already cached, SessionId: {}, RecordId: {}",
                        chatRecord.getSessionId(), chatRecord.getId());
                return;
            }
            redisTemplate.opsForList().rightPush(key, chatRecord);
            if (chatRecord.getId() != null) {
                redisTemplate.opsForZSet().add(idKey, chatRecord.getId(), chatRecord.getId());
            }
            redisTemplate.opsForList().trim(key, -cacheSize, -1);
            trimHistoryIdIndex(idKey, cacheSize);
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.expire(idKey, CACHE_TTL_HOURS, TimeUnit.HOURS);
            indexSession(chatRecord.getSessionId());
            log.debug("Redis chat history appended, SessionId: {}, CacheSize: {}", chatRecord.getSessionId(), cacheSize);
        } catch (Exception e) {
            log.warn("Redis chat history append failed, evicting cache. SessionId: {}, Error: {}",
                    chatRecord.getSessionId(), e.getMessage());
            evictSessionContext(chatRecord.getSessionId());
            if (failFast) {
                throw new IllegalStateException("Redis chat history append failed, sessionId="
                        + chatRecord.getSessionId(), e);
            }
        }
    }

    public void evictSessionContext(String sessionId) {
        try {
            redisTemplate.delete(historyKey(sessionId));
        } catch (Exception e) {
            log.warn("Redis history cache delete failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
        try {
            redisTemplate.delete(historyIdKey(sessionId));
        } catch (Exception e) {
            log.warn("Redis history id cache delete failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
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

    public void deleteSessionContext(String sessionId) {
        evictSessionContext(sessionId);
        try {
            chatSessionSummaryMapper.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("Persistent summary delete failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
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
            String idKey = historyIdKey(sessionId);
            try {
                redisTemplate.delete(key);
                redisTemplate.delete(idKey);
                redisTemplate.opsForList().rightPushAll(key, cacheRecords.toArray());
                for (ChatRecord record : cacheRecords) {
                    if (record.getId() != null) {
                        redisTemplate.opsForZSet().add(idKey, record.getId(), record.getId());
                    }
                }
                redisTemplate.opsForList().trim(key, -positive(properties.getRedisCacheSize(), 30), -1);
                trimHistoryIdIndex(idKey, positive(properties.getRedisCacheSize(), 30));
                redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
                redisTemplate.expire(idKey, CACHE_TTL_HOURS, TimeUnit.HOURS);
                indexSession(sessionId);
            } catch (Exception e) {
                log.warn("Redis history cache write-back failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
            }
        });
    }

    private boolean isRecordAlreadyCached(String key, String idKey, ChatRecord chatRecord) {
        Long recordId = chatRecord.getId();
        if (recordId == null) {
            return false;
        }
        Double score = redisTemplate.opsForZSet().score(idKey, recordId);
        if (score != null) {
            return true;
        }
        List<Object> rawHistory = redisTemplate.opsForList().range(key, 0, -1);
        if (rawHistory == null || rawHistory.isEmpty()) {
            return false;
        }
        for (Object rawRecord : rawHistory) {
            ChatRecord cachedRecord = toChatRecord(rawRecord);
            if (recordId.equals(cachedRecord.getId())) {
                redisTemplate.opsForZSet().add(idKey, recordId, recordId);
                return true;
            }
        }
        return false;
    }

    private void trimHistoryIdIndex(String idKey, int cacheSize) {
        Long size = redisTemplate.opsForZSet().size(idKey);
        if (size != null && size > cacheSize) {
            redisTemplate.opsForZSet().removeRange(idKey, 0, size - cacheSize - 1);
        }
    }

    public void refreshSummaryIfNeededAsync(String sessionId) {
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
            String existingSummary = readSummary(sessionId);
            boolean hasExistingSummary = existingSummary != null && !existingSummary.isBlank();
            List<ChatRecord> incrementalHistory = selectIncrementalHistory(
                    history, hasExistingSummary ? lastSummarizedRecordId : null);
            if (incrementalHistory.isEmpty()) {
                return;
            }
            if (hasExistingSummary && incrementalHistory.size() < refreshEvery) {
                return;
            }
            String summary = generateSummary(existingSummary, incrementalHistory);
            if (summary == null || summary.isBlank()) {
                return;
            }
            persistSummary(sessionId, summary, latestRecordId);
        } catch (Exception e) {
            log.warn("Conversation summary refresh skipped. SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
    }

    private String generateSummary(String existingSummary, List<ChatRecord> incrementalHistory) {
        ChatModel model = resolveSummaryModel();
        if (model == null) {
            return "";
        }
        try {
            List<Message> messages = buildSummaryPrompt(existingSummary, incrementalHistory);
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
            if (value != null) {
                return String.valueOf(value);
            }
        } catch (Exception e) {
            log.warn("Redis summary read failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
        ChatSessionSummary persisted = loadPersistedSummary(sessionId);
        if (persisted == null || persisted.getSummary() == null) {
            return "";
        }
        cacheSummary(sessionId, persisted.getSummary(), persisted.getLastSummarizedRecordId());
        return persisted.getSummary();
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

    private String historyIdKey(String sessionId) {
        return HISTORY_ID_KEY_PREFIX + sessionId;
    }

    private String summaryKey(String sessionId) {
        return SUMMARY_KEY_PREFIX + sessionId;
    }

    private String summaryMetaKey(String sessionId) {
        return SUMMARY_META_KEY_PREFIX + sessionId;
    }

    private void persistSummary(String sessionId, String summary, Long latestRecordId) {
        String limitedSummary = limit(summary, properties.getSummaryMaxChars());
        ChatSessionSummary entity = ChatSessionSummary.builder()
                .sessionId(sessionId)
                .userId(resolveUserId(sessionId))
                .summary(limitedSummary)
                .lastSummarizedRecordId(latestRecordId)
                .build();
        chatSessionSummaryMapper.upsertSummary(entity);
        cacheSummary(sessionId, limitedSummary, latestRecordId);
    }

    private ChatSessionSummary loadPersistedSummary(String sessionId) {
        try {
            return chatSessionSummaryMapper.selectBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("Persistent summary read failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void cacheSummary(String sessionId, String summary, Long lastSummarizedRecordId) {
        try {
            if (summary != null && !summary.isBlank()) {
                redisTemplate.opsForValue().set(summaryKey(sessionId),
                        limit(summary, properties.getSummaryMaxChars()), CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
            if (lastSummarizedRecordId != null) {
                redisTemplate.opsForValue().set(summaryMetaKey(sessionId),
                        lastSummarizedRecordId, CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.warn("Redis summary cache write failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
    }

    private Long latestRecordId(List<ChatRecord> history) {
        return history.stream()
                .map(ChatRecord::getId)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
    }

    private Long readLastSummarizedRecordId(String sessionId) {
        try {
            Object value = redisTemplate.opsForValue().get(summaryMetaKey(sessionId));
            if (value != null) {
                return Long.valueOf(String.valueOf(value));
            }
        } catch (Exception e) {
            log.warn("Redis summary meta read failed, SessionId: {}, Error: {}", sessionId, e.getMessage());
        }
        ChatSessionSummary persisted = loadPersistedSummary(sessionId);
        if (persisted == null) {
            return null;
        }
        cacheSummary(sessionId, persisted.getSummary(), persisted.getLastSummarizedRecordId());
        return persisted.getLastSummarizedRecordId();
    }

    List<ChatRecord> selectIncrementalHistory(List<ChatRecord> history, Long lastSummarizedRecordId) {
        if (lastSummarizedRecordId == null) {
            return history;
        }
        return history.stream()
                .filter(record -> record.getId() != null && record.getId() > lastSummarizedRecordId)
                .toList();
    }

    List<Message> buildSummaryPrompt(String existingSummary, List<ChatRecord> incrementalHistory) {
        String transcript = incrementalHistory.stream()
                .map(this::toSummaryLine)
                .collect(Collectors.joining("\n"));
        String safeExistingSummary = existingSummary == null ? "" : existingSummary.trim();
        String task = safeExistingSummary.isBlank()
                ? "Summarize the following new chat history in Chinese within "
                : "Merge the existing summary and the new chat history into an updated Chinese summary within ";
        StringBuilder userPrompt = new StringBuilder(task)
                .append(positive(properties.getSummaryMaxChars(), 2000))
                .append(" characters. Keep only durable goals, user preferences, facts, constraints, decisions, and open follow-ups. ")
                .append("Do not invent facts. Drop resolved small talk and duplicated details.");
        if (!safeExistingSummary.isBlank()) {
            userPrompt.append("\n\nExisting summary:\n")
                    .append(limit(safeExistingSummary, positive(properties.getSummaryMaxChars(), 2000)));
        }
        userPrompt.append("\n\nNew chat history since last summary:\n")
                .append(transcript);
        return List.of(
                new SystemMessage("You maintain a rolling long-term memory for a customer-service chat. Preserve stable facts and unresolved work; remove stale or redundant details."),
                new UserMessage(userPrompt.toString())
        );
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
