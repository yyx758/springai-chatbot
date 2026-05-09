package com.example.chatbot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.security.AuthInterceptor;
import com.example.chatbot.service.ChatbotService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@Slf4j
public class ChatbotController {

    private final ChatbotService chatbotService;

    @Autowired
    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    /**
     * 同步对话接口
     */
    @PostMapping("/message")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request, HttpServletRequest httpServletRequest) {
        String userId = resolveUserId(httpServletRequest);
        request.setSessionId(Optional.ofNullable(request.getSessionId())
                .filter(s -> !s.isBlank())
                .orElse(buildSessionId(userId)));
        return ResponseEntity.ok(chatbotService.chat(request, userId));
    }

    /**
     * 流式对话接口
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request, HttpServletRequest httpServletRequest) {
        String userId = resolveUserId(httpServletRequest);
        request.setSessionId(Optional.ofNullable(request.getSessionId())
                .filter(s -> !s.isBlank())
                .orElse(buildSessionId(userId)));

        SseEmitter emitter = new SseEmitter(180_000L);
        try {
            chatbotService.streamChat(request, emitter, userId);
        } catch (Exception e) {
            log.error("初始化流式对话失败", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("error", "流式对话初始化失败: " + e.getMessage())));
            } catch (Exception ignored) {
                // Ignore send failures when connection is already closed.
            }
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 分页查询：按 userId 过滤所有属于该用户的会话
     */
    @GetMapping("/records")
    public ResponseEntity<IPage<ChatRecord>> getChatRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(chatbotService.getChatRecordsPage(page, size, userId));
    }

    /**
     * 个人统计：按 userId 过滤
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSystemStats(HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(chatbotService.getSystemStats(userId));
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "UP", "timestamp", System.currentTimeMillis()));
    }

    /**
     * 加载特定会话历史
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<ChatRecord>> getChatHistory(@PathVariable String sessionId, HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(chatbotService.getChatHistory(sessionId, userId));
    }
    /**
     * 删除指定会话
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId, HttpServletRequest request) {
        String userId = resolveUserId(request);
        chatbotService.deleteSession(sessionId, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "会话已删除"));
    }

    private String resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("未登录或登录已过期");
        }
        return String.valueOf(userId);
    }

    private String buildSessionId(String userId) {
        return userId + "_" + UUID.randomUUID();
    }
}
