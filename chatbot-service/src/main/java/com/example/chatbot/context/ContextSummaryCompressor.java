package com.example.chatbot.context;

import java.util.List;

public interface ContextSummaryCompressor {

    String summarize(List<ContextSegment> segments, ContextCompressionMode mode);
}
