package com.example.chatbot.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.agent.tool.ChatHistoryTools;
import com.example.chatbot.agent.tool.FileReadTools;
import com.example.chatbot.agent.tool.KnowledgeReadTools;
import com.example.chatbot.agent.tool.KnowledgeWriteTools;
import com.example.chatbot.agent.tool.TimeTools;
import com.example.chatbot.agent.tool.WebTools;
import com.example.chatbot.agent.tool.WorkspaceTools;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.service.RagService;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.service.ChatbotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AgentService {

    private static final Pattern PUBLIC_URL_PATTERN = Pattern.compile("https?://[^\\s\\u3000，。！？；、）)]+", Pattern.CASE_INSENSITIVE);
    private static final int WEB_CONTEXT_MAX_CHARS = 12000;

    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;
    private final ChatRecordMapper chatRecordMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final ChatbotService chatbotService;
    private final RagService ragService;
    private final KnowledgeReadTools knowledgeReadTools;
    private final KnowledgeWriteTools knowledgeWriteTools;
    private final FileReadTools fileReadTools;
    private final ChatHistoryTools chatHistoryTools;
    private final TimeTools timeTools;
    private final WorkspaceTools workspaceTools;
    private final WebTools webTools;

    @Value("${app.agent.enabled:true}")
    private boolean agentEnabled;

    @Value("${app.agent.max-history:5}")
    private int maxHistory;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    public AgentService(
            ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
            ChatRecordMapper chatRecordMapper,
            KnowledgeDocumentMapper knowledgeDocumentMapper,
            ChatbotService chatbotService,
            RagService ragService,
            KnowledgeReadTools knowledgeReadTools,
            KnowledgeWriteTools knowledgeWriteTools,
            FileReadTools fileReadTools,
            ChatHistoryTools chatHistoryTools,
            TimeTools timeTools,
            WorkspaceTools workspaceTools,
            WebTools webTools
    ) {
        this.openAiChatModel = getIfAvailable(openAiChatModelProvider, "OpenAI/DeepSeek");
        this.ollamaChatModel = getIfAvailable(ollamaChatModelProvider, "Ollama");
        this.chatRecordMapper = chatRecordMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.chatbotService = chatbotService;
        this.ragService = ragService;
        this.knowledgeReadTools = knowledgeReadTools;
        this.knowledgeWriteTools = knowledgeWriteTools;
        this.fileReadTools = fileReadTools;
        this.chatHistoryTools = chatHistoryTools;
        this.timeTools = timeTools;
        this.workspaceTools = workspaceTools;
        this.webTools = webTools;
    }

    public void streamAgent(ChatRequest request, SseEmitter emitter, String userId) {
        if (!agentEnabled) {
            sendStreamError(emitter, "Agent is disabled");
            return;
        }
        ensureSessionOwnedByUser(request.getSessionId(), userId);

        ChatModel model = getChatModel(request.getModel());
        if (model == null) {
            sendStreamError(emitter, "No available chat model");
            return;
        }
        if (model == openAiChatModel && (openAiApiKey == null || openAiApiKey.isBlank())) {
            sendStreamError(emitter, "AI API key is missing");
            return;
        }

        List<Message> messages = buildMessages(request, userId);
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(AgentToolContextKeys.USER_ID, userId);
        toolContext.put(AgentToolContextKeys.SESSION_ID, request.getSessionId());
        toolContext.put(AgentToolContextKeys.EMITTER, emitter);

        Long userIdValue = Long.valueOf(userId);
        Long baselineKnowledgeDocumentId = latestKnowledgeDocumentId(userIdValue);
        StringBuilder fullResponse = new StringBuilder();
        try {
            emitter.send(SseEmitter.event().name("agent_status")
                    .data(Map.of("status", "started")));

            enrichWithMandatoryWebFetch(messages, request.getMessage(), new ToolContext(toolContext));
            enrichWithAutoRag(messages, request.getMessage(), Long.valueOf(userId));

            ChatClient.create(model)
                    .prompt()
                    .messages(messages)
                    .tools(knowledgeReadTools, knowledgeWriteTools, fileReadTools, chatHistoryTools, timeTools, workspaceTools, webTools)
                    .toolContext(toolContext)
                    .stream()
                    .content()
                    .subscribe(
                            chunk -> {
                                try {
                                    if (chunk != null) {
                                        fullResponse.append(chunk);
                                        emitter.send(Map.of("content", chunk));
                                    }
                                } catch (Exception e) {
                                    log.warn("Agent SSE chunk send failed: {}", e.getMessage());
                                }
                            },
                            error -> {
                                log.error("Agent stream failed: {}", error.getMessage(), error);
                                String savedResponse = fullResponse.length() > 0
                                        ? fullResponse + "\n\n[Agent response interrupted]"
                                        : "";
                                chatbotService.asyncSaveChatRecord(request.getSessionId(), request.getMessage(), savedResponse);
                                sendStreamError(emitter, resolveErrorMessage(error));
                            },
                            () -> {
                                chatbotService.asyncSaveChatRecord(request.getSessionId(), request.getMessage(), fullResponse.toString());
                                try {
                                    emitNewKnowledgeDocuments(emitter, userIdValue, baselineKnowledgeDocumentId);
                                    emitter.send(SseEmitter.event().name("agent_status")
                                            .data(Map.of("status", "completed")));
                                } catch (Exception e) {
                                    log.warn("Agent completion event send failed: {}", e.getMessage());
                                }
                                emitter.complete();
                            }
                    );
        } catch (Exception e) {
            log.error("Agent initialization failed: {}", e.getMessage(), e);
            sendStreamError(emitter, resolveErrorMessage(e));
        }
    }

    private List<Message> buildMessages(ChatRequest request, String userId) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt()));

        List<ChatRecord> history = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, request.getSessionId())
                .orderByDesc(ChatRecord::getCreatedTime)
                .last("LIMIT " + Math.max(0, maxHistory)));

        history.stream()
                .sorted(Comparator.comparing(ChatRecord::getCreatedTime))
                .forEach(record -> {
                    messages.add(new UserMessage(record.getUserMessage()));
                    messages.add(new AssistantMessage(record.getBotResponse()));
                });

        messages.add(new UserMessage(request.getMessage()));
        return messages;
    }

    private String buildSystemPrompt() {
        return """
                You are AI Studio's customer service agent.
                You can answer directly, read current-user data, and perform only the explicitly available low-risk tools.

                KNOWLEDGE BASE TOOLS — STRICT rules:
                1. "列出/查看/搜索/管理/所有/全部 知识库/文档" → ALWAYS call listAllKnowledgeDocuments FIRST. NEVER use searchKnowledge for listing.
                2. "XXX是什么/怎么用/原理" (specific question about content) → call searchKnowledge.
                3. "创建/保存/写入" → call createKnowledgeDocument.
                4. "删除" → call listAllKnowledgeDocuments first to find the ID, then call requestDeleteKnowledgeDocument.
                The word "搜索" in "搜索所有文档" means LIST, not SEARCH. Always use listAllKnowledgeDocuments when the intent is to enumerate.

                When the user asks to create, save, store, or put a document into the knowledge base, you MUST call createKnowledgeDocument.
                When the user asks to create, write, save, or edit a normal file, you MUST use workspace tools such as createWorkspaceFile or updateWorkspaceFile.
                Workspace files are the only files you can create or edit. Never claim you wrote a local/server file unless a workspace tool succeeded.
                When the user uploads or imports a code project, first listWorkspaceFiles, then read relevant files before proposing or applying code changes.
                For code edits, prefer targeted updates to the smallest necessary files and explain which workspace files changed.
                Local folders are only available after the user explicitly opens one in the browser workspace UI. You can operate on the synced workspace files, but the browser must write them back to the local folder.
                When the user asks to search the web or read a web page, use web tools. If web tools are disabled or not configured, say that clearly.
                If the system message says fetchWebPage has already been called for the current request, use that provided tool result and do not pretend to browse again.
                Never say a document was saved into the knowledge base unless createKnowledgeDocument succeeded.
                CRITICAL RULE: NEVER generate markdown links [text](url) or clickable URLs for any confirmation or action.
                When a tool returns requiresConfirmation=true, the Confirm button is ALREADY rendered in the UI tool panel.
                You MUST simply say "请点击上方确认按钮来完成操作" — do NOT generate any links, do NOT include any URLs.
                You must not delete anything directly. For deletion, call the request-delete tool and tell the user to click the Confirm button.
                Never claim that you deleted, sent, purchased, or executed a sensitive action unless the tool result confirms it.
                Use tools to get real project or user data instead of guessing.
                If a tool returns no useful data, say that clearly and continue with a conservative answer.
                Do not ask for or expose internal tokens, passwords, API keys, or secrets.
                Reply in concise, natural Chinese unless the user asks for another language.
                """;
    }

    private void enrichWithMandatoryWebFetch(List<Message> messages, String userMessage, ToolContext toolContext) {
        String url = extractFirstPublicUrl(userMessage);
        if (url == null || !looksLikeWebFetchRequest(userMessage)) {
            return;
        }
        try {
            Map<String, Object> result = webTools.fetchWebPage(url, toolContext);
            String title = String.valueOf(result.getOrDefault("title", ""));
            String content = String.valueOf(result.getOrDefault("content", ""));
            if (content.length() > WEB_CONTEXT_MAX_CHARS) {
                content = content.substring(0, WEB_CONTEXT_MAX_CHARS) + "\n\n[content truncated]";
            }
            messages.add(new SystemMessage("""
                    fetchWebPage has already been called for the current user request.
                    Use this real fetched page content as the web source. Do not claim web access beyond this tool result.

                    URL: %s
                    Title: %s
                    Content:
                    %s
                    """.formatted(url, title, content)));
        } catch (Exception e) {
            messages.add(new SystemMessage("""
                    fetchWebPage was required for the current user request but failed.
                    Tell the user the web fetch failed and include this error, instead of inventing page content.

                    URL: %s
                    Error: %s
                    """.formatted(url, e.getMessage())));
        }
    }

    private void enrichWithAutoRag(List<Message> messages, String userMessage, Long userId) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        try {
            List<RagReference> references = ragService.retrieveReferences(userId, userMessage, 3);
            if (references == null || references.isEmpty()) {
                return;
            }
            String knowledgePrompt = ragService.buildKnowledgePrompt(references);
            if (knowledgePrompt != null && !knowledgePrompt.isBlank()) {
                messages.add(new SystemMessage(knowledgePrompt));
            }
        } catch (Exception e) {
            log.warn("Auto RAG enrichment failed: {}", e.getMessage());
        }
    }

    private String extractFirstPublicUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = PUBLIC_URL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean looksLikeWebFetchRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("读取")
                || normalized.contains("抓取")
                || normalized.contains("爬取")
                || normalized.contains("获取")
                || normalized.contains("打开")
                || normalized.contains("网页")
                || normalized.contains("网站")
                || normalized.contains("fetch")
                || normalized.contains("scrape")
                || normalized.contains("read");
    }

    private Long latestKnowledgeDocumentId(Long userId) {
        KnowledgeDocument latest = knowledgeDocumentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUserId, userId)
                .orderByDesc(KnowledgeDocument::getId)
                .last("LIMIT 1"));
        return latest == null || latest.getId() == null ? 0L : latest.getId();
    }

    private void emitNewKnowledgeDocuments(SseEmitter emitter, Long userId, Long baselineId) {
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUserId, userId)
                .gt(KnowledgeDocument::getId, baselineId == null ? 0L : baselineId)
                .orderByAsc(KnowledgeDocument::getId));
        for (KnowledgeDocument document : documents) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("success", true);
                payload.put("documentId", document.getId());
                payload.put("title", document.getTitle());
                payload.put("enabled", document.getEnabled());
                payload.put("fileKey", document.getFileKey());
                payload.put("openPath", "/api/knowledge/documents/" + document.getId());
                if (document.getFileKey() != null && !document.getFileKey().isBlank()) {
                    payload.put("downloadUrl", "/api/files/download/" + document.getFileKey());
                }
                emitter.send(SseEmitter.event().name("knowledge_document_created").data(payload));
            } catch (Exception e) {
                log.warn("Agent knowledge document card event send failed: documentId={}, error={}",
                        document.getId(), e.getMessage());
            }
        }
    }

    private ChatModel getChatModel(String preferredModel) {
        if ("ollama".equalsIgnoreCase(preferredModel)) {
            return ollamaChatModel != null ? ollamaChatModel : openAiChatModel;
        }
        return openAiChatModel != null ? openAiChatModel : ollamaChatModel;
    }

    private <T> T getIfAvailable(ObjectProvider<T> provider, String name) {
        try {
            return provider.getIfAvailable();
        } catch (Exception e) {
            log.warn("{} model is unavailable: {}", name, e.getMessage());
            return null;
        }
    }

    private void ensureSessionOwnedByUser(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!sessionId.startsWith(userId + "_")) {
            throw new IllegalArgumentException("session does not belong to current user");
        }
    }

    private String resolveErrorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return "Agent service failed";
        }
        if (message.contains("401") || message.contains("Unauthorized") || message.contains("Incorrect API key")) {
            return "AI API authentication failed";
        }
        if (message.contains("429") || message.contains("Rate limit") || message.contains("Too Many Requests")) {
            return "AI service is rate limited, please retry later";
        }
        if (message.contains("timeout") || message.contains("Timeout") || message.contains("timed out")) {
            return "AI service timed out, please retry";
        }
        return "Agent service failed, please retry";
    }

    private void sendStreamError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("error", message)));
        } catch (Exception e) {
            log.warn("Agent SSE error send failed: {}", e.getMessage());
        }
        emitter.complete();
    }
}
