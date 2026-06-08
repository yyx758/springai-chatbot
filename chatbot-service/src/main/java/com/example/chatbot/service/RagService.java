package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.chatbot.dto.KnowledgeDocumentCreateRequest;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.kafka.KnowledgeEvent;
import com.example.chatbot.kafka.KnowledgeEventProducer;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.rag.RagProperties;
import com.example.chatbot.rag.VectorIndexingService;
import com.example.chatbot.rag.VectorRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 10;
    private static final int SNIPPET_RADIUS = 80;

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeEventProducer knowledgeEventProducer;
    private final VectorRagService vectorRagService;
    private final VectorIndexingService vectorIndexingService;
    private final RagProperties ragProperties;
    private final FileServiceClient fileServiceClient;

    public KnowledgeDocument createDocument(Long userId, KnowledgeDocumentCreateRequest request) {
        String indexStatus = vectorRagService.isEnabled() ? "PENDING_INDEX" : "VECTOR_DISABLED";
        KnowledgeDocument document = KnowledgeDocument.builder()
                .userId(userId)
                .title(request.getTitle().trim())
                .content(request.getContent().trim())
                .fileKey(request.getFileKey() != null ? request.getFileKey().trim() : null)
                .tags(request.getTags() == null ? null : request.getTags().trim())
                .enabled(request.getEnabled() == null ? true : request.getEnabled())
                .indexStatus(indexStatus)
                .build();
        knowledgeDocumentMapper.insert(document);

        ensureGeneratedFile(userId, document);

        // 发布知识库创建事件，通知 ChatService 刷新 RAG 缓存
        knowledgeEventProducer.sendKnowledgeEvent(KnowledgeEvent.builder()
                .eventType("KNOWLEDGE_CREATED")
                .documentId(document.getId())
                .userId(userId)
                .title(document.getTitle())
                .eventTime(LocalDateTime.now())
                .build());

        return document;
    }

    public IPage<KnowledgeDocument> listDocuments(Long userId, int page, int size) {
        IPage<KnowledgeDocument> result = knowledgeDocumentMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getUserId, userId)
                        .orderByDesc(KnowledgeDocument::getUpdatedTime)
                        .orderByDesc(KnowledgeDocument::getId)
        );
        result.getRecords().forEach(document -> ensureGeneratedFile(userId, document));
        return result;
    }

    public void deleteDocument(Long userId, Long documentId) {
        KnowledgeDocument existed = knowledgeDocumentMapper.selectById(documentId);
        if (existed == null) {
            throw new IllegalArgumentException("知识文档不存在");
        }
        if (!userId.equals(existed.getUserId())) {
            throw new IllegalArgumentException("无权删除该知识文档");
        }
        knowledgeDocumentMapper.deleteById(documentId);

        // 发布知识库删除事件，通知 ChatService 刷新 RAG 缓存
        knowledgeEventProducer.sendKnowledgeEvent(KnowledgeEvent.builder()
                .eventType("KNOWLEDGE_DELETED")
                .documentId(documentId)
                .userId(userId)
                .title(existed.getTitle())
                .eventTime(LocalDateTime.now())
                .build());
    }

    public List<RagReference> retrieveReferences(Long userId, String query, Integer topK) {
        String mode = ragProperties.getMode() == null ? "keyword" : ragProperties.getMode().trim().toLowerCase(Locale.ROOT);
        int finalTopK = normalizeTopK(topK);

        if ("vector".equals(mode)) {
            try {
                List<RagReference> vectorResults = vectorRagService.retrieve(userId, query, finalTopK);
                if (!vectorResults.isEmpty() || !ragProperties.isFallbackToKeyword()) {
                    return vectorResults;
                }
            } catch (Exception e) {
                log.warn("Vector RAG failed, fallbackToKeyword={}, error={}", ragProperties.isFallbackToKeyword(), e.getMessage());
                if (!ragProperties.isFallbackToKeyword()) {
                    return List.of();
                }
            }
            return retrieveKeywordReferences(userId, query, finalTopK);
        }

        if ("hybrid".equals(mode)) {
            List<RagReference> keywordResults = retrieveKeywordReferences(userId, query, finalTopK);
            try {
                List<RagReference> vectorResults = vectorRagService.retrieve(userId, query, finalTopK);
                return mergeReferences(vectorResults, keywordResults, finalTopK);
            } catch (Exception e) {
                log.warn("Hybrid vector retrieval failed, using keyword results: {}", e.getMessage());
                return keywordResults;
            }
        }

        return retrieveKeywordReferences(userId, query, finalTopK);
    }

    public Map<String, Object> reindexUserDocuments(Long userId) {
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getUserId, userId)
                        .eq(KnowledgeDocument::getEnabled, true)
        );
        int requested = 0;
        for (KnowledgeDocument document : documents) {
            vectorIndexingService.indexDocument(userId, document.getId());
            requested++;
        }
        return Map.of(
                "success", true,
                "vectorEnabled", vectorIndexingService.isEnabled(),
                "requested", requested
        );
    }

    public KnowledgeDocument getDocument(Long userId, Long documentId) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("鐭ヨ瘑鏂囨。涓嶅瓨鍦?");
        }
        if (!userId.equals(document.getUserId())) {
            throw new IllegalArgumentException("鏃犳潈璁块棶璇ョ煡璇嗘枃妗?");
        }
        ensureGeneratedFile(userId, document);
        return document;
    }

    public List<RagReference> retrieveKeywordReferences(Long userId, String query, Integer topK) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isBlank()) {
            return List.of();
        }

        int finalTopK = normalizeTopK(topK);
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getUserId, userId)
                        .eq(KnowledgeDocument::getEnabled, true)
        );

        if (documents.isEmpty()) {
            return List.of();
        }

        List<String> keywords = buildKeywords(safeQuery);
        List<ScoredDocument> scoredDocuments = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            int score = calculateScore(document, safeQuery, keywords);
            if (score > 0) {
                scoredDocuments.add(new ScoredDocument(document, score));
            }
        }

        return scoredDocuments.stream()
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed()
                        .thenComparing(sd -> sd.document().getId(), Comparator.reverseOrder()))
                .limit(finalTopK)
                .map(sd -> RagReference.builder()
                        .documentId(sd.document().getId())
                        .title(sd.document().getTitle())
                        .snippet(buildSnippet(sd.document().getContent(), keywords))
                        .score(sd.score())
                        .build())
                .collect(Collectors.toList());
    }

    private List<RagReference> mergeReferences(List<RagReference> vectorResults, List<RagReference> keywordResults, int topK) {
        Map<Long, RagReference> merged = new LinkedHashMap<>();
        for (RagReference reference : vectorResults) {
            merged.put(reference.getDocumentId(), reference);
        }
        for (RagReference reference : keywordResults) {
            merged.merge(reference.getDocumentId(), reference, (existing, incoming) -> {
                int existingScore = existing.getScore() == null ? 0 : existing.getScore();
                int incomingScore = incoming.getScore() == null ? 0 : incoming.getScore();
                return incomingScore > existingScore ? incoming : existing;
            });
        }
        return merged.values().stream()
                .sorted(Comparator.comparingInt((RagReference r) -> r.getScore() == null ? 0 : r.getScore()).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    public String buildKnowledgePrompt(List<RagReference> references) {
        if (references == null || references.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("以下是可用知识片段，请优先基于这些内容回答，并在答案中尽量保持事实一致：\n");
        for (int i = 0; i < references.size(); i++) {
            RagReference reference = references.get(i);
            builder.append("[").append(i + 1).append("] ")
                    .append(reference.getTitle()).append("\n")
                    .append(reference.getSnippet()).append("\n");
        }
        builder.append("如果知识片段无法覆盖问题，请明确说明并给出保守回答。");
        return builder.toString();
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<String> buildKeywords(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        List<String> keywords = new ArrayList<>();
        keywords.add(normalized);
        for (String token : normalized.split("[\\s,，。！？!?:：;；、]+")) {
            if (token.length() >= 2) {
                keywords.add(token);
            }
        }
        if (!normalized.contains(" ") && normalized.length() >= 4) {
            for (int i = 0; i < normalized.length() - 1; i++) {
                String gram = normalized.substring(i, i + 2);
                if (!gram.isBlank()) {
                    keywords.add(gram);
                }
            }
        }
        return keywords.stream().distinct().collect(Collectors.toList());
    }

    private int calculateScore(KnowledgeDocument document, String query, List<String> keywords) {
        String title = valueOrEmpty(document.getTitle()).toLowerCase(Locale.ROOT);
        String content = valueOrEmpty(document.getContent()).toLowerCase(Locale.ROOT);
        String tags = valueOrEmpty(document.getTags()).toLowerCase(Locale.ROOT);
        int score = 0;

        if (title.contains(query.toLowerCase(Locale.ROOT))) {
            score += 40;
        }
        if (content.contains(query.toLowerCase(Locale.ROOT))) {
            score += 30;
        }
        if (tags.contains(query.toLowerCase(Locale.ROOT))) {
            score += 20;
        }

        for (String keyword : keywords) {
            if (keyword.length() < 2) {
                continue;
            }
            if (title.contains(keyword)) {
                score += 8 + Math.min(keyword.length(), 6);
            }
            if (content.contains(keyword)) {
                score += 4 + Math.min(keyword.length(), 4);
            }
            if (tags.contains(keyword)) {
                score += 5;
            }
        }
        return score;
    }

    private String buildSnippet(String content, List<String> keywords) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String safeContent = content.trim();
        String lower = safeContent.toLowerCase(Locale.ROOT);
        int hitIndex = -1;
        for (String keyword : keywords) {
            if (keyword.length() < 2) {
                continue;
            }
            int idx = lower.indexOf(keyword);
            if (idx >= 0) {
                hitIndex = idx;
                break;
            }
        }

        if (hitIndex < 0) {
            return safeContent.substring(0, Math.min(160, safeContent.length()));
        }

        int start = Math.max(0, hitIndex - SNIPPET_RADIUS);
        int end = Math.min(safeContent.length(), hitIndex + SNIPPET_RADIUS);
        String snippet = safeContent.substring(start, end);
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < safeContent.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ScoredDocument(KnowledgeDocument document, int score) {
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void ensureGeneratedFile(Long userId, KnowledgeDocument document) {
        if (document == null || hasText(document.getFileKey()) || !hasText(document.getContent())) {
            return;
        }
        Map<String, Object> fileInfo = fileServiceClient.createGeneratedKnowledgeMarkdown(
                userId,
                document.getId(),
                document.getTitle(),
                document.getContent());
        if (fileInfo != null && fileInfo.get("fileKey") != null) {
            document.setFileKey(String.valueOf(fileInfo.get("fileKey")));
            knowledgeDocumentMapper.updateById(document);
        }
    }
}
