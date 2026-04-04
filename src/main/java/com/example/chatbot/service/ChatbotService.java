package com.example.chatbot.service;

import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
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

    @Autowired
    private RedisTemplate<String, Object> redisTemplate; // 注入 RedisTemplate

    @Autowired
    private ObjectMapper objectMapper; // 注入 ObjectMapper 用于类型转换
//@Value是把外部配置文件（比如 application.yml 或 application.properties）里的值，“注入”到代码的变量中。后面是默认值

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
            asyncSaveChatRecord(request.getSessionId(), userInput, aiResponse);
            
            logger.info("会话[{}]处理完成，耗时:{}ms", request.getSessionId(), responseTime);
            
            return new ChatResponse(aiResponse, request.getSessionId(), responseTime);
            
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            logger.error("处理聊天请求时发生错误，会话ID: {}, 耗时: {}ms", request.getSessionId(), responseTime, e);
            return ChatResponse.error("服务暂时不可用，请稍后重试", request.getSessionId());
        }
    }




    /**
     *  改造组装上下文逻辑，优先从 Redis List 中读取
     */
    private List<Message> buildConversationContext(String sessionId, String userInput) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        String redisKey = "chat:history:" + sessionId;

        // 从 Redis 的 List 中获取所有记录 (0 到 -1 代表全部)
        List<Object> cachedRecords = redisTemplate.opsForList().range(redisKey, 0, -1);

        List<ChatRecord> history = new ArrayList<>();

        if (cachedRecords != null && !cachedRecords.isEmpty()) {
            // Redis 命中，进行类型转换
            for (Object obj : cachedRecords) {
                history.add(objectMapper.convertValue(obj, ChatRecord.class));
            }
        } else {
            // Redis 未命中，从 MySQL 兜底查询，并可以考虑在此处回写 Redis
            history = getRecentChatHistory(sessionId, maxHistory);

            // 【核心修改点：回写 Redis】
            if (history != null && !history.isEmpty()) {
                // 将从数据库查出的历史记录批量写入 Redis 列表
                redisTemplate.opsForList().rightPushAll(redisKey, history.toArray());
                // 务必设置过期时间，防止冷数据永久占用内存
                redisTemplate.expire(redisKey, 2, TimeUnit.HOURS);
            }
        }


        for (ChatRecord record : history) {
            messages.add(new UserMessage(record.getUserMessage()));
            messages.add(new AssistantMessage(record.getBotResponse()));
        }

        messages.add(new UserMessage(userInput));
        return messages;
    }

    /**
     * 保存聊天记录到数据库
     */
    @Async // 异步执行，不阻塞主流程
    public void asyncSaveChatRecord(String sessionId, String userMessage, String botResponse) {
        try {
            ChatRecord record = new ChatRecord(userMessage, botResponse, sessionId);
            String redisKey = "chat:history:" + sessionId;

            // 1. 存入 Redis List (将新记录推入列表右侧)
            redisTemplate.opsForList().rightPush(redisKey, record);

            // 2. 裁剪 List，只保留最近的 maxHistory 条记录，旧记录会被抛弃
            redisTemplate.opsForList().trim(redisKey, -maxHistory, -1);

            // 3. 设置过期时间（例如 2 小时无对话自动清除 Redis 缓存，释放内存）
            redisTemplate.expire(redisKey, 2, TimeUnit.HOURS);

            // 4. 持久化到 MySQL
            chatRecordMapper.insert(record);
        } catch (Exception e) {
            logger.error("异步保存聊天记录失败 ID: {}", sessionId, e);
        }
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
                                asyncSaveChatRecord(request.getSessionId(), userInput, fullResponse.toString());
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