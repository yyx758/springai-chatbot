package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import com.example.chatbot.mapper.AgentLongTermMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryIndexServiceTest {

    @Mock
    private AgentLongTermMemoryMapper memoryMapper;

    private MemoryProperties properties;
    private MemoryIndexService service;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setMaxIndexItems(2);
        service = new MemoryIndexService(memoryMapper, properties);
    }

    @Test
    @DisplayName("Active project safety memory is prioritized in index")
    void projectSafetyMemoryIsPrioritized() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory(1L, "USER", "feedback", "Style", "User likes concise replies.", ""),
                memory(2L, "PROJECT", "project", "Pending Action boundary", "安全边界: writes require Pending Action.", "")
        ));

        List<MemoryIndexItem> index = service.loadIndex(7L, "springaI-chatbot");

        assertEquals(2L, index.get(0).getId());
        assertEquals(2, index.size());
    }

    private AgentLongTermMemory memory(Long id, String scope, String type, String name, String description, String loadHint) {
        return AgentLongTermMemory.builder()
                .id(id)
                .scopeType(scope)
                .scopeKey(scope.equals("USER") ? "7" : "springaI-chatbot")
                .memoryType(type)
                .status("ACTIVE")
                .name(name)
                .description(description)
                .loadHint(loadHint)
                .build();
    }
}
