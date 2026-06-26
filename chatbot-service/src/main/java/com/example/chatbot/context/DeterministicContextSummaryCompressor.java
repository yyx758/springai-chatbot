package com.example.chatbot.context;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class DeterministicContextSummaryCompressor implements ContextSummaryCompressor {

    private static final int MAX_ITEMS = 16;

    @Override
    public String summarize(List<ContextSegment> segments, ContextCompressionMode mode) {
        List<ContextSegment> safeSegments = segments == null ? List.of() : segments;
        String heading = mode == ContextCompressionMode.REACTIVE
                ? "[Reactive compacted context]"
                : "[Auto compacted context]";
        String body = safeSegments.stream()
                .filter(Objects::nonNull)
                .limit(MAX_ITEMS)
                .map(this::summarizeSegment)
                .collect(Collectors.joining("\n"));
        int remaining = Math.max(0, safeSegments.size() - MAX_ITEMS);
        if (remaining > 0) {
            body = body + "\n- omittedSegments=" + remaining;
        }
        return heading + "\n"
                + "Summary only. Re-read source refs for exact content.\n"
                + body;
    }

    private String summarizeSegment(ContextSegment segment) {
        StringBuilder builder = new StringBuilder("- type=")
                .append(segment.getType() == null ? "UNKNOWN" : segment.getType().name());
        if (hasText(segment.getId())) {
            builder.append(", id=").append(segment.getId());
        }
        if (hasText(segment.getSourceRef())) {
            builder.append(", source=").append(segment.getSourceRef());
        }
        if (hasText(segment.getToolName())) {
            builder.append(", tool=").append(segment.getToolName());
        }
        builder.append(", tokens=").append(Math.max(0, segment.getEstimatedTokens()));
        return builder.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
