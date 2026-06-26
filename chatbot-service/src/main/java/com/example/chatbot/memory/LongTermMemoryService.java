package com.example.chatbot.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.AgentLongTermMemory;
import com.example.chatbot.entity.AgentMemoryEvent;
import com.example.chatbot.mapper.AgentLongTermMemoryMapper;
import com.example.chatbot.mapper.AgentMemoryEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LongTermMemoryService {

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)api[_-]?key\\s*[:=]"),
            Pattern.compile("(?i)bearer\\s+[a-z0-9._\\-]{16,}"),
            Pattern.compile("(?i)password\\s*[:=]"),
            Pattern.compile("(?i)private\\s+key"),
            Pattern.compile("(?i)authorization\\s*[:=]"),
            Pattern.compile("(?i)^\\s*[A-Z0-9_]*(SECRET|TOKEN|PASSWORD|KEY)[A-Z0-9_]*\\s*=", Pattern.MULTILINE)
    );

    private final AgentLongTermMemoryMapper memoryMapper;
    private final AgentMemoryEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    public List<AgentLongTermMemory> list(Long userId,
                                          String scopeType,
                                          String scopeKey,
                                          String memoryType,
                                          String status,
                                          String keyword,
                                          Integer limit) {
        LambdaQueryWrapper<AgentLongTermMemory> query = new LambdaQueryWrapper<AgentLongTermMemory>()
                .eq(AgentLongTermMemory::getUserId, requireUserId(userId))
                .orderByDesc(AgentLongTermMemory::getUpdatedTime)
                .orderByDesc(AgentLongTermMemory::getId)
                .last("LIMIT " + safeLimit(limit));
        if (notBlank(scopeType)) {
            query.eq(AgentLongTermMemory::getScopeType, normalizeEnum(scopeType));
        }
        if (notBlank(scopeKey)) {
            query.eq(AgentLongTermMemory::getScopeKey, scopeKey.trim());
        }
        if (notBlank(memoryType)) {
            query.eq(AgentLongTermMemory::getMemoryType, memoryType.trim().toLowerCase(Locale.ROOT));
        }
        if (notBlank(status)) {
            query.eq(AgentLongTermMemory::getStatus, normalizeEnum(status));
        }
        if (notBlank(keyword)) {
            String like = keyword.trim();
            query.and(wrapper -> wrapper.like(AgentLongTermMemory::getName, like)
                    .or()
                    .like(AgentLongTermMemory::getDescription, like));
        }
        return memoryMapper.selectList(query);
    }

    public AgentLongTermMemory get(Long userId, Long memoryId) {
        AgentLongTermMemory memory = memoryMapper.selectOne(new LambdaQueryWrapper<AgentLongTermMemory>()
                .eq(AgentLongTermMemory::getUserId, requireUserId(userId))
                .eq(AgentLongTermMemory::getId, memoryId)
                .last("LIMIT 1"));
        if (memory == null) {
            throw new IllegalArgumentException("memory not found");
        }
        return memory;
    }

    public AgentLongTermMemory create(Long userId, LongTermMemoryRequest request) {
        requireNoSecret(request.getContent());
        String contentHash = hash(request.getContent());
        AgentLongTermMemory duplicate = memoryMapper.selectOne(new LambdaQueryWrapper<AgentLongTermMemory>()
                .eq(AgentLongTermMemory::getUserId, requireUserId(userId))
                .eq(AgentLongTermMemory::getContentHash, contentHash)
                .last("LIMIT 1"));
        if (duplicate != null) {
            throw new IllegalArgumentException("duplicate memory content");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentLongTermMemory memory = AgentLongTermMemory.builder()
                .userId(userId)
                .scopeType(resolveScopeType(request.getScopeType()))
                .scopeKey(resolveScopeKey(userId, request.getScopeType(), request.getScopeKey()))
                .memoryType(resolveMemoryType(request.getMemoryType()))
                .name(requiredText(request.getName(), "name", 128))
                .description(requiredText(request.getDescription(), "description", 512))
                .content(requiredText(request.getContent(), "content", 65535))
                .loadHint(trimToNull(request.getLoadHint(), 512))
                .sourceType(resolveSourceType(request.getSourceType()))
                .status(resolveStatus(request.getStatus()))
                .createdTime(now)
                .updatedTime(now)
                .useCount(0L)
                .contentHash(contentHash)
                .build();
        memoryMapper.insert(memory);
        writeEvent(memory.getUserId(), memory.getId(), "CREATE", null, Map.of("name", memory.getName()));
        return memory;
    }

    public AgentLongTermMemory update(Long userId, Long memoryId, LongTermMemoryRequest request) {
        AgentLongTermMemory existing = get(userId, memoryId);
        requireNoSecret(request.getContent());
        existing.setScopeType(resolveScopeType(defaultText(request.getScopeType(), existing.getScopeType())));
        existing.setScopeKey(resolveScopeKey(userId, existing.getScopeType(), defaultText(request.getScopeKey(), existing.getScopeKey())));
        existing.setMemoryType(resolveMemoryType(defaultText(request.getMemoryType(), existing.getMemoryType())));
        existing.setName(requiredText(defaultText(request.getName(), existing.getName()), "name", 128));
        existing.setDescription(requiredText(defaultText(request.getDescription(), existing.getDescription()), "description", 512));
        existing.setContent(requiredText(defaultText(request.getContent(), existing.getContent()), "content", 65535));
        existing.setLoadHint(trimToNull(defaultText(request.getLoadHint(), existing.getLoadHint()), 512));
        existing.setSourceType(resolveSourceType(defaultText(request.getSourceType(), existing.getSourceType())));
        existing.setStatus(resolveStatus(defaultText(request.getStatus(), existing.getStatus())));
        existing.setContentHash(hash(existing.getContent()));
        existing.setUpdatedTime(LocalDateTime.now());
        memoryMapper.updateById(existing);
        writeEvent(userId, memoryId, "UPDATE", null, Map.of("name", existing.getName()));
        return existing;
    }

    public AgentLongTermMemory archive(Long userId, Long memoryId) {
        AgentLongTermMemory memory = get(userId, memoryId);
        memory.setStatus(MemoryStatus.ARCHIVED.name());
        memory.setUpdatedTime(LocalDateTime.now());
        memoryMapper.updateById(memory);
        writeEvent(userId, memoryId, "ARCHIVE", null, Map.of("name", memory.getName()));
        return memory;
    }

    public void delete(Long userId, Long memoryId) {
        AgentLongTermMemory memory = get(userId, memoryId);
        memoryMapper.deleteById(memory.getId());
        writeEvent(userId, memoryId, "DELETE", null, Map.of("name", memory.getName()));
    }

    public List<AgentLongTermMemory> loadActiveDetails(Long userId, List<Long> memoryIds, boolean markUsed) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return List.of();
        }
        List<AgentLongTermMemory> memories = memoryMapper.selectList(new LambdaQueryWrapper<AgentLongTermMemory>()
                .eq(AgentLongTermMemory::getUserId, requireUserId(userId))
                .eq(AgentLongTermMemory::getStatus, MemoryStatus.ACTIVE.name())
                .in(AgentLongTermMemory::getId, memoryIds));
        if (markUsed) {
            for (AgentLongTermMemory memory : memories) {
                memory.setLastUsedTime(LocalDateTime.now());
                memory.setUseCount(memory.getUseCount() == null ? 1L : memory.getUseCount() + 1L);
                memoryMapper.updateById(memory);
                writeEvent(userId, memory.getId(), "LOAD_DETAIL", null, Map.of("name", memory.getName()));
            }
        }
        return memories;
    }

    public Long createSuggestion(Long userId, MemorySuggestionService.MemorySuggestion suggestion, String sourceSessionId) {
        Map<String, Object> payload = Map.of(
                "scopeType", suggestion.getScopeType(),
                "memoryType", suggestion.getMemoryType(),
                "name", suggestion.getName(),
                "description", suggestion.getDescription(),
                "content", suggestion.getContent(),
                "loadHint", suggestion.getLoadHint()
        );
        return writeEvent(userId, null, "SUGGEST", sourceSessionId, payload);
    }

    public AgentLongTermMemory applySuggestion(Long userId, Long suggestionId) {
        AgentMemoryEvent event = eventMapper.selectById(suggestionId);
        if (event == null || !userId.equals(event.getUserId()) || !"SUGGEST".equals(event.getEventType())) {
            throw new IllegalArgumentException("memory suggestion not found");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(event.getPayloadJson(), Map.class);
            LongTermMemoryRequest request = new LongTermMemoryRequest();
            request.setScopeType(String.valueOf(payload.getOrDefault("scopeType", "USER")));
            request.setMemoryType(String.valueOf(payload.getOrDefault("memoryType", "feedback")));
            request.setName(String.valueOf(payload.getOrDefault("name", "Memory suggestion")));
            request.setDescription(String.valueOf(payload.getOrDefault("description", "")));
            request.setContent(String.valueOf(payload.getOrDefault("content", "")));
            request.setLoadHint(String.valueOf(payload.getOrDefault("loadHint", "")));
            request.setSourceType(MemorySourceType.AGENT_SUGGESTED.name());
            request.setStatus(MemoryStatus.ACTIVE.name());
            AgentLongTermMemory memory = create(userId, request);
            writeEvent(userId, memory.getId(), "SUGGEST_APPLY", event.getSourceSessionId(), Map.of("suggestionId", suggestionId));
            return memory;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid memory suggestion payload", e);
        }
    }

    public void dismissSuggestion(Long userId, Long suggestionId) {
        AgentMemoryEvent event = eventMapper.selectById(suggestionId);
        if (event == null || !userId.equals(event.getUserId()) || !"SUGGEST".equals(event.getEventType())) {
            throw new IllegalArgumentException("memory suggestion not found");
        }
        writeEvent(userId, null, "SUGGEST_DISMISS", event.getSourceSessionId(), Map.of("suggestionId", suggestionId));
    }

    public Long writeEvent(Long userId, Long memoryId, String eventType, String sourceSessionId, Map<String, ?> payload) {
        String payloadJson = "{}";
        try {
            payloadJson = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ignored) {
        }
        AgentMemoryEvent event = AgentMemoryEvent.builder()
                .memoryId(memoryId)
                .userId(userId)
                .eventType(eventType)
                .sourceSessionId(sourceSessionId)
                .payloadJson(payloadJson)
                .createdTime(LocalDateTime.now())
                .build();
        eventMapper.insert(event);
        return event.getId();
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        return userId;
    }

    private void requireNoSecret(String content) {
        String safe = content == null ? "" : content;
        for (Pattern pattern : SECRET_PATTERNS) {
            if (pattern.matcher(safe).find()) {
                throw new IllegalArgumentException("memory content appears to contain a secret");
            }
        }
    }

    private String resolveScopeType(String value) {
        String resolved = normalizeEnum(defaultText(value, MemoryScopeType.USER.name()));
        MemoryScopeType.valueOf(resolved);
        return resolved;
    }

    private String resolveScopeKey(Long userId, String scopeType, String scopeKey) {
        String resolvedScopeType = resolveScopeType(scopeType);
        if (MemoryScopeType.USER.name().equals(resolvedScopeType)) {
            return String.valueOf(userId);
        }
        if (MemoryScopeType.PROJECT.name().equals(resolvedScopeType) && !notBlank(scopeKey)) {
            return properties.getDefaultProjectKey();
        }
        return requiredText(scopeKey, "scopeKey", 255);
    }

    private String resolveMemoryType(String value) {
        String resolved = defaultText(value, MemoryType.user.name()).trim().toLowerCase(Locale.ROOT);
        MemoryType.valueOf(resolved);
        return resolved;
    }

    private String resolveSourceType(String value) {
        String resolved = normalizeEnum(defaultText(value, MemorySourceType.MANUAL.name()));
        MemorySourceType.valueOf(resolved);
        return resolved;
    }

    private String resolveStatus(String value) {
        String resolved = normalizeEnum(defaultText(value, MemoryStatus.ACTIVE.name()));
        MemoryStatus.valueOf(resolved);
        return resolved;
    }

    private String hash(String content) {
        String normalized = content == null ? "" : content.trim().replace("\r\n", "\n");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private String requiredText(String value, String field, int maxChars) {
        if (!notBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxChars) {
            return trimmed.substring(0, maxChars);
        }
        return trimmed;
    }

    private String trimToNull(String value, int maxChars) {
        if (!notBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed;
    }

    private String defaultText(String value, String fallback) {
        return notBlank(value) ? value : fallback;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeEnum(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private int safeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, 200);
    }
}
