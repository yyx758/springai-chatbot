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
    private final KeywordExtractor keywordExtractor;

    /**
     * 执行混合检索。
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

    // ========== 向量检索 ==========

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

    // ========== 关键词检索（优化版）==========

    private List<HybridCandidate> searchKeyword(Long userId, String query, int topK) {
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getUserId, userId)
                        .eq(KnowledgeDocument::getEnabled, true));

        if (documents.isEmpty()) {
            return List.of();
        }

        // 从 query 中提取各类关键词
        List<String> queryBigrams = keywordExtractor.extractBigrams(query);
        List<String> queryPhrases = keywordExtractor.extractQueryPhrases(query);
        List<String> queryTechTerms = keywordExtractor.extractTechnicalTerms(query);

        // 对每篇文档评分
        List<KeywordMatchResult> scoredDocs = new ArrayList<>();
        for (KnowledgeDocument doc : documents) {
            KeywordMatchResult result = scoreDocument(doc, query, queryBigrams, queryPhrases, queryTechTerms);
            if (result.getKeywordScore() > 0) {
                scoredDocs.add(result);
            }
        }

        // 按分数降序排列
        scoredDocs.sort((a, b) -> Double.compare(b.getKeywordScore(), a.getKeywordScore()));

        // 转换为 HybridCandidate
        List<HybridCandidate> candidates = new ArrayList<>();
        for (int rank = 0; rank < scoredDocs.size(); rank++) {
            KeywordMatchResult r = scoredDocs.get(rank);
            candidates.add(HybridCandidate.builder()
                    .chunkId(r.getDocumentId() + "_0")
                    .documentId(r.getDocumentId())
                    .title(r.getTitle())
                    .snippet(r.getSnippet())
                    .keywordRank(rank + 1)
                    .keywordScore(r.getKeywordScore())
                    .matchedTerms(r.getMatchedTerms())
                    .build());
        }
        return candidates;
    }

    /**
     * 对单篇文档进行关键词评分。
     * 使用 KeywordExtractor 提取高质量词，按规则加权打分。
     */
    private KeywordMatchResult scoreDocument(
            KnowledgeDocument doc,
            String query,
            List<String> queryBigrams,
            List<String> queryPhrases,
            List<String> queryTechTerms) {

        String title = valueOrEmpty(doc.getTitle()).toLowerCase();
        String content = valueOrEmpty(doc.getContent()).toLowerCase();

        double score = 0;
        List<KeywordMatchResult.MatchDetail> details = new ArrayList<>();

        // ===== 1. queryPhrase 命中 title（+8）=====
        for (String phrase : queryPhrases) {
            if (title.contains(phrase)) {
                score += 8;
                details.add(KeywordMatchResult.MatchDetail.builder()
                        .term(phrase).matchType("QUERY_PHRASE_IN_TITLE").score(8).build());
            } else if (content.contains(phrase)) {
                score += 4;
                details.add(KeywordMatchResult.MatchDetail.builder()
                        .term(phrase).matchType("QUERY_PHRASE_IN_CONTENT").score(4).build());
            }
        }

        // ===== 2. titlePhrase 命中 query（+8）=====
        List<String> titlePhrases = keywordExtractor.extractPhrases(doc.getTitle());
        for (String phrase : titlePhrases) {
            if (query.toLowerCase().contains(phrase)) {
                score += 8;
                details.add(KeywordMatchResult.MatchDetail.builder()
                        .term(phrase).matchType("TITLE_PHRASE_IN_QUERY").score(8).build());
            }
        }

        // ===== 3. 高质量 2-gram 命中 title/content =====
        for (String bigram : queryBigrams) {
            if (title.contains(bigram)) {
                score += 2;
                details.add(KeywordMatchResult.MatchDetail.builder()
                        .term(bigram).matchType("BIGRAM_IN_TITLE").score(2).build());
            } else if (content.contains(bigram)) {
                score += 1;
                details.add(KeywordMatchResult.MatchDetail.builder()
                        .term(bigram).matchType("BIGRAM_IN_CONTENT").score(1).build());
            }
        }

        // ===== 4. 英文技术词命中 =====
        for (String term : queryTechTerms) {
            if (title.contains(term.toLowerCase())) {
                score += 8;
                details.add(KeywordMatchResult.MatchDetail.builder()
                        .term(term).matchType("TECHNICAL_IN_TITLE").score(8).build());
            } else if (content.contains(term.toLowerCase())) {
                score += 4;
                details.add(KeywordMatchResult.MatchDetail.builder()
                        .term(term).matchType("TECHNICAL_IN_CONTENT").score(4).build());
            }
        }

        // 构建 matchedTerms（只保留真正参与评分的高质量词）
        List<String> matchedTerms = details.stream()
                .map(d -> d.getTerm())
                .distinct()
                .toList();

        // 构建 snippet
        String snippet = buildSnippet(content, queryBigrams);

        return KeywordMatchResult.builder()
                .chunkId(doc.getId() + "_0")
                .documentId(doc.getId())
                .title(doc.getTitle())
                .snippet(snippet)
                .keywordScore(score)
                .matchedTerms(matchedTerms)
                .matchedDetails(details)
                .build();
    }

    // ========== snippet 提取 ==========

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
                    truncate(query, 30), c.getVectorRank(), c.getChunkId(), c.getDocumentId(),
                    truncate(c.getTitle(), 25), c.getVectorScore());
        }
    }

    private void logKeywordResults(String query, List<HybridCandidate> candidates) {
        for (HybridCandidate c : candidates) {
            log.info("[HybridRAG-Keyword] query='{}', rank={}, chunkId={}, docId={}, title='{}', keywordScore={}, matchedTerms={}",
                    truncate(query, 30), c.getKeywordRank(), c.getChunkId(), c.getDocumentId(),
                    truncate(c.getTitle(), 25), c.getKeywordScore(), c.getMatchedTerms());
        }
    }

    // ========== 工具方法 ==========

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private List<RagReference> toRagReferences(List<HybridCandidate> candidates) {
        List<RagReference> refs = new ArrayList<>();
        for (HybridCandidate c : candidates) {
            refs.add(RagReference.builder()
                    .chunkId(c.getChunkId())
                    .documentId(c.getDocumentId())
                    .title(c.getTitle())
                    .snippet(c.getSnippet())
                    .score(c.getFinalScore())
                    .build());
        }
        return refs;
    }
}
