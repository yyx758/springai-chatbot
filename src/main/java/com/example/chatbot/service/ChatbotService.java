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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
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

    @Autowired
    public ChatbotService(OpenAiChatModel openAiChatModel, OllamaChatModel ollamaChatModel, ChatRecordMapper chatRecordMapper, RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.chatRecordMapper = chatRecordMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
    public ChatResponse chat(ChatRequest request) {
        ChatModel model = getChatModel(request.getModel());
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
    private List<Message> buildConversationContext(String sessionId, String userInput) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        // 优先加载该 sessionId 的历史记录
        List<ChatRecord> history = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId)
                .orderByAsc(ChatRecord::getCreatedTime)
                .last("LIMIT " + maxHistory));

        history.forEach(r -> {
            messages.add(new UserMessage(r.getUserMessage()));
            messages.add(new AssistantMessage(r.getBotResponse()));
        });
        messages.add(new UserMessage(userInput));
        return messages;
    }

    @Async
    public void asyncSaveChatRecord(String sessionId, String userMsg, String botRes) {
        // 【新增核心过滤】：如果 botRes 包含 SSE 协议特征，强行清洗，只保留内容
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

    public List<ChatRecord> getChatHistory(String sessionId) {
        return chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId).orderByAsc(ChatRecord::getCreatedTime));
    }

    public Map<String, Object> getSystemStats(String userId) {
        LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
        if (userId != null && !userId.trim().isEmpty()) {
            wrapper.likeRight(ChatRecord::getSessionId, userId + "_");
        }
        return Map.of("totalChats", chatRecordMapper.selectCount(wrapper), "status", "RUNNING");
    }

    public IPage<ChatRecord> getChatRecordsPage(int page, int size, String userId) {
        LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
        if (userId != null && !userId.trim().isEmpty()) {
            wrapper.likeRight(ChatRecord::getSessionId, userId + "_");
        }
        wrapper.orderByDesc(ChatRecord::getCreatedTime);
        return chatRecordMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 流式对话逻辑
     */
    public void streamChat(ChatRequest request, SseEmitter emitter) {
        List<Message> messages = buildConversationContext(request.getSessionId(), request.getMessage());
        // 用于收集纯文本内容保存到数据库
        StringBuilder fullRes = new StringBuilder();

        getChatModel(request.getModel()).stream(new Prompt(messages))
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
                        err -> emitter.completeWithError(err),
                        () -> {
                            // 只有在这里保存到数据库，确保内容是干净的
                            asyncSaveChatRecord(request.getSessionId(), request.getMessage(), fullRes.toString());
                            try { emitter.send(Map.of("content", "[DONE]")); } catch (Exception ignored) {}
                            emitter.complete();
                        }
                );
    }

    /**
     * 删除特定会话的所有记录及缓存
     */
    public boolean deleteSession(String sessionId) {
        try {
            // 1. 删除数据库记录
            LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChatRecord::getSessionId, sessionId);
            chatRecordMapper.delete(wrapper);

            // 2. 清理 Redis 中的对话上下文缓存
            redisTemplate.delete(sessionId);

            log.info("会话已成功删除: {}", sessionId);
            return true;
        } catch (Exception e) {
            log.error("删除会话失败: {}", sessionId, e);
            return false;
        }
    }
}