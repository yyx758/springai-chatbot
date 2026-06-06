package com.example.chatbot.controller;

import com.example.chatbot.agent.AgentService;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat/agent")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentService agentService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgent(@RequestBody ChatRequest request, HttpServletRequest httpServletRequest) {
        String userId = resolveUserId(httpServletRequest);
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = buildSessionId(userId);
        }
        request.setSessionId(sessionId);

        SseEmitter emitter = new SseEmitter(180_000L);
        try {
            agentService.streamAgent(request, emitter, userId);
        } catch (Exception e) {
            log.error("Agent stream initialization failed", e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("error", "Agent stream initialization failed: " + e.getMessage())));
            } catch (Exception ignored) {
            }
            emitter.complete();
        }
        return emitter;
    }

    private String resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return String.valueOf(userId);
    }

    private String buildSessionId(String userId) {
        return userId + "_" + UUID.randomUUID();
    }
}
