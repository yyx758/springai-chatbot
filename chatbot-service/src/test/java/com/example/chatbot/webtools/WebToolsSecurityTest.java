package com.example.chatbot.webtools;

import com.example.chatbot.workspace.AgentWorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WebToolsSecurityTest {

    private WebToolService service;

    @BeforeEach
    void setUp() {
        WebToolsProperties properties = new WebToolsProperties();
        service = new WebToolService(properties, new ObjectMapper(), Mockito.mock(AgentWorkspaceService.class));
    }

    @Test
    @DisplayName("Web fetch refuses localhost URLs")
    void rejectsLocalhost() {
        assertThrows(ResponseStatusException.class, () -> service.validateExternalUrl("http://localhost:8080/admin"));
    }

    @Test
    @DisplayName("Web fetch refuses private network URLs")
    void rejectsPrivateNetwork() {
        assertThrows(ResponseStatusException.class, () -> service.validateExternalUrl("http://192.168.1.10/page"));
    }

    @Test
    @DisplayName("Web tools stay unavailable while disabled")
    void disabledToolsDoNotSearch() {
        assertThrows(ResponseStatusException.class, () -> service.search("spring ai", 3));
    }
}
