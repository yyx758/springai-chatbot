package com.example.chatbot.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索融合排序器。
 * <p>
 * 流程：
 * 1. 按 chunkId 合并向量和关键词候选
 * 2. 计算加权 RRF 分数
 * 3. 规则型 rerank/filter 判断 selected
 * 4. 返回最终进入 prompt 的结果
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridRanker {

    private final HybridRagProperties properties;

    /**
     * 执行完整的融合排序流程。
     */
    public List<HybridCandidate> rank(
            List<HybridCandidate> vectorCandidates,
            List<HybridCandidate> keywordCandidates,
            QueryIntent queryIntent,
            int finalTopK) {

        int effectiveTopK = finalTopK > 0 ? finalTopK : properties.getFinalTopK();

        // Step 1: 按 chunkId 合并候选
        Map<String, HybridCandidate> merged = mergeCandidatesByChunkId(vectorCandidates, keywordCandidates);

        // Step 2: 计算加权 RRF 分数
        calculateWeightedRrf(merged, vectorCandidates, keywordCandidates,
                queryIntent.getVectorWeight(), queryIntent.getKeywordWeight());

        // Step 3: 按 rrfScore 降序排列
        List<HybridCandidate> ranked = merged.values().stream()
                .sorted(Comparator.comparingDouble(HybridCandidate::getRrfScore).reversed())
                .toList();

        // Step 4: 规则型 rerank/filter
        List<HybridCandidate> selected = applyRuleBasedRerankAndFilter(ranked, effectiveTopK);

        // Step 5: 打印完整日志
        logFinalRanking(ranked, selected, queryIntent);

        return selected;
    }

    // ========== Step 1: 按 chunkId 合并 ==========

    private Map<String, HybridCandidate> mergeCandidatesByChunkId(
            List<HybridCandidate> vectorCandidates,
            List<HybridCandidate> keywordCandidates) {

        Map<String, HybridCandidate> merged = new LinkedHashMap<>();

        for (HybridCandidate vc : vectorCandidates) {
            merged.put(vc.getChunkId(), vc);
        }

        for (HybridCandidate kc : keywordCandidates) {
            merged.merge(kc.getChunkId(), kc, (existing, incoming) -> {
                return HybridCandidate.builder()
                        .chunkId(existing.getChunkId())
                        .documentId(existing.getDocumentId() != null ? existing.getDocumentId() : incoming.getDocumentId())
                        .title(existing.getTitle() != null ? existing.getTitle() : incoming.getTitle())
                        .snippet(existing.getSnippet() != null ? existing.getSnippet() : incoming.getSnippet())
                        .vectorRank(existing.getVectorRank())
                        .vectorScore(existing.getVectorScore())
                        .keywordRank(incoming.getKeywordRank())
                        .keywordScore(incoming.getKeywordScore())
                        .matchedTerms(incoming.getMatchedTerms())
                        .build();
            });
        }

        return merged;
    }

    // ========== Step 2: 加权 RRF ==========

    private void calculateWeightedRrf(
            Map<String, HybridCandidate> merged,
            List<HybridCandidate> vectorCandidates,
            List<HybridCandidate> keywordCandidates,
            double vectorWeight,
            double keywordWeight) {

        int k = properties.getRrfK();

        for (int rank = 0; rank < vectorCandidates.size(); rank++) {
            HybridCandidate vc = vectorCandidates.get(rank);
            HybridCandidate candidate = merged.get(vc.getChunkId());
            if (candidate != null) {
                candidate.setRrfScore(candidate.getRrfScore() + vectorWeight / (k + rank + 1));
            }
        }

        for (int rank = 0; rank < keywordCandidates.size(); rank++) {
            HybridCandidate kc = keywordCandidates.get(rank);
            HybridCandidate candidate = merged.get(kc.getChunkId());
            if (candidate != null) {
                candidate.setRrfScore(candidate.getRrfScore() + keywordWeight / (k + rank + 1));
            }
        }

        // finalScore = rrfScore + ruleBoost（当前 ruleBoost = 0）
        for (HybridCandidate candidate : merged.values()) {
            candidate.setRuleBoost(properties.getDefaultRuleBoost());
            candidate.setFinalScore(candidate.getRrfScore() + candidate.getRuleBoost());
        }
    }

    // ========== Step 4: 规则型 rerank/filter ==========

    /**
     * 基于规则的 rerank + filter，逐条判断 selected 和 selectedReason。
     */
    private List<HybridCandidate> applyRuleBasedRerankAndFilter(
            List<HybridCandidate> ranked, int finalTopK) {

        List<HybridCandidate> selected = new ArrayList<>();
        Map<Long, Integer> docChunkCount = new HashMap<>();
        HybridCandidate bestFiltered = null;

        for (HybridCandidate c : ranked) {
            // 规则 7: finalTopK 限制
            if (selected.size() >= finalTopK) {
                c.setSelected(false);
                c.setSelectedReason(SelectReason.FILTERED_EXCEED_FINAL_TOP_K);
                continue;
            }

            // 规则 1: 空内容过滤
            if (isEmptyContent(c)) {
                c.setSelected(false);
                c.setSelectedReason(SelectReason.FILTERED_EMPTY_CONTENT);
                bestFiltered = pickBest(bestFiltered, c);
                continue;
            }

            // 规则 6: 同文档 chunk 限制（在强向量之前检查）
            int docCount = docChunkCount.getOrDefault(c.getDocumentId(), 0);
            if (docCount >= properties.getMaxChunksPerDocument()) {
                c.setSelected(false);
                c.setSelectedReason(SelectReason.FILTERED_SAME_DOCUMENT_LIMIT);
                bestFiltered = pickBest(bestFiltered, c);
                continue;
            }

            Double vs = c.getVectorScore();
            Integer kr = c.getKeywordRank();

            // 规则 2: 强向量相关
            if (vs != null && vs >= properties.getStrongVectorThreshold()) {
                c.setSelected(true);
                c.setSelectedReason(SelectReason.SELECTED_STRONG_VECTOR);
                docChunkCount.merge(c.getDocumentId(), 1, Integer::sum);
                selected.add(c);
                continue;
            }

            // 规则 3: 中等向量 + 关键词辅助
            if (vs != null && vs >= properties.getMediumVectorThreshold() && kr != null
                    && hasNonStopWordKeyword(c)) {
                c.setSelected(true);
                c.setSelectedReason(SelectReason.SELECTED_VECTOR_AND_KEYWORD);
                docChunkCount.merge(c.getDocumentId(), 1, Integer::sum);
                selected.add(c);
                continue;
            }

            // 规则 4: 关键词强命中
            if (hasStrongKeywordSignal(c)) {
                c.setSelected(true);
                c.setSelectedReason(SelectReason.SELECTED_STRONG_KEYWORD);
                docChunkCount.merge(c.getDocumentId(), 1, Integer::sum);
                selected.add(c);
                continue;
            }

            // 规则 5: 弱相关过滤
            c.setSelected(false);
            c.setSelectedReason(SelectReason.FILTERED_NO_VALID_SIGNAL);
            bestFiltered = pickBest(bestFiltered, c);
        }

        // 规则 8: fallback 兜底
        if (selected.isEmpty() && bestFiltered != null
                && bestFiltered.getVectorScore() != null
                && bestFiltered.getVectorScore() >= properties.getFallbackVectorThreshold()) {
            bestFiltered.setSelected(true);
            bestFiltered.setSelectedReason(SelectReason.SELECTED_FALLBACK_BEST_VECTOR);
            selected.add(bestFiltered);
            log.info("[HybridRAG-Final] fallback: all filtered, keeping best vector candidate chunkId={}",
                    bestFiltered.getChunkId());
        }

        return selected;
    }

    /**
     * 选择两个候选中 finalScore 更高的那个。
     * 修复 Java 值传递问题：返回新对象而非修改原引用。
     */
    private HybridCandidate pickBest(HybridCandidate current, HybridCandidate candidate) {
        if (current == null) return candidate;
        return candidate.getFinalScore() > current.getFinalScore() ? candidate : current;
    }

    // ========== 规则辅助方法 ==========

    private boolean isEmptyContent(HybridCandidate c) {
        return c.getSnippet() == null || c.getSnippet().isBlank();
    }

    private boolean hasNonStopWordKeyword(HybridCandidate c) {
        if (c.getMatchedTerms() == null || c.getMatchedTerms().isEmpty()) {
            return false;
        }
        for (String term : c.getMatchedTerms()) {
            if (term.length() >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 关键词强命中判断：
     * keywordScore >= strongKeywordThreshold 且 matchedTerms 中存在高质量词
     */
    private boolean hasStrongKeywordSignal(HybridCandidate c) {
        if (c.getKeywordScore() == null || c.getKeywordScore() < properties.getStrongKeywordThreshold()) {
            return false;
        }
        if (c.getMatchedTerms() == null || c.getMatchedTerms().isEmpty()) {
            return false;
        }
        for (String term : c.getMatchedTerms()) {
            if (term.matches("[A-Z][a-zA-Z0-9]+") ||                      // PascalCase
                term.matches("[a-z]+_[a-z]+") ||                           // snake_case
                term.matches("(?i)(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE)") || // SQL
                term.matches("(?i)(docker|kubectl|psql|mysql|grep|curl|ssh)") || // 命令
                term.matches(".*[\\u4e00-\\u9fff].*") && term.length() >= 2 || // 中文关键词/短语
                term.length() >= 4) {                                       // 长短语
                return true;
            }
        }
        return false;
    }

    // ========== 日志 ==========

    private void logFinalRanking(
            List<HybridCandidate> ranked,
            List<HybridCandidate> selected,
            QueryIntent queryIntent) {

        for (int i = 0; i < ranked.size(); i++) {
            HybridCandidate c = ranked.get(i);
            boolean isSelected = selected.contains(c);
            log.info("[HybridRAG-Final] rank={}, chunkId={}, docId={}, title='{}', "
                            + "vectorRank={}, vectorScore={}, keywordRank={}, keywordScore={}, "
                            + "rrfScore={}, ruleBoost={}, finalScore={}, selected={}, selectedReason={}",
                    i + 1,
                    c.getChunkId(),
                    c.getDocumentId(),
                    preview(c.getTitle(), 30),
                    c.getVectorRank(),
                    c.getVectorScore(),
                    c.getKeywordRank(),
                    c.getKeywordScore(),
                    String.format("%.6f", c.getRrfScore()),
                    String.format("%.6f", c.getRuleBoost()),
                    String.format("%.6f", c.getFinalScore()),
                    isSelected,
                    c.getSelectedReason());
        }

        log.info("[HybridRAG-Final] total candidates={}, selected={}, "
                        + "queryType={}, vectorWeight={}, keywordWeight={}",
                ranked.size(), selected.size(),
                queryIntent.getQueryType(),
                queryIntent.getVectorWeight(),
                queryIntent.getKeywordWeight());
    }

    // ========== 工具方法 ==========

    /**
     * 文本预览：截断 + 去除多余空白。
     */
    public static String preview(String text, int maxLen) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) + "..." : cleaned;
    }
}
