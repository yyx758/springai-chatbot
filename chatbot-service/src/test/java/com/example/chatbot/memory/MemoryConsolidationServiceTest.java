package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryConsolidationServiceTest {

    @Test
    @DisplayName("Consolidation preview suggests archiving duplicate content hashes")
    void consolidationPreviewFindsDuplicates() {
        LongTermMemoryService memoryService = mock(LongTermMemoryService.class);
        when(memoryService.list(7L, "PROJECT", "springaI-chatbot", null, "ACTIVE", null, 200)).thenReturn(List.of(
                memory(1L, "hash"),
                memory(2L, "hash")
        ));
        MemoryConsolidationService service = new MemoryConsolidationService(memoryService);

        var preview = service.preview(7L, "PROJECT", "springaI-chatbot");

        assertEquals(1, preview.getActions().size());
        assertEquals(List.of(2L), preview.getActions().get(0).getArchiveIds());
    }

    @Test
    @DisplayName("Consolidation apply archives only confirmed duplicate ids")
    void consolidationApplyArchivesConfirmedIds() {
        LongTermMemoryService memoryService = mock(LongTermMemoryService.class);
        MemoryConsolidationService service = new MemoryConsolidationService(memoryService);
        var preview = MemoryConsolidationService.ConsolidationPreview.builder()
                .actions(List.of(MemoryConsolidationService.ConsolidationAction.builder()
                        .action("ARCHIVE_DUPLICATE")
                        .keepId(1L)
                        .archiveIds(List.of(2L))
                        .build()))
                .build();

        service.apply(7L, preview);

        verify(memoryService).archive(7L, 2L);
    }

    private AgentLongTermMemory memory(Long id, String hash) {
        return AgentLongTermMemory.builder().id(id).contentHash(hash).build();
    }
}
