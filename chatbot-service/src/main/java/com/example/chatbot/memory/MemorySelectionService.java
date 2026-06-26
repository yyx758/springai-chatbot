package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemorySelectionService {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private final LongTermMemoryService memoryService;
    private final MemoryIndexService memoryIndexService;
    private final MemoryPromptBuilder promptBuilder;
    private final MemoryProperties properties;
    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Autowired
    public MemorySelectionService(LongTermMemoryService memoryService,
                                  MemoryIndexService memoryIndexService,
                                  MemoryPromptBuilder promptBuilder,
                                  MemoryProperties properties,
                                  ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
                                  ObjectProvider<OllamaChatModel> ollamaChatModelProvider) {
        this(memoryService,
                memoryIndexService,
                promptBuilder,
                properties,
                getIfAvailable(openAiChatModelProvider),
                getIfAvailable(ollamaChatModelProvider));
    }

    public MemorySelectionService(LongTermMemoryService memoryService,
                                  MemoryIndexService memoryIndexService,
                                  MemoryPromptBuilder promptBuilder,
                                  MemoryProperties properties) {
        this(memoryService, memoryIndexService, promptBuilder, properties, (OpenAiChatModel) null, (OllamaChatModel) null);
    }

    private MemorySelectionService(LongTermMemoryService memoryService,
                                   MemoryIndexService memoryIndexService,
                                   MemoryPromptBuilder promptBuilder,
                                   MemoryProperties properties,
                                   OpenAiChatModel openAiChatModel,
                                   OllamaChatModel ollamaChatModel) {
        this.memoryService = memoryService;
        this.memoryIndexService = memoryIndexService;
        this.promptBuilder = promptBuilder;
        this.properties = properties;
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
    }

    public MemorySelectionPreview selectDetailPreview(Long userId, String projectKey, String userInput) {
        List<MemoryIndexItem> index = memoryIndexService.loadIndex(userId, projectKey);
        SelectionDecision decision = selectIds(index, userInput);
        List<Long> selectedIds = decision.ids();
        List<AgentLongTermMemory> details = memoryService.loadActiveDetails(userId, selectedIds, false);
        String prompt = promptBuilder.buildDetailPrompt(trimDetails(details));
        return MemorySelectionPreview.builder()
                .index(index)
                .selectedIds(selectedIds)
                .details(details)
                .prompt(prompt)
                .fallback(decision.fallback())
                .reason(decision.reason())
                .build();
    }

    private SelectionDecision selectIds(List<MemoryIndexItem> index, String userInput) {
        if (index == null || index.isEmpty()) {
            return new SelectionDecision(List.of(), false, "empty memory index");
        }
        List<Long> llmIds = selectIdsByLlm(index, userInput);
        if (!llmIds.isEmpty()) {
            return new SelectionDecision(llmIds, false, "LLM_SIDE_QUERY selected memory detail ids");
        }
        String query = safe(userInput).toLowerCase(Locale.ROOT);
        Set<Long> selected = new LinkedHashSet<>();
        for (MemoryIndexItem item : index) {
            String haystack = (safe(item.getName()) + " " + safe(item.getDescription()) + " " + safe(item.getLoadHint()))
                    .toLowerCase(Locale.ROOT);
            if (isRelevant(query, haystack, item)) {
                selected.add(item.getId());
            }
            if (selected.size() >= Math.max(1, properties.getMaxSelectedDetails())) {
                break;
            }
        }
        if (selected.isEmpty() && !query.isBlank()) {
            index.stream()
                    .filter(item -> "project".equals(item.getMemoryType()) || "feedback".equals(item.getMemoryType()))
                    .limit(Math.max(1, Math.min(2, properties.getMaxSelectedDetails())))
                    .map(MemoryIndexItem::getId)
                    .forEach(selected::add);
        }
        return new SelectionDecision(new ArrayList<>(selected), true, "rule-based fallback selected memory detail ids");
    }

    private List<Long> selectIdsByLlm(List<MemoryIndexItem> index, String userInput) {
        if (!properties.isDetailSelectionEnabled() || !"LLM_SIDE_QUERY".equalsIgnoreCase(properties.getSelectMode())) {
            return List.of();
        }
        ChatModel model = resolveModel();
        if (model == null) {
            return List.of();
        }
        try {
            String indexText = index.stream()
                    .map(item -> "id=%d type=%s name=%s description=%s load_hint=%s".formatted(
                            item.getId(),
                            safe(item.getMemoryType()),
                            safe(item.getName()),
                            safe(item.getDescription()),
                            safe(item.getLoadHint())))
                    .reduce("", (left, right) -> left + "\n" + right);
            String response = model.call(new Prompt(List.of(
                    new SystemMessage("Select relevant long-term memory ids. Respond JSON only, format: {\"selectedIds\":[1,2],\"reason\":\"...\"}. Only use ids from the provided index."),
                    new UserMessage("Current user input:\n" + safe(userInput) + "\n\nMemory index:\n" + indexText)
            ))).getResult().getOutput().getText();
            return sanitizeSelectedIds(parseIds(response), index);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private ChatModel resolveModel() {
        if (openAiChatModel != null && openAiApiKey != null && !openAiApiKey.isBlank()
                && !"sk-placeholder".equals(openAiApiKey)) {
            return openAiChatModel;
        }
        return ollamaChatModel;
    }

    private List<Long> parseIds(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        Matcher matcher = NUMBER_PATTERN.matcher(response);
        List<Long> ids = new ArrayList<>();
        while (matcher.find()) {
            ids.add(Long.valueOf(matcher.group()));
        }
        return ids;
    }

    private List<Long> sanitizeSelectedIds(List<Long> ids, List<MemoryIndexItem> index) {
        Set<Long> allowed = new LinkedHashSet<>();
        index.stream()
                .sorted(Comparator.comparing(MemoryIndexItem::getId))
                .map(MemoryIndexItem::getId)
                .forEach(allowed::add);
        return ids.stream()
                .filter(allowed::contains)
                .distinct()
                .limit(Math.max(1, properties.getMaxSelectedDetails()))
                .toList();
    }

    private boolean isRelevant(String query, String haystack, MemoryIndexItem item) {
        if (query.isBlank()) {
            return false;
        }
        for (String token : query.split("[\\s\\p{Punct}，。！？；、]+")) {
            if (token.length() >= 2 && haystack.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return ("project".equals(item.getMemoryType()) && touchesProject(query))
                || ("feedback".equals(item.getMemoryType()) && touchesPreference(query));
    }

    private boolean touchesProject(String query) {
        return query.contains("项目")
                || query.contains("代码")
                || query.contains("部署")
                || query.contains("生产")
                || query.contains("gateway")
                || query.contains("git")
                || query.contains("review");
    }

    private boolean touchesPreference(String query) {
        return query.contains("回答")
                || query.contains("实现")
                || query.contains("验证")
                || query.contains("总结")
                || query.contains("偏好");
    }

    private List<AgentLongTermMemory> trimDetails(List<AgentLongTermMemory> details) {
        int maxPerItem = Math.max(1, properties.getMaxDetailCharsPerItem());
        int maxTotal = Math.max(1, properties.getMaxTotalDetailChars());
        int total = 0;
        List<AgentLongTermMemory> trimmed = new ArrayList<>();
        for (AgentLongTermMemory detail : details) {
            String content = safe(detail.getContent());
            if (content.length() > maxPerItem) {
                content = content.substring(0, maxPerItem);
            }
            if (total + content.length() > maxTotal) {
                break;
            }
            detail.setContent(content);
            detail.setLastUsedTime(LocalDateTime.now());
            trimmed.add(detail);
            total += content.length();
        }
        return trimmed;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static <T> T getIfAvailable(ObjectProvider<T> provider) {
        try {
            return provider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    private record SelectionDecision(List<Long> ids, boolean fallback, String reason) {
    }
}
