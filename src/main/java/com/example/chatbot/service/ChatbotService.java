package com.example.chatbot.service;

import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 智能客服核心服务类
 * 基于Spring AI + MyBatis实现智能对话
 * 
 * @author yyvb
 */
@Service
public class ChatbotService {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotService.class);

    private final ChatClient chatClient;
    private final ChatRecordMapper chatRecordMapper;

    @Value("${app.chatbot.system-prompt:你是一个专业的智能客服助手，请友好、专业地回答用户的问题。}")
    private String systemPrompt;

    @Value("${app.chatbot.max-history:5}")
    private int maxHistory;

    @Value("${app.chatbot.timeout:30000}")
    private long timeout;

    @Autowired
    public ChatbotService(ChatClient.Builder chatClientBuilder, ChatRecordMapper chatRecordMapper) {
        this.chatClient = chatClientBuilder.build();
        this.chatRecordMapper = chatRecordMapper;
    }

    /**
     * 处理用户聊天请求
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 使用 isBlank() 同时检查：是否长度为 0，或者是否只包含空白字符
            if (request.getMessage() == null || request.getMessage().isBlank()) {
                return ChatResponse.error("消息内容不能为空", request.getSessionId());
            }

            String userInput = request.getMessage().trim();
            
            // 构建对话上下文
            List<Message> messages = buildConversationContext(request.getSessionId(), userInput);
            
            // 调用AI获取响应
            String aiResponse = callAIWithTimeout(messages);
            
            long responseTime = System.currentTimeMillis() - startTime;
            
            // 保存聊天记录
            saveChatRecord(request.getSessionId(), userInput, aiResponse);
            
            logger.info("会话[{}]处理完成，耗时:{}ms", request.getSessionId(), responseTime);
            
            return new ChatResponse(aiResponse, request.getSessionId(), responseTime);
            
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            logger.error("处理聊天请求时发生错误，会话ID: {}, 耗时: {}ms", request.getSessionId(), responseTime, e);
            return ChatResponse.error("服务暂时不可用，请稍后重试", request.getSessionId());
        }
    }



    /**
     * 构建对话上下文，包含历史记录
     */
    private List<Message> buildConversationContext(String sessionId, String userInput) {
        List<Message> messages = new ArrayList<>();
        
        // 添加系统提示
        messages.add(new SystemMessage(systemPrompt));
        
        // 获取历史对话记录
        List<ChatRecord> history = getRecentChatHistory(sessionId, maxHistory);
        
        // 添加历史对话到上下文（按时间顺序）
        for (ChatRecord record : history) {
            messages.add(new UserMessage(record.getUserMessage()));
            messages.add(new AssistantMessage(record.getBotResponse()));
        }
        
        // 添加当前用户输入
        messages.add(new UserMessage(userInput));
        
        return messages;
    }

    /**
     * 带超时的AI调用
     */
    private String callAIWithTimeout(List<Message> messages) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return chatClient.prompt()
                    .messages(messages)
                    .call()
                    .content();
            } catch (Exception e) {
                logger.error("调用Spring AI服务失败", e);
                throw new RuntimeException("AI服务调用失败", e);
            }
        });

        return future.get(timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * 保存聊天记录到数据库
     */
    private void saveChatRecord(String sessionId, String userMessage, String botResponse) {
        try {
            ChatRecord record = new ChatRecord(userMessage, botResponse, sessionId);
            chatRecordMapper.insert(record);
        } catch (Exception e) {
            logger.error("保存聊天记录失败，会话ID: {}", sessionId, e);
        }
    }

    /**
     * 获取最近的聊天历史记录
     */
    private List<ChatRecord> getRecentChatHistory(String sessionId, int limit) {
        try {
            return chatRecordMapper.selectBySessionId(sessionId);
        } catch (Exception e) {
            logger.error("获取聊天历史失败，会话ID: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取会话历史记录
     */
    public List<ChatRecord> getChatHistory(String sessionId) {
        return chatRecordMapper.selectBySessionId(sessionId);
    }

    /**
     * 获取系统统计信息
     */
    public Map<String, Object> getSystemStats() {
        try {
            long totalChats = chatRecordMapper.count();
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalChats", totalChats);
            stats.put("status", "运行正常");
            stats.put("timestamp", LocalDateTime.now());
            
            return stats;
        } catch (Exception e) {
            logger.error("获取系统统计信息失败", e);
            Map<String, Object> errorStats = new HashMap<>();
            errorStats.put("status", "获取统计信息失败");
            errorStats.put("error", e.getMessage());
            errorStats.put("timestamp", LocalDateTime.now());
            return errorStats;
        }
    }

    /**
     * 分页获取聊天记录
     */
    public List<ChatRecord> getChatRecords(int page, int size) {
        int offset = page * size;
        return chatRecordMapper.selectByPage(offset, size);
    }

    /**
     * 获取总记录数
     */
    public long getTotalCount() {
        return chatRecordMapper.count();
    }

    /**
     * 流式处理聊天请求，通过 SseEmitter 逐块推送
     */
    public void streamChat(ChatRequest request, SseEmitter emitter) {
        String userInput = request.getMessage().trim();
        List<Message> messages = buildConversationContext(request.getSessionId(), userInput);
        StringBuilder fullResponse = new StringBuilder();

        // 如图二所示，使用 .stream().content()
        chatClient.prompt()
                .messages(messages)
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            try {
                                if (chunk != null) {
                                    fullResponse.append(chunk);
                                    // 【关键修改】将文本包装为 JSON 格式推送，防止 Markdown 的换行符被 SSE 协议截断
                                    emitter.send(SseEmitter.event().data(Map.of("content", chunk)));
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            logger.error("流式AI调用失败，会话ID: {}", request.getSessionId(), error);
                            emitter.completeWithError(error);
                        },
                        () -> {
                            try {
                                saveChatRecord(request.getSessionId(), userInput, fullResponse.toString());
                                // 发送结束标识符
                                emitter.send(SseEmitter.event().name("done").data(Map.of("content", "[DONE]")));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }
                );
    }
} 