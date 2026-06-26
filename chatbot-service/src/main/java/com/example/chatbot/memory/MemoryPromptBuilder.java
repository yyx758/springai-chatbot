package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MemoryPromptBuilder {

    public String buildIndexPrompt(List<MemoryIndexItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Long-term memory index:\n");
        for (MemoryIndexItem item : items) {
            builder.append("- id=").append(item.getId())
                    .append(" scope=").append(safe(item.getScopeType()))
                    .append(" type=").append(safe(item.getMemoryType()))
                    .append(" name=\"").append(safe(item.getName())).append("\"\n")
                    .append("  description=\"").append(safe(item.getDescription())).append("\"");
            if (item.getLoadHint() != null && !item.getLoadHint().isBlank()) {
                builder.append("\n  load_hint=\"").append(item.getLoadHint()).append("\"");
            }
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    public String buildDetailPrompt(List<AgentLongTermMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Loaded long-term memory detail:\n");
        for (AgentLongTermMemory memory : memories) {
            builder.append("[memory id=").append(memory.getId())
                    .append(" scope=").append(safe(memory.getScopeType()))
                    .append(" type=").append(safe(memory.getMemoryType()))
                    .append("]\n")
                    .append(safe(memory.getContent()).trim())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
