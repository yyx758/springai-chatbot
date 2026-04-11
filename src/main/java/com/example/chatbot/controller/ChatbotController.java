package com.example.chatbot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.service.ChatbotService;
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
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        request.setSessionId(Optional.ofNullable(request.getSessionId())
                .filter(s -> !s.isBlank())
                .orElse(UUID.randomUUID().toString()));
        return ResponseEntity.ok(chatbotService.chat(request));
    }

    /**
     * 流式对话接口
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        request.setSessionId(Optional.ofNullable(request.getSessionId())
                .filter(s -> !s.isBlank())
                .orElse(UUID.randomUUID().toString()));

        SseEmitter emitter = new SseEmitter(180_000L);
        chatbotService.streamChat(request, emitter);
        return emitter;
    }

    /**
     * 分页查询：按 userId 过滤所有属于该用户的会话
     */
    @GetMapping("/records")
    public ResponseEntity<IPage<ChatRecord>> getChatRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String userId) {
        return ResponseEntity.ok(chatbotService.getChatRecordsPage(page, size, userId));
    }

    /**
     * 个人统计：按 userId 过滤
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSystemStats(@RequestParam(required = false) String userId) {
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
    public ResponseEntity<List<ChatRecord>> getChatHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatbotService.getChatHistory(sessionId));
    }
}