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

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 智能客服核心服务类
 * 基于Spring AI + MyBatis实现智能对话
 * 
 * @author yyvb
 */
@Service
public class ChatbotService {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotService.class);
    // 【修改点 2】不再使用单一的 ChatClient，改用两个具体的模型
    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;
    private final ChatRecordMapper chatRecordMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.chatbot.system-prompt:你是一个专业的智能客服助手，请友好、专业地回答用户的问题。}")
    private String systemPrompt;

    @Value("${app.chatbot.max-history:5}")
    private int maxHistory;

    @Value("${app.chatbot.timeout:30000}")
    private long timeout;

    // 【修改点 3】构造函数注入两个模型
    @Autowired
    public ChatbotService(OpenAiChatModel openAiChatModel, OllamaChatModel ollamaChatModel, ChatRecordMapper chatRecordMapper) {
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.chatRecordMapper = chatRecordMapper;
    }

    /**
     * 处理用户聊天请求（同步）
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        try {
            if (request.getMessage() == null || request.getMessage().isBlank()) {
                return ChatResponse.error("消息内容不能为空", request.getSessionId());
            }

            String userInput = request.getMessage().trim();
            List<Message> messages = buildConversationContext(request.getSessionId(), userInput);

            // 【修改点 4】逻辑切换：传入前端想用的模型标识
            String aiResponse = callAIWithFallback(messages, request.getModel());

            long responseTime = System.currentTimeMillis() - startTime;
            asyncSaveChatRecord(request.getSessionId(), userInput, aiResponse);

            return new ChatResponse(aiResponse, request.getSessionId(), responseTime);
        } catch (Exception e) {
            logger.error("聊天最终失败", e);
            return ChatResponse.error("AI服务全线拥堵，请稍后再试", request.getSessionId());
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
     * 【修改点 5】核心降级逻辑：同步调用
     */
    private String callAIWithFallback(List<Message> messages, String preferredModel) throws Exception {
        // 如果前端指名道姓要用 ollama
        if ("ollama".equalsIgnoreCase(preferredModel)) {
            return ollamaChatModel.call(new Prompt(messages)).getResult().getOutput().getContent();
        }

        try {
            // 默认尝试官方 DeepSeek (OpenAI 协议)
            logger.info("正在尝试调用 DeepSeek 官方接口...");
            return openAiChatModel.call(new Prompt(messages)).getResult().getOutput().getContent();
        } catch (Exception e) {
            // 自动降级！
            logger.warn("官方接口异常，正在自动切换至本地 Ollama 隧道...");
            return ollamaChatModel.call(new Prompt(messages)).getResult().getOutput().getContent();
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
    /**
     * 【修改点 6】流式处理降级逻辑
     */
    public void streamChat(ChatRequest request, SseEmitter emitter) {
        String userInput = request.getMessage().trim();
        List<Message> messages = buildConversationContext(request.getSessionId(), userInput);
        StringBuilder fullResponse = new StringBuilder();

        try {
            // 【核心改动】一进来先发一个空包，占住坑位，防止超时！
            emitter.send(SseEmitter.event().data(Map.of("content", "")));
        } catch (IOException e) {
            logger.error("发送初始化包失败", e);
        }

        // 决定起手模型
        boolean startWithOllama = "ollama".equalsIgnoreCase(request.getModel());

        Flux<String> chatFlux;
        if (startWithOllama) {
            chatFlux = ollamaChatModel.stream(new Prompt(messages)).map(res -> res.getResult().getOutput().getContent());
        } else {
            // 官方优先，失败则 resume (接力) 到 Ollama
            chatFlux = openAiChatModel.stream(new Prompt(messages))
                    .map(res -> {
                        String content = res.getResult().getOutput().getContent();
                        return content != null ? content : "";
                    })
                    .onErrorResume(e -> {
                        logger.warn("流式输出：官方接口故障，切换到本地 Ollama...");
                        return ollamaChatModel.stream(new Prompt(messages))
                                .map(res -> res.getResult().getOutput().getContent());
                    });
        }

        chatFlux.subscribe(
                chunk -> {
                    try {
                        if (chunk != null && !chunk.isEmpty()) {
                            fullResponse.append(chunk);
                            // 发送实际内容
                            emitter.send(SseEmitter.event().data(Map.of("content", chunk)));
                            // 【新增】每发一个字，紧跟一个空行或注释，强迫中间代理（Cloudflare）推送到前端
                            emitter.send(SseEmitter.event().comment("keep-alive"));
                        }
                    } catch (IOException e) {
                        emitter.completeWithError(e);
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
} 