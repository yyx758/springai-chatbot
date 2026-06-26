package com.example.chatbot.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MemoryImportServiceTest {

    @Test
    @DisplayName("Import preview creates active project memory drafts from project notes")
    void importPreviewCreatesDrafts() {
        MemoryProperties properties = new MemoryProperties();
        MemoryImportService service = new MemoryImportService(mock(LongTermMemoryService.class), properties);

        var drafts = service.preview("生产环境用户入口只走 Gateway :9000。", "PROJECT", "springaI-chatbot");

        assertFalse(drafts.isEmpty());
        assertEquals("PROJECT", drafts.get(0).getScopeType());
        assertEquals("ACTIVE", drafts.get(0).getStatus());
    }
}
