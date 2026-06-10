package com.example.chatbot.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索融合排序器。
 * <p>
 * 职责：
 * 1. 将向量检索和关键词检索的候选结果按 chunkId 合并
 * 2. 计算加权 RRF 分数
 * 3. 根据向量分数 + 关键词命中进行最终过滤
 * 4. 输出进入 prompt 的最终结果
 */
@Component
@Slf4j
public class HybridRanker {

    private static final int RRF_K = 60;
    private static final int FINAL_TOP_K = 5;

    // 最终过滤阈值
    private static final double STRONG_THRESHOLD = 0.55;
    private static final double MEDIUM_THRESHOLD = 0.35;
    private static final double FALLBACK_THRESHOLD = 0.45;

    /**
     * 执行完整的融合排序流程。
     *
     * @param vectorCandidates   向量检索结果（已按相似度排序）
     * @param keywordCandidates  关键词检索结果（已按评分排序）
     * @param queryIntent        查询意图（包含权重）
     * @param finalTopK          最终返回数量
     * @return 最终进入 prompt 的候选结果
     */
    public List<HybridCandidate> rank(
            List<HybridCandidate> vectorCandidates,
            List<HybridCandidate> keywordCandidates,
            QueryIntent queryIntent,
            int finalTopK) {

        if (finalTopK <= 0) {
            finalTopK = FINAL_TOP_K;
        }

        // Step 1: 按 chunkId 合并候选
        Map<String, HybridCandidate> merged = mergeCandidatesByChunkId(vectorCandidates, keywordCandidates);

        // Step 2: 计算加权 RRF 分数
        calculateWeightedRrf(merged, vectorCandidates, keywordCandidates,
                queryIntent.getVectorWeight(), queryIntent.getKeywordWeight());

        // Step 3: 按 rrfScore 降序排列
        List<HybridCandidate> ranked = merged.values().stream()
                .sorted(Comparator.comparingDouble(HybridCandidate::getRrfScore).reversed())
                .toList();

        // Step 4: 最终过滤
        List<HybridCandidate> selected = filterFinalResults(ranked, finalTopK);

        // Step 5: 打印完整日志
        logFinalRanking(ranked, selected, queryIntent);

        return selected;
    }

