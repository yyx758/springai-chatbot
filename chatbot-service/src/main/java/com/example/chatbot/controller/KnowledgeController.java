package com.example.chatbot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.chatbot.dto.KnowledgeDocumentCreateRequest;
import com.example.chatbot.dto.KnowledgeSearchRequest;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.security.AuthInterceptor;
import com.example.chatbot.service.RagService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class KnowledgeController {

    private final RagService ragService;

    @PostMapping("/documents")
    public ResponseEntity<KnowledgeDocument> createDocument(
            @Valid @RequestBody KnowledgeDocumentCreateRequest request,
            HttpServletRequest httpServletRequest
    ) {
        Long userId = resolveUserId(httpServletRequest);
        return ResponseEntity.ok(ragService.createDocument(userId, request));
    }

    @GetMapping("/documents")
    public ResponseEntity<IPage<KnowledgeDocument>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest httpServletRequest
    ) {
        Long userId = resolveUserId(httpServletRequest);
        return ResponseEntity.ok(ragService.listDocuments(userId, page, size));
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<KnowledgeDocument> getDocument(
            @PathVariable Long documentId,
            HttpServletRequest httpServletRequest
    ) {
        Long userId = resolveUserId(httpServletRequest);
        return ResponseEntity.ok(ragService.getDocument(userId, documentId));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @PathVariable Long documentId,
            HttpServletRequest httpServletRequest
    ) {
        Long userId = resolveUserId(httpServletRequest);
        ragService.deleteDocument(userId, documentId);
        return ResponseEntity.ok(Map.of("success", true, "message", "知识文档已删除"));
    }

    @PostMapping("/search")
    public ResponseEntity<List<RagReference>> search(
            @Valid @RequestBody KnowledgeSearchRequest request,
            HttpServletRequest httpServletRequest
    ) {
        Long userId = resolveUserId(httpServletRequest);
        return ResponseEntity.ok(ragService.retrieveReferences(userId, request.getQuery(), request.getTopK()));
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("未登录或登录已过期");
        }
        return Long.valueOf(String.valueOf(userId));
    }
}
