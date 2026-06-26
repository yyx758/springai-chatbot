package com.example.chatbot.controller;

import com.example.chatbot.agent.review.conversation.CodeReviewConversationContext;
import com.example.chatbot.agent.review.conversation.CodeReviewConversationOrchestrator;
import com.example.chatbot.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/agent/conversation")
@RequiredArgsConstructor
public class CodeReviewConversationController {

    private final CodeReviewConversationOrchestrator orchestrator;

    @GetMapping("/context")
    public CodeReviewConversationContext getContext(@RequestParam String sessionId,
                                                    HttpServletRequest request) {
        return orchestrator.getContext(resolveUserId(request), sessionId);
    }

    @PostMapping("/context/active-run")
    public CodeReviewConversationContext setActiveRun(@RequestBody ActiveRunRequest body,
                                                      HttpServletRequest request) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return orchestrator.setActiveReviewRun(resolveUserId(request), body.getSessionId(), body.getRunId());
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return Long.valueOf(String.valueOf(userId));
    }

    @Data
    public static class ActiveRunRequest {
        private String sessionId;
        private String runId;
    }
}