    /**
     * 按 chunkId 合并向量和关键词候选结果。
     * 同一个 chunk 可能被两个检索器都命中。
     */
    private Map<String, HybridCandidate> mergeCandidatesByChunkId(
            List<HybridCandidate> vectorCandidates,
            List<HybridCandidate> keywordCandidates) {

        Map<String, HybridCandidate> merged = new LinkedHashMap<>();

        // 先放入向量结果
        for (HybridCandidate vc : vectorCandidates) {
            merged.put(vc.getChunkId(), vc);
        }

        // 合并关键词结果
        for (HybridCandidate kc : keywordCandidates) {
            merged.merge(kc.getChunkId(), kc, (existing, incoming) -> {
                // 合并：保留向量的 snippet，补充关键词的 matchedTerms
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

    /**
     * 计算加权 RRF 分数。
     * score = vectorWeight / (k + vectorRank) + keywordWeight / (k + keywordRank)
     */
    private void calculateWeightedRrf(
            Map<String, HybridCandidate> merged,
            List<HybridCandidate> vectorCandidates,
            List<HybridCandidate> keywordCandidates,
            double vectorWeight,
            double keywordWeight) {

        // 向量部分
        for (int rank = 0; rank < vectorCandidates.size(); rank++) {
            HybridCandidate vc = vectorCandidates.get(rank);
            HybridCandidate candidate = merged.get(vc.getChunkId());
            if (candidate != null) {
                double vectorRrf = vectorWeight / (RRF_K + rank + 1);
                candidate.setRrfScore(candidate.getRrfScore() + vectorRrf);
            }
        }

        // 关键词部分
        for (int rank = 0; rank < keywordCandidates.size(); rank++) {
            HybridCandidate kc = keywordCandidates.get(rank);
            HybridCandidate candidate = merged.get(kc.getChunkId());
            if (candidate != null) {
                double keywordRrf = keywordWeight / (RRF_K + rank + 1);
                candidate.setRrfScore(candidate.getRrfScore() + keywordRrf);
            }
        }

        // finalScore 暂时 = rrfScore
        for (HybridCandidate candidate : merged.values()) {
            candidate.setFinalScore(candidate.getRrfScore());
        }
    }

    /**
     * 最终过滤规则：
     * 1. vectorScore >= 0.55 → 强相关，直接保留
     * 2. 0.35 <= vectorScore < 0.55 → 中等，关键词也命中则保留
     * 3. vectorScore < 0.35 → 弱相关，仅专有词命中时保留
     * 4. 只有关键词命中的高分结果也保留
     * 5. 全部被过滤时 fallback 保留最高分 1 条
     */
    private List<HybridCandidate> filterFinalResults(List<HybridCandidate> ranked, int finalTopK) {
        List<HybridCandidate> selected = new ArrayList<>();
        HybridCandidate bestFiltered = null;

        for (HybridCandidate c : ranked) {
            boolean pass = false;

            Double vs = c.getVectorScore();
            Integer kr = c.getKeywordRank();

            if (vs != null && vs >= STRONG_THRESHOLD) {
                // 强相关：直接保留
                pass = true;
            } else if (vs != null && vs >= MEDIUM_THRESHOLD) {
                // 中等相关：关键词也命中则保留
                pass = (kr != null);
            } else if (vs != null && vs < MEDIUM_THRESHOLD) {
                // 弱相关：仅专有词命中时保留
                pass = hasHighValueKeywordHit(c);
            } else if (kr != null && c.getKeywordScore() != null && c.getKeywordScore() > 20) {
                // 仅关键词命中且高分
                pass = true;
            }

            if (pass) {
                c.setSelected(true);
                selected.add(c);
            } else {
                c.setSelected(false);
                // 记录最高分的被过滤候选，用于 fallback
                if (bestFiltered == null || c.getFinalScore() > bestFiltered.getFinalScore()) {
                    bestFiltered = c;
                }
            }

            if (selected.size() >= finalTopK) {
                break;
            }
        }

        // fallback：全部被过滤时保留最高分 1 条
        if (selected.isEmpty() && bestFiltered != null) {
            bestFiltered.setSelected(true);
            selected.add(bestFiltered);
            log.info("[HybridRAG-Final] fallback: all filtered, keeping best candidate chunkId={}", bestFiltered.getChunkId());
        }

        return selected;
    }

    /**
     * 判断是否命中了高价值关键词（类名、方法名、命令、配置项等）。
     */
    private boolean hasHighValueKeywordHit(HybridCandidate c) {
        if (c.getMatchedTerms() == null || c.getMatchedTerms().isEmpty()) {
            return false;
        }
        for (String term : c.getMatchedTerms()) {
            // PascalCase 类名、snake_case、SQL 关键字、命令词
            if (term.matches("[A-Z][a-zA-Z0-9]+") ||
                term.matches("[a-z]+_[a-z]+") ||
                term.matches("(?i)(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE)") ||
                term.matches("(?i)(docker|kubectl|psql|mysql|grep|curl|ssh)")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打印完整融合排序日志。
     */
    private void logFinalRanking(
            List<HybridCandidate> ranked,
            List<HybridCandidate> selected,
            QueryIntent queryIntent) {

        for (int i = 0; i < ranked.size(); i++) {
            HybridCandidate c = ranked.get(i);
            boolean isSelected = selected.contains(c);
            log.info("[HybridRAG-Final] rank={}, chunkId={}, docId={}, title='{}', "
                            + "vectorRank={}, vectorScore={}, keywordRank={}, keywordScore={}, "
                            + "rrfScore={}, finalScore={}, selected={}",
                    i + 1,
                    c.getChunkId(),
                    c.getDocumentId(),
                    c.getTitle() != null && c.getTitle().length() > 30
                            ? c.getTitle().substring(0, 30) + "..." : c.getTitle(),
                    c.getVectorRank(),
                    c.getVectorScore(),
                    c.getKeywordRank(),
                    c.getKeywordScore(),
                    String.format("%.6f", c.getRrfScore()),
                    String.format("%.6f", c.getFinalScore()),
                    isSelected);
        }

        log.info("[HybridRAG-Final] total candidates={}, selected={}, "
                        + "queryType={}, vectorWeight={}, keywordWeight={}",
                ranked.size(), selected.size(),
                queryIntent.getQueryType(),
                queryIntent.getVectorWeight(),
                queryIntent.getKeywordWeight());
    }
}
