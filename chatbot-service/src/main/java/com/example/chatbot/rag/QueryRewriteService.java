package com.example.chatbot.rag;

import org.springframework.stereotype.Component;

/**
 * Lightweight retrieval query rewrite.
 * <p>
 * This is intentionally rule-based to avoid adding LLM latency to the chat path.
 */
@Component
public class QueryRewriteService {

    public String rewriteForRetrieval(String query) {
        if (query == null) {
            return "";
        }
        String rewritten = query.trim()
                .replaceAll("(?i)^(请问|帮我|帮忙|麻烦|我想知道|能不能|可以告诉我)", "")
                .replaceAll("(?i)(查一下|介绍一下|说一下|讲一下|讲讲|具体说说|详细讲讲)", " ")
                .replaceAll("(?i)(怎么办|是什么|怎么做|怎么|如何|为什么|有没有|相关内容)", " ")
                .replaceAll("[?？!！。，,、;；:：\\s]+", " ")
                .replaceAll("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])", "")
                .trim();
        return rewritten.isBlank() ? query.trim() : rewritten;
    }
}
