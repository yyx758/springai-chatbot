package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.context.ContextCompactionResult;
import com.example.chatbot.context.ContextCompressionService;
import com.example.chatbot.context.ContextSegment;
import com.example.chatbot.context.ContextSegmentType;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.entity.ChatSessionSummary;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.mapper.ChatSessionSummaryMapper;
import com.example.chatbot.memory.MemoryIndexService;
import com.example.chatbot.memory.MemoryProperties;
import com.example.chatbot.memory.MemoryPromptBuilder;
import com.example.chatbot.memory.MemorySelectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
    private final ContextCompressionService contextCompressionService;
    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;
    private MemoryIndexService memoryIndexService;
    private MemoryPromptBuilder memoryPromptBuilder;
    private MemorySelectionService memorySelectionService;
    private MemoryProperties memoryProperties;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    public ChatContextService(
            ChatRecordMapper chatRecordMapper,
            ChatSessionSummaryMapper chatSessionSummaryMapper,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            RagService ragService,
            ChatContextProperties properties,
            ContextCompressionService contextCompressionService,
            ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider
    ) {
        this.chatRecordMapper = chatRecordMapper;
        this.chatSessionSummaryMapper = chatSessionSummaryMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.properties = properties;
        this.contextCompressionService = contextCompressionService;
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
        List<ContextSegment> segments = new ArrayList<>();
        segments.add(segment(ContextSegmentType.SYSTEM_FIXED, "system", systemPrompt));
        appendLongTermMemorySegments(segments, userId, userInput);
        List<RagReference> references = resolveRagReferences(userId, userInput, useRag, ragTopK, ragEnabledDefault, ragTopKDefault);
        appendLayeredHistory(segments, sessionId, userInput);
        if (!references.isEmpty()) {
            segments.add(segment(ContextSegmentType.RAG_CONTEXT, "system", ragService.buildKnowledgePrompt(references)));
        }
        segments.add(segment(ContextSegmentType.CURRENT_USER_INPUT, "user", userInput));
        return new ConversationContext(compressToMessages(segments), references);
    }

    public List<Message> buildConversationMessages(String sessionId, String userInput, String systemPrompt) {
        List<ContextSegment> segments = new ArrayList<>();
        segments.add(segment(ContextSegmentType.SYSTEM_FIXED, "system", systemPrompt));
        appendLongTermMemorySegments(segments, resolveUserId(sessionId), userInput);
        appendLayeredHistory(segments, sessionId, userInput);
        segments.add(segment(ContextSegmentType.CURRENT_USER_INPUT, "user", userInput));
        return compressToMessages(segments);
    }

    @Autowired(required = false)
    public void setMemoryIndexService(MemoryIndexService memoryIndexService) {
        this.memoryIndexService = memoryIndexService;
    }

    @Autowired(required = false)
    public void setMemoryPromptBuilder(MemoryPromptBuilder memoryPromptBuilder) {
        this.memoryPromptBuilder = memoryPromptBuilder;
    }

    @Autowired(required = false)
    public void setMemorySelectionService(MemorySelectionService memorySelectionService) {
        this.memorySelectionService = memorySelectionService;
    }

    @Autowired(required = false)
    public void setMemoryProperties(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties;
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

    private void appendLayeredHistory(List<ContextSegment> segments, String sessionId, String userInput) {
        String summary = readSummary(sessionId);
        if (summary != null && !summary.isBlank()) {
            segments.add(segment(ContextSegmentType.SESSION_SUMMARY, "system",
                    "Earlier conversation summary for long-term context:\n" + summary));
        }

        List<ChatRecord> recentHistory = loadRecentHistory(sessionId);
        for (ChatRecord record : recentHistory) {
            addRecordMessages(segments, record);
        }
    }

    private void appendLongTermMemorySegments(List<ContextSegment> segments, String userId, String userInput) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            appendLongTermMemorySegments(segments, Long.valueOf(userId), userInput);
        } catch (NumberFormatException ignored) {
        }
    }

    private void appendLongTermMemorySegments(List<ContextSegment> segments, Long userId, String userInput) {
        if (memoryIndexService == null || memoryPromptBuilder == null || userId == null) {
            return;
        }
        try {
            String prompt = memoryPromptBuilder.buildIndexPrompt(memoryIndexService.loadIndex(userId, null));
            if (prompt != null && !prompt.isBlank()) {
                ContextSegment segment = segment(ContextSegmentType.MEMORY_INDEX, "system", prompt);
                segment.setSourceRef("memory:index");
                segments.add(segment);
            }
            if (memorySelectionService != null && memoryProperties != null && memoryProperties.isDetailSelectionEnabled()) {
                String detailPrompt = memorySelectionService.selectDetailPreview(userId, null, userInput).getPrompt();
                if (detailPrompt != null && !detailPrompt.isBlank()) {
                    ContextSegment detail = segment(ContextSegmentType.MEMORY_DETAIL, "system", detailPrompt);
                    detail.setSourceRef("memory:detail:selected");
                    segments.add(detail);
                }
            }
        } catch (Exception e) {
            log.warn("Long-term memory index skipped. userId={}, error={}", userId, e.getMessage());
        }
    }

    private List<ChatRecord> loadRecentHistory(String sessionId) {
        String key = historyKey(sessionId);
        int recentSize = positive(properties.getRecentWindowSize(), 6);
        try {
            long start = System.nanoTime();
            List<Object> rawHistory = redisTemplate.opsForList().range(key, -recentSize, -1);
            long elapsedUs = (System.nanoTime() - start) / 1000;
            if (rawHistory != null && !rawHistory.isEmpty()) {
                List<ChatRecord> history = toSortedHistory(rawHistory);
                log.info("Recent context Redis hit, records: {}, elapsed: {} us", history.size(), elapsedUs);
                return history;
            }
            log.info("Recent context Redis miss, querying MySQL. SessionId: {}", sessionId);
        } catch (Exception e) {
            log.warn("Recent context Redis unavailable, falling back to MySQL. SessionId: {}, Error: {}",
                    sessionId, e.getMessage());
            try {
                redisTemplate.delete(key);
            } catch (Exception deleteException) {
                log.warn("Redis history cache delete failed, SessionId: {}, Error: {}", sessionId, deleteException.getMessage());
            }
        }

        long start = System.nanoTime();
        List<ChatRecord> history = new ArrayList<>(chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId)
                .orderByDesc(ChatRecord::getCreatedTime)
                .last("LIMIT " + recentSize)));
        Collections.reverse(history);
        long elapsedUs = (System.nanoTime() - start) / 1000;
        log.info("Recent context MySQL query completed, records: {}, elapsed: {} us", history.size(), elapsedUs);
        return history;
    }

    private List<ChatRecord> loadInitialSummaryHistory(String sessionId) {
        int limit = candidateLimit();
        long start = System.nanoTime();
        List<ChatRecord> history = new ArrayList<>(chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId)
                .orderByDesc(ChatRecord::getCreatedTime)
                .last("LIMIT " + limit)));
        Collections.reverse(history);
        long elapsedUs = (System.nanoTime() - start) / 1000;
        log.info("Summary initial MySQL query completed, records: {}, elapsed: {} us", history.size(), elapsedUs);
        return history;
    }

    private List<ChatRecord> loadIncrementalSummaryHistory(String sessionId, Long lastSummarizedRecordId) {
        if (lastSummarizedRecordId == null) {
            return loadInitialSummaryHistory(sessionId);
        }
        int limit = candidateLimit();
        long start = System.nanoTime();
        List<ChatRecord> history = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId)
                .gt(ChatRecord::getId, lastSummarizedRecordId)
                .orderByAsc(ChatRecord::getId)
                .last("LIMIT " + limit));
        long elapsedUs = (System.nanoTime() - start) / 1000;
        log.info("Summary incremental MySQL query completed, records: {}, lastSummarizedRecordId={}, elapsed: {} us",
                history.size(), lastSummarizedRecordId, elapsedUs);
        return history;
    }

    private List<ChatRecord> toSortedHistory(List<Object> rawHistory) {
        return rawHistory.stream()
                .map(this::toChatRecord)
                .sorted(Comparator.comparing(ChatRecord::getCreatedTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
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

    void refreshSummaryIfNeeded(String sessionId) {
        try {
            int trigger = positive(properties.getSummaryTriggerRecords(), 12);
            int refreshEvery = positive(properties.getSummaryRefreshEveryRecords(), 6);
            Long lastSummarizedRecordId = readLastSummarizedRecordId(sessionId);
            String existingSummary = readSummary(sessionId);
            boolean hasExistingSummary = existingSummary != null && !existingSummary.isBlank();
            List<ChatRecord> incrementalHistory = hasExistingSummary
                    ? loadIncrementalSummaryHistory(sessionId, lastSummarizedRecordId)
                    : loadInitialSummaryHistory(sessionId);
            if (!hasExistingSummary && incrementalHistory.size() < trigger) {
                return;
            }
            if (incrementalHistory.isEmpty()) {
                return;
            }
            if (hasExistingSummary && incrementalHistory.size() < refreshEvery) {
                return;
            }
            Long latestRecordId = latestRecordId(incrementalHistory);
            if (latestRecordId == null) {
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

    private void addRecordMessages(List<ContextSegment> segments, ChatRecord record) {
        String userMessage = record.getUserMessage();
        if (record.getImageData() != null && !record.getImageData().isBlank()) {
            userMessage = userMessage + "\n[The user sent an image in this turn. Image bytes are not included in context cache.]";
        }
        int maxChars = positive(properties.getRecentMessageMaxChars(), 2000);
        segments.add(segment(ContextSegmentType.RECENT_HISTORY, "user", limit(nullToEmpty(userMessage), maxChars)));
        segments.add(segment(ContextSegmentType.RECENT_HISTORY, "assistant", limit(nullToEmpty(record.getBotResponse()), maxChars)));
    }

    private String toSummaryLine(ChatRecord record) {
        String imageHint = record.getImageData() == null || record.getImageData().isBlank() ? "" : " [image]";
        return "User" + imageHint + ": " + limit(nullToEmpty(record.getUserMessage()), 600)
                + "\nAssistant: " + limit(nullToEmpty(record.getBotResponse()), 600);
    }

    private List<Message> compressToMessages(List<ContextSegment> segments) {
        ContextCompactionResult result = contextCompressionService.compact(
                segments, positive(properties.getMaxContextChars(), 12000));
        return result.getSegments().stream()
                .map(ContextSegment::toMessage)
                .toList();
    }

    private ContextSegment segment(ContextSegmentType type, String role, String content) {
        return ContextSegment.builder()
                .type(type)
                .role(role)
                .content(nullToEmpty(content))
                .required(type == ContextSegmentType.SYSTEM_FIXED || type == ContextSegmentType.CURRENT_USER_INPUT)
                .compactable(type != ContextSegmentType.SYSTEM_FIXED && type != ContextSegmentType.CURRENT_USER_INPUT)
                .priority(priority(type))
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private int priority(ContextSegmentType type) {
        return switch (type) {
            case SYSTEM_FIXED, CURRENT_USER_INPUT -> 0;
            case TOOL_SCHEMA, USER_MEMORY, MEMORY_INDEX, MEMORY_DETAIL -> 1;
            case SESSION_SUMMARY -> 2;
            case RAG_CONTEXT -> 3;
            case RECENT_HISTORY -> 4;
            case WEB_CONTEXT -> 5;
            case WORKSPACE_FILE, CODE_REVIEW_DIFF -> 6;
            case TOOL_RESULT -> 7;
            case COMPACTED_SUMMARY -> 2;
        };
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
                Math.max(positive(properties.getRecentWindowSize(), 6),
                        positive(properties.getSummaryTriggerRecords(), 12)
                                + positive(properties.getSummaryRefreshEveryRecords(), 6)));
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

}
