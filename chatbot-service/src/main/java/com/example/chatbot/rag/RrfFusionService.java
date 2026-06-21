package com.example.chatbot.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class RrfFusionService {

    private final HybridRagProperties properties;

    public List<HybridSearchResult> fuse(List<SearchResult> vectorResults,
                                         List<SearchResult> keywordResults,
                                         int finalTopK) {
        int k = Math.max(1, properties.getRrfK());
        int limit = finalTopK > 0 ? finalTopK : properties.getFinalTopK();

        Map<String, MutableHybridResult> merged = new LinkedHashMap<>();
        addResults(merged, vectorResults, true, k);
        addResults(merged, keywordResults, false, k);

        List<HybridSearchResult> ranked = merged.values().stream()
                .map(MutableHybridResult::toResult)
                .sorted(Comparator.comparing(HybridSearchResult::getRrfScore, Comparator.nullsLast(Double::compareTo)).reversed())
                .limit(limit)
                .toList();

        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setRank(i + 1);
            log.info("[HybridRAG-RRF] rank={}, chunkId={}, docId={}, title='{}', rrfScore={}, vectorRank={}, keywordRank={}, vectorScore={}, keywordScore={}, sources={}",
                    i + 1,
                    ranked.get(i).getChunkId(),
                    ranked.get(i).getDocId(),
                    HybridRanker.preview(ranked.get(i).getTitle(), 30),
                    String.format("%.6f", ranked.get(i).getRrfScore()),
                    ranked.get(i).getVectorRank(),
                    ranked.get(i).getKeywordRank(),
                    ranked.get(i).getVectorScore(),
                    ranked.get(i).getKeywordScore(),
                    ranked.get(i).getSources());
        }
        return ranked;
    }

    private void addResults(Map<String, MutableHybridResult> merged,
                            List<SearchResult> results,
                            boolean vector,
                            int k) {
        if (results == null) {
            return;
        }
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            if (result == null || result.getChunkId() == null || result.getChunkId().isBlank()) {
                continue;
            }
            int rank = result.getRank() != null && result.getRank() > 0 ? result.getRank() : i + 1;
            MutableHybridResult mergedResult = merged.computeIfAbsent(result.getChunkId(), chunkId -> new MutableHybridResult(result));
            mergedResult.rrfScore += 1.0 / (k + rank);
            if (vector) {
                mergedResult.vectorRank = rank;
                mergedResult.vectorScore = result.getScore();
                mergedResult.sources.add("vector");
            } else {
                mergedResult.keywordRank = rank;
                mergedResult.keywordScore = result.getScore();
                mergedResult.sources.add(result.getSource() == null || result.getSource().isBlank()
                        ? "elasticsearch"
                        : result.getSource());
            }
            mergedResult.fillMissing(result);
        }
    }

    private static class MutableHybridResult {
        private String chunkId;
        private Long docId;
        private String title;
        private String content;
        private double rrfScore;
        private Integer vectorRank;
        private Integer keywordRank;
        private Double vectorScore;
        private Double keywordScore;
        private final Set<String> sources = new LinkedHashSet<>();

        private MutableHybridResult(SearchResult result) {
            fillMissing(result);
        }

        private void fillMissing(SearchResult result) {
            if (chunkId == null) {
                chunkId = result.getChunkId();
            }
            if (docId == null) {
                docId = result.getDocId();
            }
            if (title == null || title.isBlank()) {
                title = result.getTitle();
            }
            if (content == null || content.isBlank()) {
                content = result.getContent();
            }
        }

        private HybridSearchResult toResult() {
            return HybridSearchResult.builder()
                    .chunkId(chunkId)
                    .docId(docId)
                    .title(title)
                    .content(content)
                    .rrfScore(rrfScore)
                    .vectorRank(vectorRank)
                    .keywordRank(keywordRank)
                    .vectorScore(vectorScore)
                    .keywordScore(keywordScore)
                    .sources(new ArrayList<>(sources))
                    .build();
        }
    }
}
