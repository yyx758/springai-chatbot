package com.example.chatbot.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合检索编排服务。
 * <p>
 * 职责：
 * 1. 分析查询意图，确定权重
 * 2. 并行执行向量检索和关键词检索
 * 3. 调用 HybridRanker 融合排序
 * 4. 返回最终进入 prompt 的 RagReference 列表
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridSearchService {

    private final QueryIntentAnalyzer intentAnalyzer;
    private final VectorRagService vectorRagService;
    private final HybridRanker hybridRanker;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    /**
     * 执行混合检索。
     *
     * @param userId  用户 ID
     * @param query   用户查询
     * @param topK    最终返回数量
     * @return 融合排序后的 RagReference 列表
     */
    public List<RagReference> search(Long userId, String query, int topK) {
        // Step 1: 分析查询意图
        QueryIntent intent = intentAnalyzer.analyze(query);

        // Step 2: 执行两路检索
        List<HybridCandidate> vectorCandidates = searchVector(userId, query, topK);
        List<HybridCandidate> keywordCandidates = searchKeyword(userId, query, topK);

        // Step 3: 打印检索原始排名
        logVectorResults(query, vectorCandidates);
        logKeywordResults(query, keywordCandidates);

        // Step 4: 融合排序
        List<HybridCandidate> selected = hybridRanker.rank(vectorCandidates, keywordCandidates, intent, topK);

        // Step 5: 转换为 RagReference
        return toRagReferences(selected);
    }

    /**
     * 向量检索：调用 Embedding API → PGVector 相似度搜索。
     * 返回候选列表，不做阈值过滤（由 HybridRanker 处理）。
     */
    private List<HybridCandidate> searchVector(Long userId, String query, int topK) {
        if (!vectorRagService.isEnabled()) {
            return List.of();
        }
        try {
            List<RagReference> vectorRefs = vectorRagService.retrieve(userId, query, Math.max(topK * 2, 10));
            List<HybridCandidate> candidates = new ArrayList<>();
            for (int rank = 0; rank < vectorRefs.size(); rank++) {
                RagReference ref = vectorRefs.get(rank);
                String chunkId = ref.getChunkId() != null ? ref.getChunkId() : ref.getDocumentId() + "_0";
                candidates.add(HybridCandidate.builder()
                        .chunkId(chunkId)
                        .documentId(ref.getDocumentId())
                        .title(ref.getTitle())
                        .snippet(ref.getSnippet())
                        .vectorRank(rank + 1)
                        .vectorScore(ref.getScore())
                        .build());
            }
            return candidates;
        } catch (Exception e) {
            log.warn("[HybridRAG] vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词检索：自研评分算法。
     */
    private List<HybridCandidate> searchKeyword(Long userId, String query, int topK) {
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getUserId, userId)
                        .eq(KnowledgeDocument::getEnabled, true));

        if (documents.isEmpty()) {
            return List.of();
        }

        List<String> keywords = buildKeywords(query);
        List<ScoredDoc> scoredDocs = new ArrayList<>();

        for (KnowledgeDocument doc : documents) {
            int score = calculateKeywordScore(doc, query, keywords);
            List<String> matched = extractMatchedTerms(doc, keywords);
            if (score > 0) {
                scoredDocs.add(new ScoredDoc(doc, score, matched));
            }
        }

        // 按分数降序排列
        scoredDocs.sort((a, b) -> Integer.compare(b.score, a.score));

        List<HybridCandidate> candidates = new ArrayList<>();
        for (int rank = 0; rank < scoredDocs.size(); rank++) {
            ScoredDoc sd = scoredDocs.get(rank);
            candidates.add(HybridCandidate.builder()
                    .chunkId(sd.doc.getId() + "_0")
                    .documentId(sd.doc.getId())
                    .title(sd.doc.getTitle())
                    .snippet(buildSnippet(sd.doc.getContent(), keywords))
                    .keywordRank(rank + 1)
                    .keywordScore((double) sd.score)
                    .matchedTerms(sd.matchedTerms)
                    .build());
        }
        return candidates;
    }

    // ========== 关键词评分逻辑（优化版）==========

    /**
     * 优化后的关键词评分：区分泛词和专有词。
     */
    private int calculateKeywordScore(KnowledgeDocument doc, String query, List<String> keywords) {
        String title = valueOrEmpty(doc.getTitle()).toLowerCase();
        String content = valueOrEmpty(doc.getContent()).toLowerCase();
        String tags = valueOrEmpty(doc.getTags()).toLowerCase();
        int score = 0;

        // === 第一层：精确匹配（整个查询） ===
        if (title.contains(query.toLowerCase())) score += 40;
        if (content.contains(query.toLowerCase())) score += 30;
        if (tags.contains(query.toLowerCase())) score += 20;

        // === 第二层：高价值关键词匹配 ===
        for (String keyword : keywords) {
            if (keyword.length() < 2) continue;

            boolean isHighValue = keyword.matches("[A-Z][a-zA-Z0-9]+") ||
                    keyword.matches("[a-z]+_[a-z]+") ||
                    keyword.matches("(?i)(docker|kubectl|psql|mysql|grep|curl|ssh)") ||
                    keyword.matches("(?i)(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE)");

            boolean titleHit = title.contains(keyword);
            boolean contentHit = content.contains(keyword);
            boolean tagHit = tags.contains(keyword);

            if (titleHit) {
                score += isHighValue ? 8 : 4;
            }
            if (contentHit) {
                score += isHighValue ? 3 : 1;
            }
            if (tagHit) {
                score += isHighValue ? 5 : 2;
            }
        }

        // === 第三层：连续短语命中加分 ===
        if (keywords.size() >= 2) {
            String twoGram = keywords.get(0).substring(0, Math.min(2, keywords.get(0).length()));
            if (content.contains(twoGram) && title.contains(twoGram)) {
                score += 3;  // 标题和正文都命中连续短语
            }
        }

        // === 第四层：多个核心词共同命中加分 ===
        long hitCount = keywords.stream()
                .filter(kw -> kw.length() >= 2 && (title.contains(kw) || content.contains(kw)))
                .count();
        if (hitCount >= 3) {
            score += 2;
        }

        return score;
    }

    private List<String> extractMatchedTerms(KnowledgeDocument doc, List<String> keywords) {
        String title = valueOrEmpty(doc.getTitle()).toLowerCase();
        String content = valueOrEmpty(doc.getContent()).toLowerCase();
        List<String> matched = new ArrayList<>();
        for (String kw : keywords) {
            if (kw.length() >= 2 && (title.contains(kw) || content.contains(kw))) {
                matched.add(kw);
            }
        }
        return matched;
    }

    // ========== 分词逻辑 ==========

    private List<String> buildKeywords(String query) {
        String normalized = query.toLowerCase();
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
        return keywords.stream().distinct().toList();
    }

    private String buildSnippet(String content, List<String> keywords) {
        if (content == null || content.isBlank()) return "";
        String safeContent = content.trim();
        String lower = safeContent.toLowerCase();
        for (String keyword : keywords) {
            if (keyword.length() < 2) continue;
            int idx = lower.indexOf(keyword);
            if (idx >= 0) {
                int start = Math.max(0, idx - 80);
                int end = Math.min(safeContent.length(), idx + 80);
                String snippet = safeContent.substring(start, end);
                if (start > 0) snippet = "..." + snippet;
                if (end < safeContent.length()) snippet = snippet + "...";
                return snippet;
            }
        }
        return safeContent.substring(0, Math.min(160, safeContent.length()));
    }

    // ========== 日志 ==========

    private void logVectorResults(String query, List<HybridCandidate> candidates) {
        for (HybridCandidate c : candidates) {
            log.info("[HybridRAG-Vector] query='{}', rank={}, chunkId={}, docId={}, title='{}', vectorScore={}",
                    query.length() > 30 ? query.substring(0, 30) + "..." : query,
                    c.getVectorRank(), c.getChunkId(), c.getDocumentId(),
                    c.getTitle() != null && c.getTitle().length() > 25
                            ? c.getTitle().substring(0, 25) + "..." : c.getTitle(),
                    c.getVectorScore());
        }
    }

    private void logKeywordResults(String query, List<HybridCandidate> candidates) {
        for (HybridCandidate c : candidates) {
            log.info("[HybridRAG-Keyword] query='{}', rank={}, chunkId={}, docId={}, title='{}', keywordScore={}, matchedTerms={}",
                    query.length() > 30 ? query.substring(0, 30) + "..." : query,
                    c.getKeywordRank(), c.getChunkId(), c.getDocumentId(),
                    c.getTitle() != null && c.getTitle().length() > 25
                            ? c.getTitle().substring(0, 25) + "..." : c.getTitle(),
                    c.getKeywordScore(),
                    c.getMatchedTerms());
        }
    }

    // ========== 工具方法 ==========

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<RagReference> toRagReferences(List<HybridCandidate> candidates) {
        List<RagReference> refs = new ArrayList<>();
        for (HybridCandidate c : candidates) {
            refs.add(RagReference.builder()
                    .documentId(c.getDocumentId())
                    .title(c.getTitle())
                    .snippet(c.getSnippet())
                    .score(c.getFinalScore())
                    .build());
        }
        return refs;
    }

    private record ScoredDoc(KnowledgeDocument doc, int score, List<String> matchedTerms) {}
}
