package com.example.chatbot.controller;

import com.example.chatbot.agent.AgentPendingActionService;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/chat/agent/actions")
@RequiredArgsConstructor
public class AgentActionController {

    private final AgentPendingActionService pendingActionService;

    @PostMapping("/{actionId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable Long actionId, HttpServletRequest request) {
        Long userId = resolveUserId(request);
        AgentPendingAction action = pendingActionService.confirm(userId, actionId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "actionId", action.getId(),
                "status", action.getStatus(),
                "resultSummary", action.getResultSummary() == null ? "" : action.getResultSummary()
        ));
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return Long.valueOf(String.valueOf(userId));
    }
}
