package com.example.chatbot.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

@Service
@Slf4j
public class ContextCompressionService {

    private final ContextTokenEstimator tokenEstimator;
    private final ContextCompressionProperties compressionProperties;
    private final ContextSummaryCompressor summaryCompressor;
    private final ContextTranscriptService transcriptService;

    @Autowired
    public ContextCompressionService(
            ContextTokenEstimator tokenEstimator,
            ContextCompressionProperties compressionProperties,
            ContextSummaryCompressor summaryCompressor,
            ContextTranscriptService transcriptService
    ) {
        this.tokenEstimator = tokenEstimator;
        this.compressionProperties = compressionProperties;
        this.summaryCompressor = summaryCompressor;
        this.transcriptService = transcriptService;
    }

    public ContextCompressionService(
            ContextTokenEstimator tokenEstimator,
            ContextCompressionProperties compressionProperties
    ) {
        this(tokenEstimator, compressionProperties,
                new DeterministicContextSummaryCompressor(), new ContextTranscriptService());
    }

    public ContextCompactionResult compact(List<ContextSegment> inputSegments, int maxContextChars) {
        List<ContextSegment> segments = new ArrayList<>(inputSegments == null ? List.of() : inputSegments);
        segments.forEach(this::fillEstimatedTokens);

        int originalChars = totalChars(segments);
        int originalTokens = totalEstimatedTokens(segments);
        if (!compressionProperties.isEnabled()) {
            return result(segments, originalChars, originalTokens, 0, 0, 0,
                    new ContextCompressionStats(), false, false, false);
        }

        ContextCompressionStats stats = new ContextCompressionStats();
        applyZeroApiCompactionPipeline(segments, stats);

        int tokenBudget = compressionProperties.getMaxInputTokens() > 0
                ? compressionProperties.getMaxInputTokens()
                : Integer.MAX_VALUE;
        int charBudget = maxContextChars > 0 ? maxContextChars : 12000;
        int removedSegments = 0;
        int removedChars = 0;
        int removedTokens = 0;
        int currentChars = totalChars(segments);
        int currentTokens = totalEstimatedTokens(segments);
        boolean tokenBudgetExceeded = false;
        boolean charBudgetExceeded = false;
        boolean truncated = stats.snippedSegments > 0
                || stats.microCompactedToolResults > 0
                || stats.persistedLargeResults > 0;

        if (shouldAutoCompact(currentTokens, tokenBudget)) {
            autoCompact(segments, stats);
            currentChars = totalChars(segments);
            currentTokens = totalEstimatedTokens(segments);
            truncated = true;
        }

        while (currentTokens > tokenBudget && segments.size() > 2) {
            RemovedSegments removed = removeBestCandidate(segments);
            if (removed.count() == 0) {
                break;
            }
            removedSegments += removed.count();
            removedChars += removed.chars();
            removedTokens += removed.tokens();
            currentChars -= removed.chars();
            currentTokens -= removed.tokens();
            tokenBudgetExceeded = true;
            truncated = true;
        }

        while (currentChars > charBudget && segments.size() > 2) {
            RemovedSegments removed = removeBestCandidate(segments);
            if (removed.count() == 0) {
                break;
            }
            removedSegments += removed.count();
            removedChars += removed.chars();
            removedTokens += removed.tokens();
            currentChars -= removed.chars();
            currentTokens -= removed.tokens();
            charBudgetExceeded = true;
            truncated = true;
        }

        if (truncated) {
            log.info("Context compacted. originalChars={}, finalChars={}, originalEstimatedTokens={}, finalEstimatedTokens={}, "
                            + "removedSegments={}, removedChars={}, removedEstimatedTokens={}, snippedSegments={}, "
                            + "microCompactedToolResults={}, persistedLargeResults={}, autoCompacted={}, tokenBudgetExceeded={}, charBudgetExceeded={}",
                    originalChars, currentChars, originalTokens, currentTokens,
                    removedSegments, removedChars, removedTokens, stats.snippedSegments,
                    stats.microCompactedToolResults, stats.persistedLargeResults, stats.autoCompacted,
                    tokenBudgetExceeded, charBudgetExceeded);
        }
        return result(segments, originalChars, originalTokens, removedSegments, removedChars, removedTokens,
                stats, tokenBudgetExceeded, charBudgetExceeded, truncated);
    }

