package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import com.example.chatbot.entity.AgentMemoryEvent;
import com.example.chatbot.mapper.AgentLongTermMemoryMapper;
import com.example.chatbot.mapper.AgentMemoryEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryServiceTest {

    @Mock
    private AgentLongTermMemoryMapper memoryMapper;

    @Mock
    private AgentMemoryEventMapper eventMapper;

    private LongTermMemoryService service;

    @BeforeEach
    void setUp() {
        MemoryProperties properties = new MemoryProperties();
        service = new LongTermMemoryService(memoryMapper, eventMapper, new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("Create memory uses current user id and writes audit event")
    void createUsesCurrentUserAndWritesAudit() {
        LongTermMemoryRequest request = new LongTermMemoryRequest();
        request.setScopeType("PROJECT");
        request.setScopeKey("springaI-chatbot");
        request.setMemoryType("project");
        request.setName("Gateway production entry");
        request.setDescription("Production traffic uses gateway.");
        request.setContent("Production user traffic must go through Gateway :9000.");
        request.setLoadHint("Load for deployment changes.");

        AgentLongTermMemory memory = service.create(7L, request);

        assertEquals(7L, memory.getUserId());
        assertEquals("PROJECT", memory.getScopeType());
        assertEquals("ACTIVE", memory.getStatus());
        assertEquals("MANUAL", memory.getSourceType());
        verify(memoryMapper).insert(any(AgentLongTermMemory.class));
        ArgumentCaptor<AgentMemoryEvent> eventCaptor = ArgumentCaptor.forClass(AgentMemoryEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals("CREATE", eventCaptor.getValue().getEventType());
        assertEquals(7L, eventCaptor.getValue().getUserId());
    }

    @Test
    @DisplayName("Create rejects content that looks like secrets")
    void createRejectsSecretContent() {
        LongTermMemoryRequest request = new LongTermMemoryRequest();
        request.setName("secret");
        request.setDescription("bad");
        request.setContent("DEEPSEEK_API_KEY=sk-demo");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(7L, request));

        assertTrue(error.getMessage().contains("secret"));
    }

    @Test
    @DisplayName("User scoped memory forces scope key to current user id")
    void userScopeForcesScopeKey() {
        LongTermMemoryRequest request = new LongTermMemoryRequest();
        request.setScopeType("USER");
        request.setScopeKey("8");
        request.setMemoryType("feedback");
        request.setName("Response style");
        request.setDescription("Direct implementation preferred.");
        request.setContent("User prefers direct implementation and verification.");

        AgentLongTermMemory memory = service.create(7L, request);

        assertEquals("7", memory.getScopeKey());
    }

    @Test
    @DisplayName("Archive only updates memory owned by current user")
    void archiveUsesCurrentUserIsolation() {
        when(memoryMapper.selectOne(any())).thenReturn(AgentLongTermMemory.builder()
                .id(12L)
                .userId(7L)
                .status("ACTIVE")
                .name("memory")
                .build());

        AgentLongTermMemory archived = service.archive(7L, 12L);

        assertEquals("ARCHIVED", archived.getStatus());
        verify(memoryMapper).updateById(archived);
        verify(eventMapper).insert(any(AgentMemoryEvent.class));
    }
}
