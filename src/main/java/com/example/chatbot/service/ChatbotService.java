package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 智能客服核心服务类 (优化版)
 * 集成 Spring AI + MyBatis-Plus + Redis
 */
@Service
@Slf4j
public class ChatbotService {

    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;
    private final ChatRecordMapper chatRecordMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.chatbot.system-prompt}")
    private String systemPrompt;

    @Value("${app.chatbot.max-history:5}")
    private int maxHistory;

    @Autowired
    public ChatbotService(OpenAiChatModel openAiChatModel, OllamaChatModel ollamaChatModel,
                          ChatRecordMapper chatRecordMapper, RedisTemplate<String, Object> redisTemplate,
                          ObjectMapper objectMapper) {
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.chatRecordMapper = chatRecordMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 解决识别问题：将 ChatRequest 中的 model 字符串转换为具体的 ChatModel Bean
     */
    private ChatModel getChatModel(String preferredModel) {
        if ("ollama".equalsIgnoreCase(preferredModel)) {
            return ollamaChatModel;
        }
        // 默认返回官方模型 (DeepSeek/OpenAI)
        return openAiChatModel;
    }

    /**
     * 处理同步聊天请求
     */
    public ChatResponse chat(ChatRequest request) {
        String sessionId = request.getSessionId();
        String userInput = request.getMessage();
        long startTime = System.currentTimeMillis();

        try {
            if (userInput == null || userInput.isBlank()) {
                return ChatResponse.builder().success(false).error("消息不能为空").sessionId(sessionId).build();
            }

            List<Message> messages = buildConversationContext(sessionId, userInput);

            // 获取用户指定的模型
            ChatModel targetModel = getChatModel(request.getModel());

            // 执行带降级的 AI 调用
            String aiResponse;
            try {
                aiResponse = targetModel.call(new Prompt(messages)).getResult().getOutput().getContent();
            } catch (Exception e) {
                log.warn("首选模型异常，自动切换到 Ollama 兜底: {}", e.getMessage());
                aiResponse = ollamaChatModel.call(new Prompt(messages)).getResult().getOutput().getContent();
            }

            asyncSaveChatRecord(sessionId, userInput, aiResponse);

            return ChatResponse.builder()
                    .message(aiResponse)
                    .sessionId(sessionId)
                    .responseTime(System.currentTimeMillis() - startTime)
                    .success(true).build();

        } catch (Exception e) {
            log.error("会话 {} 聊天失败", sessionId, e);
            return ChatResponse.builder().success(false).error("AI服务拥堵").sessionId(sessionId).build();
        }
    }

    /**
     * 构建上下文：优先 Redis，无则 MP 查询 MySQL
     */
    private List<Message> buildConversationContext(String sessionId, String userInput) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        String redisKey = "chat:history:" + sessionId;
        List<Object> cached = redisTemplate.opsForList().range(redisKey, 0, -1);

        List<ChatRecord> history;
        if (cached != null && !cached.isEmpty()) {
            history = cached.stream()
                    .map(obj -> objectMapper.convertValue(obj, ChatRecord.class))
                    .collect(Collectors.toList());
        } else {
            // 使用 MyBatis-Plus Lambda 查询，代替原有的手写 SQL
            history = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                    .eq(ChatRecord::getSessionId, sessionId)
                    .orderByAsc(ChatRecord::getCreatedTime)
                    .last("LIMIT " + maxHistory));

            if (!history.isEmpty()) {
                redisTemplate.opsForList().rightPushAll(redisKey, history.toArray());
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
     * 异步保存记录 (MyBatis-Plus 自动 insert)
     */
    @Async
    public void asyncSaveChatRecord(String sessionId, String userMessage, String botResponse) {
        try {
            ChatRecord record = ChatRecord.builder()
                    .sessionId(sessionId)
                    .userMessage(userMessage)
                    .botResponse(botResponse)
                    .createdTime(LocalDateTime.now())
                    .build();

            // 1. MP 自动持久化
            chatRecordMapper.insert(record);

            // 2. 更新 Redis 缓存
            String redisKey = "chat:history:" + sessionId;
            redisTemplate.opsForList().rightPush(redisKey, record);
            redisTemplate.opsForList().trim(redisKey, -maxHistory, -1);
            redisTemplate.expire(redisKey, 2, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("保存聊天记录失败: {}", sessionId, e);
        }
    }

    /**
     * 流式聊天请求 (SSE)
     */
    public void streamChat(ChatRequest request, SseEmitter emitter) {
        String userInput = request.getMessage().trim();
        List<Message> messages = buildConversationContext(request.getSessionId(), userInput);
        StringBuilder fullResponse = new StringBuilder();

        try {
            emitter.send(SseEmitter.event().data(Map.of("content", ""))); // 占位发包防止超时
        } catch (IOException e) {
            log.error("初始化发包失败", e);
        }

        ChatModel targetModel = getChatModel(request.getModel());

        // 统一流式模型处理逻辑与降级
        Flux<String> chatFlux = targetModel.stream(new Prompt(messages))
                .map(res -> res.getResult().getOutput().getContent())
                .onErrorResume(e -> {
                    log.warn("流式主接口故障，切换到 Ollama: {}", e.getMessage());
                    return ollamaChatModel.stream(new Prompt(messages))
                            .map(res -> res.getResult().getOutput().getContent());
                });

        chatFlux.subscribe(
                chunk -> {
                    try {
                        if (chunk != null) {
                            fullResponse.append(chunk);
                            emitter.send(SseEmitter.event().data(Map.of("content", chunk)));
                        }
                    } catch (IOException e) {
                        log.error("SSE发送内容失败", e);
                    }
                },
                error -> emitter.completeWithError(error),
                () -> {
                    try {
                        asyncSaveChatRecord(request.getSessionId(), userInput, fullResponse.toString());
                        emitter.send(SseEmitter.event().name("done").data(Map.of("content", "[DONE]")));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }
        );
    }

    /**
     * 获取历史记录 (MP 简化)
     */
    public List<ChatRecord> getChatHistory(String sessionId) {
        return chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId)
                .orderByAsc(ChatRecord::getCreatedTime));
    }

    /**
     * 系统统计 (使用 MP selectCount)
     */
    public Map<String, Object> getSystemStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            stats.put("totalChats", chatRecordMapper.selectCount(null));
            stats.put("status", "运行正常");
        } catch (Exception e) {
            stats.put("status", "异常");
            stats.put("error", e.getMessage());
        }
        stats.put("timestamp", LocalDateTime.now());
        return stats;
    }

    /**
     * 分页查询 (使用 MP 内置分页)
     */
    public IPage<ChatRecord> getChatRecordsPage(int page, int size) {
        Page<ChatRecord> pageConfig = new Page<>(page, size);
        return chatRecordMapper.selectPage(pageConfig, new LambdaQueryWrapper<ChatRecord>()
                .orderByDesc(ChatRecord::getCreatedTime));
    }
}