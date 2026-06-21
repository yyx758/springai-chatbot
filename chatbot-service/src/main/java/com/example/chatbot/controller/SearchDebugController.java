package com.example.chatbot.controller;

import com.example.chatbot.rag.SearchDebugResponse;
import com.example.chatbot.rag.HybridSearchService;
import com.example.chatbot.security.AuthInterceptor;
import com.example.chatbot.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/debug")
@RequiredArgsConstructor
@RequireRole("ADMIN")
public class SearchDebugController {

    private final HybridSearchService hybridSearchService;

    @GetMapping("/search")
    public ResponseEntity<SearchDebugResponse> search(@RequestParam(required = false) Long userId,
                                                      @RequestParam String query,
                                                      @RequestParam(defaultValue = "3") int topK,
                                                      HttpServletRequest request) {
        Long targetUserId = userId != null ? userId : resolveUserId(request);
        return ResponseEntity.ok(hybridSearchService.debugSearch(targetUserId, query, topK));
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("未登录或登录已过期");
        }
        return Long.valueOf(String.valueOf(userId));
    }
}
