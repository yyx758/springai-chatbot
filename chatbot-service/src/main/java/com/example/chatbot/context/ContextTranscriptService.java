package com.example.chatbot.context;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContextTranscriptService {

    private final ConcurrentMap<String, TranscriptSnapshot> snapshots = new ConcurrentHashMap<>();

    public String saveSnapshot(List<ContextSegment> segments, String reason) {
        String transcriptId = UUID.randomUUID().toString();
        List<ContextSegment> safeSegments = segments == null ? List.of() : List.copyOf(segments);
        snapshots.put(transcriptId, new TranscriptSnapshot(transcriptId, reason, LocalDateTime.now(), safeSegments));
        log.info("Saved context transcript snapshot. transcriptId={}, reason={}, segmentCount={}",
                transcriptId, reason, safeSegments.size());
        return transcriptId;
    }

    public TranscriptSnapshot findSnapshot(String transcriptId) {
        return snapshots.get(transcriptId);
    }

    public record TranscriptSnapshot(
            String transcriptId,
            String reason,
            LocalDateTime createdAt,
            List<ContextSegment> segments
    ) {
    }
}
