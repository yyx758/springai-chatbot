package com.example.chatbot.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ContextTokenEstimator {

    private final Encoding encoding;

    public ContextTokenEstimator() {
        this.encoding = initializeEncoding();
    }

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        if (encoding != null) {
            try {
                return encoding.countTokens(text);
            } catch (Exception e) {
                log.warn("Tokenizer failed, falling back to heuristic estimate: {}", e.getMessage());
            }
        }
        return heuristicEstimate(text);
    }

    private Encoding initializeEncoding() {
        try {
            return Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        } catch (Exception e) {
            log.warn("Tokenizer unavailable, falling back to heuristic token estimator: {}", e.getMessage());
            return null;
        }
    }

    private int heuristicEstimate(String text) {
        int tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 127 && Character.isLetterOrDigit(c)) {
                asciiRun++;
                continue;
            }
            if (asciiRun > 0) {
                tokens += Math.max(1, (asciiRun + 3) / 4);
                asciiRun = 0;
            }
            if (!Character.isWhitespace(c)) {
                tokens++;
            }
        }
        if (asciiRun > 0) {
            tokens += Math.max(1, (asciiRun + 3) / 4);
        }
        return tokens;
    }
}
