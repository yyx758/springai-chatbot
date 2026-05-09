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
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Autowired
    public ChatbotService(
            ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
            ChatRecordMapper chatRecordMapper,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.chatRecordMapper = chatRecordMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

        // 使用 try-catch 包裹 getIfAvailable()，防止 Bean 创建失败（如网络不通）导致整个应用崩溃
        OpenAiChatModel openAi = null;
        try {
            openAi = openAiChatModelProvider.getIfAvailable();
        } catch (Exception e) {
            log.warn("OpenAI/DeepSeek 模型不可用: {}", e.getMessage());
        }
        this.openAiChatModel = openAi;

        OllamaChatModel ollama = null;
        try {
            ollama = ollamaChatModelProvider.getIfAvailable();
        } catch (Exception e) {
            log.warn("Ollama 模型不可用: {}", e.getMessage());
        }
        this.ollamaChatModel = ollama;

        log.info("Chat models available - OpenAI: {}, Ollama: {}", openAiChatModel != null, ollamaChatModel != null);
    }

    private ChatModel getChatModel(String preferredModel) {
        if ("ollama".equalsIgnoreCase(preferredModel)) {
            return ollamaChatModel != null ? ollamaChatModel : openAiChatModel;
        }
        return openAiChatModel != null ? openAiChatModel : ollamaChatModel;
    }

    /**
     * 同步对话逻辑
     */
    public ChatResponse chat(ChatRequest request, String userId) {
        ensureSessionOwnedByUser(request.getSessionId(), userId);
        ChatModel model = getChatModel(request.getModel());
        if (model == null) {
            throw new RuntimeException("未找到可用的聊天模型");
        }
        if (model == openAiChatModel && (openAiApiKey == null || openAiApiKey.isBlank())) {
            throw new RuntimeException("AI 接口鉴权失败，请检查 DEEPSEEK_API_KEY");
        }
        List<Message> messages = buildConversationContext(request.getSessionId(), request.getMessage());
        long start = System.currentTimeMillis();

        try {
            String res = model.call(new Prompt(messages)).getResult().getOutput().getContent();
            asyncSaveChatRecord(request.getSessionId(), request.getMessage(), res);
            return ChatResponse.builder()
                    .message(res)
                    .sessionId(request.getSessionId())
                    .responseTime(System.currentTimeMillis() - start)
                    .success(true)
                    .build();
        } catch (Exception e) {
            log.error("同步聊天失败: {}", e.getMessage());
            throw new RuntimeException("AI调用异常，请重试");
        }
    }

    /**
     * 核心逻辑：构建对话上下文
     */
    /**
     * 核心逻辑：构建对话上下文（带 Redis/MySQL 性能对比统计）
     */
    private List<Message> buildConversationContext(String sessionId, String userInput) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        String key = "chat:history:" + sessionId;
        List<ChatRecord> history = Collections.emptyList();
        boolean cacheHit = false;

        // --- 1. 尝试从 Redis 读取并计时 ---
        try {
            long redisStart = System.nanoTime();
            List<Object> rawHistory = redisTemplate.opsForList().range(key, 0, -1);
            long redisEnd = System.nanoTime();
            long redisTimeUs = (redisEnd - redisStart) / 1000; // 转换为微秒

            if (rawHistory != null && !rawHistory.isEmpty()) {
                // 【场景 A：缓存命中】
                history = rawHistory.stream()
                        .map(obj -> objectMapper.convertValue(obj, ChatRecord.class))
                        .collect(Collectors.toList());

                cacheHit = true;
                log.info("【性能监控】Redis 命中！读取 {} 条记录，耗时: {} 微秒 (μs)",
                        history.size(), redisTimeUs);
            } else {
                log.warn("【性能监控】Redis 未命中，正在查询 MySQL...");
            }
        } catch (Exception e) {
            // Redis 不可用时不要阻塞对话流程，直接降级到数据库
            log.warn("Redis 不可用，已降级到 MySQL 查询: {}", e.getMessage());
        }

        if (!cacheHit) {
            long dbStart = System.nanoTime();
            history = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                    .eq(ChatRecord::getSessionId, sessionId)
                    .orderByAsc(ChatRecord::getCreatedTime)
                    .last("LIMIT " + maxHistory));
            long dbEnd = System.nanoTime();

            long dbTimeUs = (dbEnd - dbStart) / 1000; // 转换为微秒

            log.info("【性能监控】MySQL 查询完成，获取 {} 条记录，耗时: {} 微秒 (μs)",
                    history.size(), dbTimeUs);

            // 异步补偿：既然库里有，顺手写回 Redis，下次就快了
            if (!history.isEmpty()) {
                final List<ChatRecord> finalHistory = history;
                CompletableFuture.runAsync(() -> {
                    try {
                        redisTemplate.opsForList().rightPushAll(key, finalHistory.toArray());
                        redisTemplate.expire(key, 2, java.util.concurrent.TimeUnit.HOURS);
                    } catch (Exception e) {
                        log.warn("Redis 写回失败: {}", e.getMessage());
                    }
                });
            }
        }

        // 3. 构建消息对象
        history.forEach(r -> {
            messages.add(new UserMessage(r.getUserMessage()));
            messages.add(new AssistantMessage(r.getBotResponse()));
        });

        messages.add(new UserMessage(userInput));
        return messages;
    }

    @Async
    public void asyncSaveChatRecord(String sessionId, String userMsg, String botRes) {
        // 【核心过滤】：如果 botRes 包含 SSE 协议特征，强行清洗，只保留内容
        String cleanBotRes = botRes;
        if (botRes.contains("data:{\"content\":")) {
            // 这里的逻辑是兜底方案，防止脏数据入库
            cleanBotRes = botRes.replaceAll("data:\\{\"content\":\"|\"\\}", "");
        }

        ChatRecord record = ChatRecord.builder()
                .sessionId(sessionId)
                .userMessage(userMsg)
                .botResponse(cleanBotRes) // 存入清洗后的纯文本
                .createdTime(LocalDateTime.now())
                .build();
        chatRecordMapper.insert(record);

        // 更新 Redis 缓存
        try {
            String key = "chat:history:" + sessionId;
            redisTemplate.opsForList().rightPush(key, record);
            redisTemplate.opsForList().trim(key, -maxHistory, -1);
            redisTemplate.expire(key, 2, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis同步失败: {}", e.getMessage());
        }
    }

    public List<ChatRecord> getChatHistory(String sessionId, String userId) {
        ensureSessionOwnedByUser(sessionId, userId);
        return chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId).orderByAsc(ChatRecord::getCreatedTime));
    }

    public Map<String, Object> getSystemStats(String userId) {
        LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.likeRight(ChatRecord::getSessionId, buildSessionPrefix(userId));
        return Map.of("totalChats", chatRecordMapper.selectCount(wrapper), "status", "RUNNING");
    }

    public IPage<ChatRecord> getChatRecordsPage(int page, int size, String userId) {
        LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.likeRight(ChatRecord::getSessionId, buildSessionPrefix(userId));
        wrapper.orderByDesc(ChatRecord::getCreatedTime);
        return chatRecordMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 流式对话逻辑
     */
    public void streamChat(ChatRequest request, SseEmitter emitter, String userId) {
        ensureSessionOwnedByUser(request.getSessionId(), userId);
        ChatModel model = getChatModel(request.getModel());
        if (model == null) {
            sendStreamError(emitter, new IllegalStateException("未找到可用的聊天模型"));
            return;
        }
        if (model == openAiChatModel && (openAiApiKey == null || openAiApiKey.isBlank())) {
            sendStreamError(emitter, new IllegalStateException("AI 接口鉴权失败，请检查 DEEPSEEK_API_KEY"));
            return;
        }
        List<Message> messages = buildConversationContext(request.getSessionId(), request.getMessage());
        // 用于收集纯文本内容保存到数据库
        StringBuilder fullRes = new StringBuilder();

        try {
            model.stream(new Prompt(messages))
                    .map(res -> res.getResult().getOutput().getContent()) // 这里获取的是纯文本内容
                    .subscribe(
                            chunk -> {
                                try {
                                    if (chunk != null) {
                                        fullRes.append(chunk); // 仅保存纯文本到 StringBuilder
                                        emitter.send(Map.of("content", chunk)); // 发送 Map，SseEmitter 会自动转为 JSON
                                    }
                                } catch (Exception e) { log.error("SSE发送失败"); }
                            },
                            err -> {
                                log.error("流式对话失败", err);
                                sendStreamError(emitter, err);
                            },
                            () -> {
                                // 只有在这里保存到数据库，确保内容是干净的
                                asyncSaveChatRecord(request.getSessionId(), request.getMessage(), fullRes.toString());
                                emitter.complete();
                            }
                    );
        } catch (Exception e) {
            log.error("流式对话初始化失败", e);
            sendStreamError(emitter, e);
        }
    }

    private String resolveStreamErrorMessage(Throwable err) {
        Throwable cursor = err;
        while (cursor != null) {
            if (cursor instanceof WebClientResponseException webEx) {
                if (webEx.getStatusCode().value() == 401) {
                    return "AI 接口鉴权失败，请检查 DEEPSEEK_API_KEY";
                }
                return "AI 接口调用失败: HTTP " + webEx.getStatusCode().value();
            }
            cursor = cursor.getCause();
        }
        return "流式对话失败: " + (err.getMessage() == null ? "未知错误" : err.getMessage());
    }

    private void sendStreamError(SseEmitter emitter, Throwable err) {
        String message = resolveStreamErrorMessage(err);
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("error", message)));
        } catch (Exception sendEx) {
            log.warn("SSE 发送失败: {}", sendEx.getMessage());
        }
        emitter.complete();
    }

    /**
     * 删除特定会话的所有记录及缓存
     */
    public boolean deleteSession(String sessionId, String userId) {
        ensureSessionOwnedByUser(sessionId, userId);
        LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatRecord::getSessionId, sessionId);
        chatRecordMapper.delete(wrapper);
        redisTemplate.delete("chat:history:" + sessionId);
        log.info("会话已成功删除: {}", sessionId);
        return true;
    }

    private String buildSessionPrefix(String userId) {
        return userId + "_";
    }

    private void ensureSessionOwnedByUser(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("无效的用户信息");
        }
        if (!sessionId.startsWith(buildSessionPrefix(userId))) {
            throw new IllegalArgumentException("无权访问该会话");
        }
    }
}
