package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MemoryConsolidationService {

    private final LongTermMemoryService memoryService;

    public ConsolidationPreview preview(Long userId, String scopeType, String scopeKey) {
        List<AgentLongTermMemory> memories = memoryService.list(userId, scopeType, scopeKey, null, "ACTIVE", null, 200);
        Map<String, List<AgentLongTermMemory>> byHash = new HashMap<>();
        for (AgentLongTermMemory memory : memories) {
            if (memory.getContentHash() != null && !memory.getContentHash().isBlank()) {
                byHash.computeIfAbsent(memory.getContentHash(), ignored -> new ArrayList<>()).add(memory);
            }
        }
        List<ConsolidationAction> actions = new ArrayList<>();
        for (List<AgentLongTermMemory> group : byHash.values()) {
            if (group.size() <= 1) {
                continue;
            }
            AgentLongTermMemory keep = group.get(0);
            List<Long> archiveIds = group.stream().skip(1).map(AgentLongTermMemory::getId).toList();
            actions.add(ConsolidationAction.builder()
                    .action("ARCHIVE_DUPLICATE")
                    .keepId(keep.getId())
                    .archiveIds(archiveIds)
                    .reason("same content hash")
                    .build());
        }
        return ConsolidationPreview.builder()
                .actions(actions)
                .message(actions.isEmpty() ? "No duplicate active memory found." : "Duplicate memory can be archived after confirmation.")
                .build();
    }

    public ConsolidationPreview apply(Long userId, ConsolidationPreview preview) {
        if (preview == null || preview.getActions() == null) {
            return ConsolidationPreview.builder().actions(List.of()).message("No actions applied.").build();
        }
        for (ConsolidationAction action : preview.getActions()) {
            if (!"ARCHIVE_DUPLICATE".equals(action.getAction()) || action.getArchiveIds() == null) {
                continue;
            }
            for (Long archiveId : action.getArchiveIds()) {
                memoryService.archive(userId, archiveId);
            }
        }
        return ConsolidationPreview.builder()
                .actions(preview.getActions())
                .message("Consolidation actions applied.")
                .build();
    }

    @Data
    @Builder
    public static class ConsolidationPreview {
        private List<ConsolidationAction> actions;
        private String message;
    }

    @Data
    @Builder
    public static class ConsolidationAction {
        private String action;
        private Long keepId;
        private List<Long> archiveIds;
        private String reason;
    }
}