    public ContextCompactionResult reactiveCompact(List<ContextSegment> inputSegments, int maxContextChars) {
        List<ContextSegment> segments = new ArrayList<>(inputSegments == null ? List.of() : inputSegments);
        segments.forEach(this::fillEstimatedTokens);
        int originalChars = totalChars(segments);
        int originalTokens = totalEstimatedTokens(segments);
        ContextCompressionStats stats = new ContextCompressionStats();

        if (!compressionProperties.isReactiveCompactEnabled()) {
            return compact(segments, maxContextChars);
        }

        transcriptService.saveSnapshot(segments, "reactive-compact");
        List<ContextSegment> compacted = reactiveCompactSegments(segments);
        compacted.forEach(this::fillEstimatedTokens);
        stats.autoCompacted = true;

        ContextCompactionResult result = compact(compacted, maxContextChars);
        result.setOriginalChars(originalChars);
        result.setOriginalEstimatedTokens(originalTokens);
        result.setAutoCompacted(true);
        return result;
    }

    private void fillEstimatedTokens(ContextSegment segment) {
        fillDefaults(segment);
        if (segment.getEstimatedTokens() <= 0) {
            segment.setEstimatedTokens(tokenEstimator.estimate(segment.getContent()));
        }
    }

    private void fillDefaults(ContextSegment segment) {
        ContextSegmentType type = segment.getType();
        if (type == null) {
            return;
        }
        if (type == ContextSegmentType.SYSTEM_FIXED || type == ContextSegmentType.CURRENT_USER_INPUT) {
            segment.setRequired(true);
        }
        if (segment.getPriority() <= 0) {
            segment.setPriority(defaultPriority(type));
        }
    }

    private int defaultPriority(ContextSegmentType type) {
        return switch (type) {
            case SYSTEM_FIXED, CURRENT_USER_INPUT -> 0;
            case TOOL_SCHEMA, USER_MEMORY, MEMORY_INDEX, MEMORY_DETAIL -> 1;
            case SESSION_SUMMARY, COMPACTED_SUMMARY -> 2;
            case RAG_CONTEXT -> 3;
            case RECENT_HISTORY -> 4;
            case WEB_CONTEXT -> 5;
            case WORKSPACE_FILE, CODE_REVIEW_DIFF -> 6;
            case TOOL_RESULT -> 7;
        };
    }

    private ContextCompactionResult result(
            List<ContextSegment> segments,
            int originalChars,
            int originalTokens,
            int removedSegments,
            int removedChars,
            int removedTokens,
            ContextCompressionStats stats,
            boolean tokenBudgetExceeded,
            boolean charBudgetExceeded,
            boolean truncated
    ) {
        return ContextCompactionResult.builder()
                .segments(segments)
                .originalChars(originalChars)
                .finalChars(totalChars(segments))
                .originalEstimatedTokens(originalTokens)
                .finalEstimatedTokens(totalEstimatedTokens(segments))
                .removedSegments(removedSegments)
                .removedChars(removedChars)
                .removedEstimatedTokens(removedTokens)
                .snippedSegments(stats.snippedSegments)
                .microCompactedToolResults(stats.microCompactedToolResults)
                .persistedLargeResults(stats.persistedLargeResults)
                .autoCompacted(stats.autoCompacted)
                .tokenBudgetExceeded(tokenBudgetExceeded)
                .charBudgetExceeded(charBudgetExceeded)
                .truncated(truncated)
                .build();
    }

    private int totalChars(List<ContextSegment> segments) {
        return segments.stream().mapToInt(this::chars).sum();
    }

    private int totalEstimatedTokens(List<ContextSegment> segments) {
        return segments.stream().mapToInt(ContextSegment::getEstimatedTokens).sum();
    }

    private int chars(ContextSegment segment) {
        return segment.getContent() == null ? 0 : segment.getContent().length();
    }

    private RemovedSegments removeBestCandidate(List<ContextSegment> segments) {
        int index = bestCandidateIndex(segments);
        if (index < 0) {
            return RemovedSegments.empty();
        }
        if (segments.get(index).getType() == ContextSegmentType.RECENT_HISTORY) {
            return removeRecentHistoryGroup(segments, index);
        }
        ContextSegment removed = segments.remove(index);
        return new RemovedSegments(1, chars(removed), removed.getEstimatedTokens());
    }

