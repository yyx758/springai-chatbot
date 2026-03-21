package com.example.chatbot.controller;

import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.service.ChatbotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能客服控制器
 * 提供聊天接口和管理功能
 * 
 * @author yyvb
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatbotController {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotController.class);

    private final ChatbotService chatbotService;

    @Autowired
    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    /**
     * 处理聊天请求
     */
    @PostMapping("/message")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            // 如果没有会话ID，生成一个新的
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                request.setSessionId(UUID.randomUUID().toString());
            }

            logger.info("收到聊天请求，会话ID: {}, 消息: {}", request.getSessionId(), request.getMessage());

            ChatResponse response = chatbotService.chat(request);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("处理聊天请求时发生异常", e);
            ChatResponse errorResponse = ChatResponse.error("系统内部错误", request.getSessionId());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * 获取会话历史记录
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<ChatRecord>> getChatHistory(@PathVariable String sessionId) {
        try {
            List<ChatRecord> history = chatbotService.getChatHistory(sessionId);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            logger.error("获取聊天历史失败，会话ID: {}", sessionId, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 获取系统统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSystemStats() {
        try {
            Map<String, Object> stats = chatbotService.getSystemStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("获取系统统计信息失败", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "运行正常",
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 分页查询聊天记录
     */
    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> getChatRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            List<ChatRecord> records = chatbotService.getChatRecords(page, size);
            long total = chatbotService.getTotalCount();
            
            Map<String, Object> result = Map.of(
                "records", records,
                "total", total,
                "page", page,
                "size", size,
                "totalPages", (total + size - 1) / size
            );
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("分页查询聊天记录失败", e);
            return ResponseEntity.status(500).build();
        }
    }
} 