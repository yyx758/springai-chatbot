package com.example.chatbot.agent.review.conversation;

public interface CodeReviewIntentClassifier {
    CodeReviewIntent classify(String sessionId, String message);
}
