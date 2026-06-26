package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MemorySelectionPreview {
    private List<MemoryIndexItem> index;
    private List<Long> selectedIds;
    private List<AgentLongTermMemory> details;
    private String prompt;
    private boolean fallback;
    private String reason;
}
