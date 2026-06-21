package com.example.chatbot.controller;

import com.example.chatbot.agent.AgentPendingActionService;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/chat/agent/actions")
@RequiredArgsConstructor
public class AgentActionController {

    private final AgentPendingActionService pendingActionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String sessionId,
                                                    @RequestParam(required = false) String actionType,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "20") Integer limit,
                                                    HttpServletRequest request) {
        Long userId = resolveUserId(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "actions", pendingActionService.listActions(userId, sessionId, actionType, status, limit == null ? 20 : limit).stream()
                        .map(pendingActionService::toActionCard)
                        .toList()
        ));
    }

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

    @PostMapping("/{actionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long actionId, HttpServletRequest request) {
        Long userId = resolveUserId(request);
        AgentPendingAction action = pendingActionService.cancel(userId, actionId);
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
