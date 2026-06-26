package com.example.chatbot.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.AgentLongTermMemory;
import com.example.chatbot.mapper.AgentLongTermMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MemoryIndexService {

    private final AgentLongTermMemoryMapper memoryMapper;
    private final MemoryProperties properties;

    public List<MemoryIndexItem> loadIndex(Long userId, String projectKey) {
        if (!properties.isEnabled() || !properties.isIndexEnabled() || userId == null) {
            return List.of();
        }
        String resolvedProjectKey = projectKey == null || projectKey.isBlank()
                ? properties.getDefaultProjectKey()
                : projectKey;
        List<AgentLongTermMemory> memories = memoryMapper.selectList(new LambdaQueryWrapper<AgentLongTermMemory>()
                .eq(AgentLongTermMemory::getUserId, userId)
                .eq(AgentLongTermMemory::getStatus, MemoryStatus.ACTIVE.name())
                .and(wrapper -> wrapper
                        .eq(AgentLongTermMemory::getScopeType, MemoryScopeType.USER.name())
                        .eq(AgentLongTermMemory::getScopeKey, String.valueOf(userId))
                        .or()
                        .eq(AgentLongTermMemory::getScopeType, MemoryScopeType.PROJECT.name())
                        .eq(AgentLongTermMemory::getScopeKey, resolvedProjectKey))
                .orderByDesc(AgentLongTermMemory::getUpdatedTime)
                .last("LIMIT " + Math.max(1, properties.getMaxIndexItems() * 3)));
        List<MemoryIndexItem> sorted = memories.stream()
                .sorted(Comparator.comparingInt(this::priority))
                .map(this::toIndexItem)
                .toList();
        return trimIndex(sorted);
    }

    private List<MemoryIndexItem> trimIndex(List<MemoryIndexItem> items) {
        int maxItems = Math.max(1, properties.getMaxIndexItems());
        int maxChars = Math.max(1, properties.getMaxIndexChars());
        List<MemoryIndexItem> result = new ArrayList<>();
        int chars = 0;
        for (MemoryIndexItem item : items) {
            if (result.size() >= maxItems) {
                break;
            }
            int itemChars = safe(item.getName()).length()
                    + safe(item.getDescription()).length()
                    + safe(item.getLoadHint()).length()
                    + 80;
            if (!result.isEmpty() && chars + itemChars > maxChars) {
                break;
            }
            result.add(item);
            chars += itemChars;
        }
        return result;
    }

    private int priority(AgentLongTermMemory memory) {
        String content = (safe(memory.getName()) + " " + safe(memory.getDescription()) + " " + safe(memory.getLoadHint())).toLowerCase();
        if (MemoryScopeType.PROJECT.name().equals(memory.getScopeType())
                && (content.contains("security") || content.contains("pending action") || content.contains("gateway")
                || content.contains("安全") || content.contains("权限") || content.contains("边界"))) {
            return 0;
        }
        if (MemoryScopeType.PROJECT.name().equals(memory.getScopeType())) {
            return 1;
        }
        if (MemoryType.feedback.name().equals(memory.getMemoryType())) {
            return 2;
        }
        if (MemoryType.user.name().equals(memory.getMemoryType())) {
            return 3;
        }
        return 4;
    }

    private MemoryIndexItem toIndexItem(AgentLongTermMemory memory) {
        return MemoryIndexItem.builder()
                .id(memory.getId())
                .scopeType(memory.getScopeType())
                .scopeKey(memory.getScopeKey())
                .memoryType(memory.getMemoryType())
                .name(memory.getName())
                .description(memory.getDescription())
                .loadHint(memory.getLoadHint())
                .build();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
