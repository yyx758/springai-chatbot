package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySelectionServiceTest {

    @Test
    @DisplayName("Selection preview only loads details for ids selected from index")
    void selectionPreviewLoadsSelectedDetails() {
        LongTermMemoryService memoryService = mock(LongTermMemoryService.class);
        MemoryIndexService indexService = mock(MemoryIndexService.class);
        MemoryProperties properties = new MemoryProperties();
        properties.setMaxSelectedDetails(1);
        when(indexService.loadIndex(7L, "springaI-chatbot")).thenReturn(List.of(
                MemoryIndexItem.builder().id(18L).memoryType("project").name("Gateway production entry")
                        .description("Production traffic must go through Gateway :9000.").build(),
                MemoryIndexItem.builder().id(19L).memoryType("reference").name("Docs").description("docs/agent").build()
        ));
        when(memoryService.loadActiveDetails(7L, List.of(18L), false)).thenReturn(List.of(
                AgentLongTermMemory.builder().id(18L).scopeType("PROJECT").memoryType("project")
                        .content("Production user traffic must go through Gateway :9000.").build()
        ));
        MemorySelectionService service = new MemorySelectionService(
                memoryService, indexService, new MemoryPromptBuilder(), properties);

        MemorySelectionPreview preview = service.selectDetailPreview(7L, "springaI-chatbot", "生产环境入口怎么配置");

        assertEquals(List.of(18L), preview.getSelectedIds());
        assertTrue(preview.getPrompt().contains("Gateway :9000"));
    }
}
