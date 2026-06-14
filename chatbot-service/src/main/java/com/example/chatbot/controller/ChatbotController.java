package com.example.chatbot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.security.AuthInterceptor;
import com.example.chatbot.service.ChatbotService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request, HttpServletRequest httpServletRequest) {
        String userId = resolveUserId(httpServletRequest);
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = buildSessionId(userId);
        }
        request.setSessionId(sessionId);

        SseEmitter emitter = new SseEmitter(180_000L);
        try {
            chatbotService.streamChat(request, emitter, userId);
        } catch (Exception e) {
            log.error("流式对话初始化失败", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("error", "流式对话初始化失败: " + e.getMessage())));
            } catch (Exception ignored) {}
            emitter.complete();
        }
        return emitter;
    }

    @PostMapping(value = "/stream/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChatWithImage(
            @RequestPart("message") String message,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "useRag", required = false) Boolean useRag,
            @RequestParam(value = "ragTopK", required = false) Integer ragTopK,
            HttpServletRequest httpServletRequest) {

        String userId = resolveUserId(httpServletRequest);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = buildSessionId(userId);
        }

        ChatRequest request = ChatRequest.builder()
                .message(message)
                .sessionId(sessionId)
                .model(model)
                .useRag(useRag)
                .ragTopK(ragTopK)
                .build();

        SseEmitter emitter = new SseEmitter(180_000L);
        try {
            chatbotService.streamChatWithImage(request, image, emitter, userId);
        } catch (Exception e) {
            log.error("多模态流式对话初始化失败", e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("error", "多模态流式对话初始化失败: " + e.getMessage())));
            } catch (Exception ignored) {}
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 多模态流式对话（通过 fileKey 从文件服务获取图片）
     * 新方式：前端先上传图片到 file-service，再传 fileKey 过来
     */
    @PostMapping(value = "/stream/filekey", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChatWithFileKey(@RequestBody Map<String, Object> body,
                                            HttpServletRequest httpServletRequest) {
        String userId = resolveUserId(httpServletRequest);
        String message = (String) body.get("message");
        String imageFileKey = (String) body.get("imageFileKey");
        String sessionId = (String) body.get("sessionId");
        String model = (String) body.get("model");
        Boolean useRag = (Boolean) body.get("useRag");
        Integer ragTopK = body.get("ragTopK") != null ? ((Number) body.get("ragTopK")).intValue() : null;

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = buildSessionId(userId);
        }

        ChatRequest request = ChatRequest.builder()
                .message(message)
                .sessionId(sessionId)
                .model(model)
                .useRag(useRag)
                .ragTopK(ragTopK)
                .build();

        SseEmitter emitter = new SseEmitter(180_000L);
        try {
            chatbotService.streamChatWithFileKey(request, imageFileKey, emitter, userId);
        } catch (Exception e) {
            log.error("多模态流式对话初始化失败", e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("error", "多模态流式对话初始化失败: " + e.getMessage())));
            } catch (Exception ignored) {}
            emitter.complete();
        }
        return emitter;
    }

    @GetMapping("/records")
    public ResponseEntity<IPage<ChatRecord>> getChatRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(chatbotService.getChatRecordsPage(page, size, userId));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSystemStats(HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(chatbotService.getSystemStats(userId));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "UP", "timestamp", System.currentTimeMillis()));
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<Map<String, Object>>> getChatHistory(@PathVariable String sessionId, HttpServletRequest request) {
        String userId = resolveUserId(request);
        List<ChatRecord> records = chatbotService.getChatHistory(sessionId, userId);
        // imageData 被 @JsonIgnore 忽略，手动构造包含图片数据的响应
        List<Map<String, Object>> result = records.stream().map(r -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", r.getId());
            map.put("userMessage", r.getUserMessage());
            map.put("botResponse", r.getBotResponse());
            map.put("imageData", r.getImageData());
            map.put("createdTime", r.getCreatedTime());
            map.put("sessionId", r.getSessionId());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

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
