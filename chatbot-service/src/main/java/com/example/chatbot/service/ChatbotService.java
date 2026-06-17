package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.kafka.ChatEvent;
import com.example.chatbot.mapper.ChatRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ChatbotService {

    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;
    private final ChatModel visionChatModel;
    private final ChatRecordMapper chatRecordMapper;
    private final ChatRecordPersistenceService chatRecordPersistenceService;
    private final FileServiceClient fileServiceClient;
    private final ChatContextService chatContextService;

    private final Semaphore ollamaSemaphore = new Semaphore(1);

    @Value("${app.chatbot.system-prompt}")
    private String systemPrompt;
    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;
    @Value("${app.chatbot.rag-enabled:true}")
    private boolean ragEnabledDefault;
    @Value("${app.chatbot.rag-top-k:3}")
    private int ragTopKDefault;
    @Value("${app.chatbot.vision-model:llava:latest}")
    private String visionModel;

    @Autowired
    public ChatbotService(
            ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
            @org.springframework.beans.factory.annotation.Qualifier("visionChatModel") ObjectProvider<ChatModel> visionChatModelProvider,
            ChatRecordMapper chatRecordMapper,
            ChatRecordPersistenceService chatRecordPersistenceService,
            FileServiceClient fileServiceClient,
            ChatContextService chatContextService
    ) {
        this.chatRecordMapper = chatRecordMapper;
        this.chatRecordPersistenceService = chatRecordPersistenceService;
        this.fileServiceClient = fileServiceClient;
        this.chatContextService = chatContextService;

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

        ChatModel vision = null;
        try {
            vision = visionChatModelProvider.getIfAvailable();
        } catch (Exception e) {
            log.warn("视觉模型不可用: {}", e.getMessage());
        }
        this.visionChatModel = vision;

        log.info("Chat models available - OpenAI: {}, Ollama: {}, Vision: {}", openAiChatModel != null, ollamaChatModel != null, visionChatModel != null);
    }

    private ChatModel getChatModel(String preferredModel) {
        if ("ollama".equalsIgnoreCase(preferredModel)) {
            return ollamaChatModel != null ? ollamaChatModel : openAiChatModel;
        }
        return openAiChatModel != null ? openAiChatModel : ollamaChatModel;
    }

    /**
     * 核心逻辑：构建对话上下文（带 Redis/MySQL 性能对比统计）
     */
    private ConversationContext buildConversationContext(
            String sessionId,
            String userInput,
            String userId,
            Boolean useRag,
            Integer ragTopK
    ) {
        return chatContextService.buildConversationContext(
                sessionId,
                userInput,
                userId,
                systemPrompt,
                useRag,
                ragTopK,
                ragEnabledDefault,
                ragTopKDefault
        );
    }
    /**
     * 保存聊天记录和 outbox 事件（事务内原子写入）
     */
    public void asyncSaveChatRecord(String sessionId, String userMsg, String botRes) {
        asyncSaveChatRecordWithImage(sessionId, userMsg, botRes, null, null);
    }

    public void asyncSaveChatRecordWithImage(String sessionId, String userMsg, String botRes,
                                              byte[] imageBytes, String imageMimeType) {
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .eventId(UUID.randomUUID().toString().replace("-", ""))
                .sessionId(sessionId)
                .userMessage(userMsg)
                .botResponse(botRes)
                .imageBytes(imageBytes)
                .imageMimeType(imageMimeType)
                .userId(resolveUserIdFromSession(sessionId))
                .eventTime(LocalDateTime.now())
                .build();

        try {
            ChatRecord chatRecord = chatRecordPersistenceService.saveChatAndOutbox(event);
            chatContextService.appendPersistedRecordToCache(chatRecord);
            log.info("【ChatbotService】聊天记录保存成功，RecordId: {}, SessionId: {}",
                    chatRecord.getId(), sessionId);
        } catch (Exception e) {
            log.error("【ChatbotService】聊天记录保存失败，SessionId: {}", sessionId, e);
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
     * 流式对话逻辑（带并发控制）
     */
    public void streamChat(ChatRequest request, SseEmitter emitter, String userId) {
        ensureSessionOwnedByUser(request.getSessionId(), userId);
        ChatModel model = getChatModel(request.getModel());
        if (model == null) {
            sendStreamError(emitter, "未找到可用的聊天模型");
            return;
        }
        if (model == openAiChatModel && (openAiApiKey == null || openAiApiKey.isBlank())) {
            sendStreamError(emitter, "AI 接口鉴权失败，请检查 DEEPSEEK_API_KEY");
            return;
        }

        boolean isOllama = model == ollamaChatModel;
        boolean acquired = false;

        // Ollama 单线程推理，用信号量排队
        if (isOllama) {
            try {
                int available = ollamaSemaphore.availablePermits();
                if (available == 0) {
                    emitter.send(SseEmitter.event().name("status")
                            .data(Map.of("status", "queued", "message", "AI 正在处理其他请求，请稍候…")));
                }
                acquired = ollamaSemaphore.tryAcquire(120, TimeUnit.SECONDS);
                if (!acquired) {
                    sendStreamError(emitter, "当前排队人数过多，请稍后重试");
                    return;
                }
                emitter.send(SseEmitter.event().name("status")
                        .data(Map.of("status", "processing", "message", "")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendStreamError(emitter, "请求被中断，请重试");
                return;
            } catch (Exception e) {
                log.warn("SSE 状态发送失败: {}", e.getMessage());
            }
        }

        boolean finalAcquired = isOllama && acquired;
        ConversationContext conversationContext = buildConversationContext(
                request.getSessionId(), request.getMessage(), userId,
                request.getUseRag(), request.getRagTopK()
        );

        if (!conversationContext.references().isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("references")
                        .data(Map.of("references", conversationContext.references())));
            } catch (Exception e) {
                log.warn("SSE 发送知识引用失败: {}", e.getMessage());
            }
        }

        StringBuilder fullRes = new StringBuilder();

        try {
            model.stream(new Prompt(conversationContext.messages()))
                    .map(res -> res.getResult().getOutput().getText())
                    .subscribe(
                            chunk -> {
                                try {
                                    if (chunk != null) {
                                        fullRes.append(chunk);
                                        emitter.send(Map.of("content", chunk));
                                    }
                                } catch (Exception e) {
                                    log.warn("SSE 发送 chunk 失败: {}", e.getMessage());
                                }
                            },
                            err -> {
                                log.error("流式对话失败: {}", resolveErrorMessage(err), err);
                                asyncSaveChatRecord(request.getSessionId(), request.getMessage(),
                                        fullRes.length() > 0 ? fullRes.toString() + "\n\n[回答中断]" : "");
                                sendStreamError(emitter, resolveErrorMessage(err));
                                releaseIfOllama(finalAcquired);
                            },
                            () -> {
                                asyncSaveChatRecord(request.getSessionId(), request.getMessage(), fullRes.toString());
                                emitter.complete();
                                releaseIfOllama(finalAcquired);
                            }
                    );
        } catch (Exception e) {
            log.error("流式对话初始化失败: {}", resolveErrorMessage(e), e);
            sendStreamError(emitter, resolveErrorMessage(e));
            releaseIfOllama(finalAcquired);
        }
    }

    /**
     * 多模态流式对话（支持图片+文字）
     * 当有图片时，强制使用 Ollama 视觉模型（如 llava）
     */
    public void streamChatWithImage(ChatRequest request, MultipartFile image,
                                    SseEmitter emitter, String userId) throws IOException {
        ensureSessionOwnedByUser(request.getSessionId(), userId);

        // 有多模态图片时，走 Ollama 原生 API（支持 /api/chat 的多模态格式）
        ChatModel model;
        boolean hasImage = image != null && !image.isEmpty();
        if (hasImage) {
            model = ollamaChatModel;
            if (model == null) {
                sendStreamError(emitter, "多模态功能需要 Ollama 模型，请确保 Ollama 服务可用");
                return;
            }
            log.info("多模态请求：使用视觉模型 {}，图片大小: {} bytes", visionModel, image.getSize());
        } else {
            model = getChatModel(request.getModel());
            if (model == null) {
                sendStreamError(emitter, "未找到可用的聊天模型");
                return;  
            }
        }

        if (model == openAiChatModel && (openAiApiKey == null || openAiApiKey.isBlank())) {
            sendStreamError(emitter, "AI 接口鉴权失败，请检查 DEEPSEEK_API_KEY");
            return;
        }

        boolean isOllama = model == ollamaChatModel;
        boolean acquired = false;

        // Ollama 单线程推理，用信号量排队
        if (isOllama) {
            try {
                int available = ollamaSemaphore.availablePermits();
                if (available == 0) {
                    emitter.send(SseEmitter.event().name("status")
                            .data(Map.of("status", "queued", "message", "AI 正在处理其他请求，请稍候…")));
                }
                acquired = ollamaSemaphore.tryAcquire(120, TimeUnit.SECONDS);
                if (!acquired) {
                    sendStreamError(emitter, "当前排队人数过多，请稍后重试");
                    return;
                }
                emitter.send(SseEmitter.event().name("status")
                        .data(Map.of("status", "processing", "message", "")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendStreamError(emitter, "请求被中断，请重试");
                return;
            } catch (Exception e) {
                log.warn("SSE 状态发送失败: {}", e.getMessage());
            }
        }

        boolean finalAcquired = isOllama && acquired;

        // 构建对话上下文
        ConversationContext conversationContext = buildConversationContext(
                request.getSessionId(), request.getMessage(), userId,
                request.getUseRag(), request.getRagTopK()
        );

        // 发送 RAG 引用
        if (!conversationContext.references().isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("references")
                        .data(Map.of("references", conversationContext.references())));
            } catch (Exception e) {
                log.warn("SSE 发送知识引用失败: {}", e.getMessage());
            }
        }

        // 构建最终消息列表：替换最后一条 UserMessage 为多模态版本
        List<Message> messages = new ArrayList<>(conversationContext.messages());
        // 保存图片字节和 MIME 类型用于持久化
        final byte[] imageBytesForSave;
        final String imageMimeTypeForSave;
        if (hasImage && image != null) {
            // 移除最后一条普通的 UserMessage，替换为带图片的版本
            messages.remove(messages.size() - 1);
            MimeType mimeType = resolveImageMimeType(image.getContentType());
            byte[] imgBytes = image.getBytes();
            Media media = new Media(mimeType, new ByteArrayResource(imgBytes));
            messages.add(UserMessage.builder()
                    .text(request.getMessage())
                    .media(media)
                    .build());
            imageBytesForSave = imgBytes;
            imageMimeTypeForSave = mimeType.toString();
        } else {
            imageBytesForSave = null;
            imageMimeTypeForSave = null;
        }

        StringBuilder fullRes = new StringBuilder();

        try {
            // 多模态请求时，通过请求级选项指定视觉模型
            Prompt prompt;
            if (hasImage) {
                var visionOptions = org.springframework.ai.ollama.api.OllamaOptions.builder()
                        .model(visionModel)
                        .build();
                prompt = new Prompt(messages, visionOptions);
                log.info("【多模态】实际调用 Ollama 视觉模型: {}，消息数: {}", visionModel, messages.size());
            } else {
                prompt = new Prompt(messages);
            }
            model.stream(prompt)
                    .map(res -> res.getResult().getOutput().getText())
                    .subscribe(
                            chunk -> {
                                try {
                                    if (chunk != null) {
                                        fullRes.append(chunk);
                                        emitter.send(Map.of("content", chunk));
                                    }
                                } catch (Exception e) {
                                    log.warn("SSE 发送 chunk 失败: {}", e.getMessage());
                                }
                            },
                            err -> {
                                log.error("多模态流式对话失败: {}", resolveErrorMessage(err), err);
                                asyncSaveChatRecordWithImage(request.getSessionId(), request.getMessage(),
                                        fullRes.length() > 0 ? fullRes.toString() + "\n\n[回答中断]" : "",
                                        imageBytesForSave, imageMimeTypeForSave);
                                sendStreamError(emitter, resolveErrorMessage(err));
                                releaseIfOllama(finalAcquired);
                            },
                            () -> {
                                asyncSaveChatRecordWithImage(request.getSessionId(), request.getMessage(),
                                        fullRes.toString(), imageBytesForSave, imageMimeTypeForSave);
                                emitter.complete();
                                releaseIfOllama(finalAcquired);
                            }
                    );
        } catch (Exception e) {
            log.error("多模态流式对话初始化失败: {}", resolveErrorMessage(e), e);
            sendStreamError(emitter, resolveErrorMessage(e));
            releaseIfOllama(finalAcquired);
        }
    }

    /**
     * 多模态流式对话（通过 fileKey 从文件服务获取图片）
     * 新方式：前端先上传图片到 file-service，再传 fileKey 过来
     */
    public void streamChatWithFileKey(ChatRequest request, String imageFileKey,
                                      SseEmitter emitter, String userId) throws IOException {
        if (imageFileKey == null || imageFileKey.isBlank()) {
            streamChat(request, emitter, userId);
            return;
        }

        // 从文件服务获取图片信息
        Map<String, Object> fileInfo = fileServiceClient.getFileInfo(imageFileKey, Long.valueOf(userId));
        if (fileInfo == null) {
            sendStreamError(emitter, "文件不存在: " + imageFileKey);
            return;
        }

        // 获取图片二进制数据
        byte[] imageBytes = fileServiceClient.getFileBytes(imageFileKey, Long.valueOf(userId));
        if (imageBytes == null || imageBytes.length == 0) {
            sendStreamError(emitter, "文件下载失败: " + imageFileKey);
            return;
        }

        String contentType = (String) fileInfo.get("contentType");

        // 直接调用带 fileKey 的方法，传递字节数组
        streamChatWithImageBytes(request, imageBytes, contentType, imageFileKey, emitter, userId);
    }

    /**
     * 多模态流式对话（通过图片字节数组 + fileKey）
     * fileKey 用于保存到数据库，避免存 Base64
     */
    public void streamChatWithImageBytes(ChatRequest request, byte[] imageBytes, String imageContentType,
                                         String imageFileKey, SseEmitter emitter, String userId) throws IOException {
        ensureSessionOwnedByUser(request.getSessionId(), userId);

        ChatModel model;
        boolean hasImage = imageBytes != null && imageBytes.length > 0;
        if (hasImage) {
            model = ollamaChatModel;
            if (model == null) {
                sendStreamError(emitter, "多模态功能需要 Ollama 模型，请确保 Ollama 服务可用");
                return;
            }
            log.info("多模态请求：使用视觉模型 {}，图片大小: {} bytes，fileKey: {}", visionModel, imageBytes.length, imageFileKey);
        } else {
            model = getChatModel(request.getModel());
            if (model == null) {
                sendStreamError(emitter, "未找到可用的聊天模型");
                return;
            }
        }

        if (model == openAiChatModel && (openAiApiKey == null || openAiApiKey.isBlank())) {
            sendStreamError(emitter, "AI 接口鉴权失败，请检查 DEEPSEEK_API_KEY");
            return;
        }

        boolean isOllama = model == ollamaChatModel;
        boolean acquired = false;

        if (isOllama) {
            try {
                int available = ollamaSemaphore.availablePermits();
                if (available == 0) {
                    emitter.send(SseEmitter.event().name("status")
                            .data(Map.of("status", "queued", "message", "AI 正在处理其他请求，请稍候…")));
                }
                acquired = ollamaSemaphore.tryAcquire(120, TimeUnit.SECONDS);
                if (!acquired) {
                    sendStreamError(emitter, "当前排队人数过多，请稍后重试");
                    return;
                }
                emitter.send(SseEmitter.event().name("status")
                        .data(Map.of("status", "processing", "message", "")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendStreamError(emitter, "请求被中断，请重试");
                return;
            } catch (Exception e) {
                log.warn("SSE 状态发送失败: {}", e.getMessage());
            }
        }

        boolean finalAcquired = isOllama && acquired;

        ConversationContext conversationContext = buildConversationContext(
                request.getSessionId(), request.getMessage(), userId,
                request.getUseRag(), request.getRagTopK()
        );

        if (!conversationContext.references().isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("references")
                        .data(Map.of("references", conversationContext.references())));
            } catch (Exception e) {
                log.warn("SSE 发送知识引用失败: {}", e.getMessage());
            }
        }

        List<Message> messages = new ArrayList<>(conversationContext.messages());
        final byte[] imageBytesForSave;
        final String imageMimeTypeForSave;
        if (hasImage) {
            messages.remove(messages.size() - 1);
            MimeType mimeType = resolveImageMimeType(imageContentType);
            Media media = new Media(mimeType, new ByteArrayResource(imageBytes));
            messages.add(UserMessage.builder()
                    .text(request.getMessage())
                    .media(media)
                    .build());
            imageBytesForSave = imageBytes;
            imageMimeTypeForSave = mimeType.toString();
        } else {
            imageBytesForSave = null;
            imageMimeTypeForSave = null;
        }

        StringBuilder fullRes = new StringBuilder();

        try {
            Prompt prompt;
            if (hasImage) {
                var visionOptions = org.springframework.ai.ollama.api.OllamaOptions.builder()
                        .model(visionModel)
                        .build();
                prompt = new Prompt(messages, visionOptions);
                log.info("【多模态】实际调用 Ollama 视觉模型: {}，消息数: {}", visionModel, messages.size());
            } else {
                prompt = new Prompt(messages);
            }
            model.stream(prompt)
                    .map(res -> res.getResult().getOutput().getText())
                    .subscribe(
                            chunk -> {
                                try {
                                    if (chunk != null) {
                                        fullRes.append(chunk);
                                        emitter.send(Map.of("content", chunk));
                                    }
                                } catch (Exception e) {
                                    log.warn("SSE 发送 chunk 失败: {}", e.getMessage());
                                }
                            },
                            err -> {
                                log.error("多模态流式对话失败: {}", resolveErrorMessage(err), err);
                                // 优先保存 fileKey，没有则保存 Base64
                                if (imageFileKey != null) {
                                    asyncSaveChatRecordWithFileKey(request.getSessionId(), request.getMessage(),
                                            fullRes.length() > 0 ? fullRes.toString() + "\n\n[回答中断]" : "",
                                            imageFileKey, imageMimeTypeForSave);
                                } else {
                                    asyncSaveChatRecordWithImage(request.getSessionId(), request.getMessage(),
                                            fullRes.length() > 0 ? fullRes.toString() + "\n\n[回答中断]" : "",
                                            imageBytesForSave, imageMimeTypeForSave);
                                }
                                sendStreamError(emitter, resolveErrorMessage(err));
                                releaseIfOllama(finalAcquired);
                            },
                            () -> {
                                if (imageFileKey != null) {
                                    asyncSaveChatRecordWithFileKey(request.getSessionId(), request.getMessage(),
                                            fullRes.toString(), imageFileKey, imageMimeTypeForSave);
                                } else {
                                    asyncSaveChatRecordWithImage(request.getSessionId(), request.getMessage(),
                                            fullRes.toString(), imageBytesForSave, imageMimeTypeForSave);
                                }
                                emitter.complete();
                                releaseIfOllama(finalAcquired);
                            }
                    );
        } catch (Exception e) {
            log.error("多模态流式对话初始化失败: {}", resolveErrorMessage(e), e);
            sendStreamError(emitter, resolveErrorMessage(e));
            releaseIfOllama(finalAcquired);
        }
    }

    /**
     * 发送聊天事件（fileKey 模式，不传图片字节）
     */
    public void asyncSaveChatRecordWithFileKey(String sessionId, String userMsg, String botRes,
                                               String imageFileKey, String imageMimeType) {
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .eventId(UUID.randomUUID().toString().replace("-", ""))
                .sessionId(sessionId)
                .userMessage(userMsg)
                .botResponse(botRes)
                .imageFileKey(imageFileKey)
                .imageMimeType(imageMimeType)
                .userId(resolveUserIdFromSession(sessionId))
                .eventTime(LocalDateTime.now())
                .build();

        try {
            ChatRecord chatRecord = chatRecordPersistenceService.saveChatAndOutbox(event);
            chatContextService.appendPersistedRecordToCache(chatRecord);
            log.info("【ChatbotService】聊天记录保存成功（fileKey），RecordId: {}, SessionId: {}",
                    chatRecord.getId(), sessionId);
        } catch (Exception e) {
            log.error("【ChatbotService】聊天记录保存失败（fileKey），SessionId: {}", sessionId, e);
        }
    }

    private String resolveUserIdFromSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        int split = sessionId.indexOf('_');
        return split > 0 ? sessionId.substring(0, split) : null;
    }

    private MimeType resolveImageMimeType(String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            try {
                return MimeTypeUtils.parseMimeType(contentType);
            } catch (Exception ignored) {}
        }
        return MimeTypeUtils.IMAGE_JPEG;
    }

    private void releaseIfOllama(boolean acquired) {
        if (acquired) {
            ollamaSemaphore.release();
        }
    }

    private String resolveErrorMessage(Throwable err) {
        String msg = err.getMessage();
        if (msg == null) msg = "未知错误";

        // 401 鉴权
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("Incorrect API key"))
            return "API 密钥错误，请检查 DEEPSEEK_API_KEY 配置";

        // 429 限流
        if (msg.contains("429") || msg.contains("Rate limit") || msg.contains("Too Many Requests"))
            return "请求过于频繁，请稍后重试";

        // 连接超时 / 拒绝
        if (msg.contains("timeout") || msg.contains("Timeout") || msg.contains("timed out"))
            return "AI 服务响应超时，请重试";

        if (msg.contains("Connection refused") || msg.contains("connect") || msg.contains("refused"))
            return "AI 服务连接失败，请检查网络或模型是否运行中";

        // 500 服务器错误
        if (msg.contains("500") || msg.contains("Internal Server Error"))
            return "AI 服务内部错误，请稍后重试";

        // 内容被审查
        if (msg.contains("content_filter") || msg.contains("safety") || msg.contains("moderation"))
            return "内容被安全策略拦截，请修改提问内容";

        return "服务异常，请重试";
    }

    private void sendStreamError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("error", message)));
        } catch (Exception sendEx) {
            log.warn("SSE 错误发送失败: {}", sendEx.getMessage());
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
        chatContextService.evictSessionContext(sessionId);
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
