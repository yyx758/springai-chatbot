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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RagService {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 10;
    private static final int SNIPPET_RADIUS = 80;

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeEventProducer knowledgeEventProducer;

    public KnowledgeDocument createDocument(Long userId, KnowledgeDocumentCreateRequest request) {
        KnowledgeDocument document = KnowledgeDocument.builder()
                .userId(userId)
                .title(request.getTitle().trim())
                .content(request.getContent().trim())
                .fileKey(request.getFileKey() != null ? request.getFileKey().trim() : null)
                .tags(request.getTags() == null ? null : request.getTags().trim())
                .enabled(request.getEnabled() == null ? true : request.getEnabled())
                .build();
        knowledgeDocumentMapper.insert(document);

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
        return knowledgeDocumentMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getUserId, userId)
                        .orderByDesc(KnowledgeDocument::getUpdatedTime)
                        .orderByDesc(KnowledgeDocument::getId)
        );
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
}
