package com.example.chatbot.controller;

import com.example.chatbot.memory.LongTermMemoryRequest;
import com.example.chatbot.memory.LongTermMemoryService;
import com.example.chatbot.memory.MemoryConsolidationService;
import com.example.chatbot.memory.MemoryImportService;
import com.example.chatbot.memory.MemoryIndexService;
import com.example.chatbot.memory.MemoryPromptBuilder;
import com.example.chatbot.memory.MemorySelectionService;
import com.example.chatbot.memory.MemorySuggestionService;
import com.example.chatbot.security.AuthInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/chat/memory")
@RequiredArgsConstructor
public class LongTermMemoryController {

    private final LongTermMemoryService memoryService;
    private final MemoryIndexService memoryIndexService;
    private final MemoryPromptBuilder promptBuilder;
    private final MemorySelectionService memorySelectionService;
    private final MemorySuggestionService memorySuggestionService;
    private final MemoryImportService memoryImportService;
    private final MemoryConsolidationService memoryConsolidationService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String scopeType,
                                                    @RequestParam(required = false) String scopeKey,
                                                    @RequestParam(required = false) String memoryType,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "50") Integer limit,
                                                    HttpServletRequest request) {
        Long userId = resolveUserId(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "memories", memoryService.list(userId, scopeType, scopeKey, memoryType, status, keyword, limit)
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id, HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "memory", memoryService.get(resolveUserId(request), id)
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody LongTermMemoryRequest body,
                                                      HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "memory", memoryService.create(resolveUserId(request), body)
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody LongTermMemoryRequest body,
                                                      HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "memory", memoryService.update(resolveUserId(request), id, body)
        ));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Map<String, Object>> archive(@PathVariable Long id, HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "memory", memoryService.archive(resolveUserId(request), id)
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpServletRequest request) {
        memoryService.delete(resolveUserId(request), id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/index-preview")
    public ResponseEntity<Map<String, Object>> indexPreview(@RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest request) {
        Long userId = resolveUserId(request);
        String projectKey = body == null ? null : String.valueOf(body.getOrDefault("projectKey", ""));
        var items = memoryIndexService.loadIndex(userId, projectKey);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "items", items,
                "prompt", promptBuilder.buildIndexPrompt(items)
        ));
    }

    @PostMapping("/select-detail-preview")
    public ResponseEntity<Map<String, Object>> selectDetailPreview(@RequestBody(required = false) Map<String, Object> body,
                                                                   HttpServletRequest request) {
        Long userId = resolveUserId(request);
        String projectKey = stringValue(body, "projectKey");
        String userInput = stringValue(body, "userInput");
        return ResponseEntity.ok(Map.of(
                "success", true,
                "selection", memorySelectionService.selectDetailPreview(userId, projectKey, userInput)
        ));
    }

    @PostMapping("/suggest")
    public ResponseEntity<Map<String, Object>> suggest(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Long userId = resolveUserId(request);
        String message = stringValue(body, "message");
        var suggestion = memorySuggestionService.suggestFromUserMessage(message);
        if (suggestion.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", true, "suggested", false));
        }
        Long suggestionId = memoryService.createSuggestion(userId, suggestion.get(), stringValue(body, "sessionId"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("suggested", true);
        response.put("suggestionId", suggestionId);
        response.put("suggestion", suggestion.get());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/suggest/{suggestionId}/apply")
    public ResponseEntity<Map<String, Object>> applySuggestion(@PathVariable Long suggestionId,
                                                               HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "memory", memoryService.applySuggestion(resolveUserId(request), suggestionId)
        ));
    }

    @PostMapping("/suggest/{suggestionId}/dismiss")
    public ResponseEntity<Map<String, Object>> dismissSuggestion(@PathVariable Long suggestionId,
                                                                 HttpServletRequest request) {
        memoryService.dismissSuggestion(resolveUserId(request), suggestionId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/import-preview")
    public ResponseEntity<Map<String, Object>> importPreview(@RequestBody(required = false) Map<String, Object> body,
                                                            HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "drafts", memoryImportService.preview(
                        stringValue(body, "content"),
                        stringValue(body, "scopeType"),
                        stringValue(body, "scopeKey"))
        ));
    }

    @PostMapping("/import-apply")
    public ResponseEntity<Map<String, Object>> importApply(@RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        var rawDrafts = (java.util.List<Object>) body.getOrDefault("drafts", java.util.List.of());
        var drafts = rawDrafts.stream()
                .map(raw -> objectMapper.convertValue(raw, LongTermMemoryRequest.class))
                .toList();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "memories", memoryImportService.apply(resolveUserId(request), drafts)
        ));
    }

    @PostMapping("/consolidate-preview")
    public ResponseEntity<Map<String, Object>> consolidatePreview(@RequestBody(required = false) Map<String, Object> body,
                                                                  HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "preview", memoryConsolidationService.preview(
                        resolveUserId(request),
                        stringValue(body, "scopeType"),
                        stringValue(body, "scopeKey"))
        ));
    }

    @PostMapping("/consolidate-apply")
    public ResponseEntity<Map<String, Object>> consolidateApply(@RequestBody MemoryConsolidationService.ConsolidationPreview preview,
                                                                HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "preview", memoryConsolidationService.apply(resolveUserId(request), preview)
        ));
    }

    private String stringValue(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return "";
        }
        return String.valueOf(body.get(key));
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return Long.valueOf(String.valueOf(userId));
    }
}