    private int bestCandidateIndex(List<ContextSegment> segments) {
        int bestIndex = -1;
        int bestPriority = Integer.MIN_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            ContextSegment segment = segments.get(i);
            if (segment.isRequired()) {
                continue;
            }
            if (segment.getPriority() > bestPriority) {
                bestPriority = segment.getPriority();
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private RemovedSegments removeRecentHistoryGroup(List<ContextSegment> segments, int index) {
        int start = index;
        while (start > 0 && segments.get(start - 1).getType() == ContextSegmentType.RECENT_HISTORY) {
            start--;
        }
        int end = index + 1;
        while (end < segments.size() && segments.get(end).getType() == ContextSegmentType.RECENT_HISTORY) {
            end++;
        }

        int removeEnd = Math.min(end, start + 2);
        int removedCount = 0;
        int removedChars = 0;
        int removedTokens = 0;
        for (int i = start; i < removeEnd; i++) {
            ContextSegment removed = segments.remove(start);
            removedCount++;
            removedChars += chars(removed);
            removedTokens += removed.getEstimatedTokens();
        }
        return new RemovedSegments(removedCount, removedChars, removedTokens);
    }

    private void applyZeroApiCompactionPipeline(List<ContextSegment> segments, ContextCompressionStats stats) {
        persistLargeResults(segments, stats);
        snipHistory(segments, stats);
        microCompactToolResults(segments, stats);
    }

    private void persistLargeResults(List<ContextSegment> segments, ContextCompressionStats stats) {
        int threshold = compressionProperties.getLargeResultMaxChars();
        if (threshold <= 0) {
            return;
        }
        for (ContextSegment segment : segments) {
            if (!isLargeResultCandidate(segment) || chars(segment) <= threshold) {
                continue;
            }
            String original = segment.getContent();
            String artifactId = Integer.toHexString(original.hashCode());
            segment.setContent(largeResultPlaceholder(segment, artifactId, original));
            segment.setSourceRef(appendRef(segment.getSourceRef(), "artifact:" + artifactId));
            refreshTokens(segment);
            stats.persistedLargeResults++;
        }
    }

    private boolean isLargeResultCandidate(ContextSegment segment) {
        if (segment == null || segment.isRequired()) {
            return false;
        }
        ContextSegmentType type = segment.getType();
        return type == ContextSegmentType.TOOL_RESULT
                || type == ContextSegmentType.WORKSPACE_FILE
                || type == ContextSegmentType.CODE_REVIEW_DIFF
                || type == ContextSegmentType.WEB_CONTEXT;
    }

    private String largeResultPlaceholder(ContextSegment segment, String artifactId, String original) {
        return "[Persisted large context result]\n"
                + "type=" + safeType(segment) + "\n"
                + "tool=" + safe(segment.getToolName()) + "\n"
                + "source=" + safe(segment.getSourceRef()) + "\n"
                + "artifactId=" + artifactId + "\n"
                + "originalChars=" + original.length() + "\n"
                + "preview:\n" + preview(original, compressionProperties.getLargeResultPreviewChars());
    }

    private void snipHistory(List<ContextSegment> segments, ContextCompressionStats stats) {
        int maxMessages = compressionProperties.getSnipMaxMessages();
        if (maxMessages <= 0 || segments.size() <= maxMessages) {
            return;
        }

        int head = Math.max(0, compressionProperties.getSnipHeadMessages());
        int tail = Math.max(0, compressionProperties.getSnipTailMessages());
        int start = Math.min(head, segments.size());
        int endExclusive = Math.max(start, segments.size() - tail);
        List<ContextSegment> removed = new ArrayList<>();
        for (int i = start; i < endExclusive; ) {
            ContextSegment segment = segments.get(i);
            if (segment.isRequired()) {
                i++;
                continue;
            }
            removed.add(segments.remove(i));
            endExclusive--;
        }
        if (removed.isEmpty()) {
            return;
        }
        ContextSegment summary = compactedPlaceholder(
                "[Middle context snipped]\n"
                        + "snippedSegments=" + removed.size() + "\n"
                        + groupedTypeSummary(removed),
                "snip-history");
        refreshTokens(summary);
        segments.add(Math.min(start, segments.size()), summary);
        stats.snippedSegments += removed.size();
    }

    private void microCompactToolResults(List<ContextSegment> segments, ContextCompressionStats stats) {
        int keepRecent = Math.max(0, compressionProperties.getKeepRecentToolResults());
        List<Integer> toolIndexes = IntStream.range(0, segments.size())
                .filter(i -> segments.get(i).getType() == ContextSegmentType.TOOL_RESULT)
                .boxed()
                .toList();
        int compactCount = Math.max(0, toolIndexes.size() - keepRecent);
        if (compactCount <= 0) {
            return;
        }
        for (int i = 0; i < compactCount; i++) {
            ContextSegment segment = segments.get(toolIndexes.get(i));
            if (segment.isRequired()) {
                continue;
            }
            segment.setContent("[Earlier tool result compacted]\n"
                    + "tool=" + safe(segment.getToolName()) + "\n"
                    + "source=" + safe(segment.getSourceRef()) + "\n"
                    + "originalTokens=" + Math.max(0, segment.getEstimatedTokens()) + "\n"
                    + "Re-run or re-read the source when exact output is needed.");
            refreshTokens(segment);
            stats.microCompactedToolResults++;
        }
    }

    private boolean shouldAutoCompact(int currentTokens, int tokenBudget) {
        if (!compressionProperties.isAutoCompactEnabled() || tokenBudget == Integer.MAX_VALUE || tokenBudget <= 0) {
            return false;
        }
        int threshold = (int) Math.max(1, Math.floor(tokenBudget * compressionProperties.getAutoCompactThresholdRatio()));
        return currentTokens > threshold;
    }

    private void autoCompact(List<ContextSegment> segments, ContextCompressionStats stats) {
        List<ContextSegment> candidates = autoCompactCandidates(segments);
        if (candidates.isEmpty()) {
            return;
        }
        transcriptService.saveSnapshot(segments, "auto-compact");
        String summary = summaryCompressor.summarize(candidates, ContextCompressionMode.AUTO);
        int insertAt = firstIndexOf(segments, candidates);
        segments.removeAll(candidates);
        ContextSegment compacted = compactedPlaceholder(summary, "auto-compact");
        refreshTokens(compacted);
        segments.add(Math.min(insertAt, segments.size()), compacted);
        stats.autoCompacted = true;
    }

    private List<ContextSegment> autoCompactCandidates(List<ContextSegment> segments) {
        int keepRecent = Math.max(2, Math.min(3, compressionProperties.getSnipTailMessages()));
        int tailStart = Math.max(0, segments.size() - keepRecent);
        return IntStream.range(0, segments.size())
                .filter(i -> i < tailStart)
                .mapToObj(segments::get)
                .filter(segment -> !segment.isRequired())
                .filter(segment -> segment.getType() != ContextSegmentType.COMPACTED_SUMMARY)
                .sorted(Comparator.comparingInt(ContextSegment::getPriority).reversed())
                .toList();
    }

    private List<ContextSegment> reactiveCompactSegments(List<ContextSegment> segments) {
        int keepTail = 5;
        int tailStart = Math.max(0, segments.size() - keepTail);
        List<ContextSegment> candidates = IntStream.range(0, tailStart)
                .mapToObj(segments::get)
                .filter(segment -> !segment.isRequired())
                .toList();
        if (candidates.isEmpty()) {
            return segments;
        }
        List<ContextSegment> compacted = new ArrayList<>(segments);
        int insertAt = firstIndexOf(compacted, candidates);
        compacted.removeAll(candidates);
        ContextSegment summary = compactedPlaceholder(
                summaryCompressor.summarize(candidates, ContextCompressionMode.REACTIVE),
                "reactive-compact");
        refreshTokens(summary);
        compacted.add(Math.min(insertAt, compacted.size()), summary);
        return compacted;
    }

    private int firstIndexOf(List<ContextSegment> segments, List<ContextSegment> candidates) {
        for (int i = 0; i < segments.size(); i++) {
            if (candidates.contains(segments.get(i))) {
                return i;
            }
        }
        return Math.max(0, segments.size() - 1);
    }

    private ContextSegment compactedPlaceholder(String content, String sourceRef) {
        return ContextSegment.builder()
                .type(ContextSegmentType.COMPACTED_SUMMARY)
                .role("system")
                .content(content)
                .priority(defaultPriority(ContextSegmentType.COMPACTED_SUMMARY))
                .sourceRef(sourceRef)
                .build();
    }

    private String groupedTypeSummary(List<ContextSegment> segments) {
        return segments.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        segment -> safeType(segment),
                        java.util.stream.Collectors.counting()))
                .entrySet()
                .stream()
                .map(entry -> "- " + entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void refreshTokens(ContextSegment segment) {
        segment.setEstimatedTokens(tokenEstimator.estimate(segment.getContent()));
    }

    private String preview(String content, int maxChars) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int limit = maxChars > 0 ? maxChars : 2000;
        if (content.length() <= limit) {
            return content;
        }
        return content.substring(0, limit) + "\n...[truncated]";
    }

    private String appendRef(String sourceRef, String addition) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return addition;
        }
        return sourceRef + ";" + addition;
    }

    private String safeType(ContextSegment segment) {
        return segment.getType() == null ? "UNKNOWN" : segment.getType().name();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record RemovedSegments(int count, int chars, int tokens) {
        static RemovedSegments empty() {
            return new RemovedSegments(0, 0, 0);
        }
    }
}
