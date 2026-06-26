package com.example.chatbot.agent.review.conversation;

public enum CodeReviewIntentType {
    REVIEW_FILE,
    REVIEW_WORKSPACE,
    REVIEW_GIT_DIFF,
    LIST_REVIEW_ISSUES,
    EXPLAIN_REVIEW_ISSUE,
    GENERATE_PATCH_PREVIEW,
    CREATE_PATCH_APPLY_REQUEST,
    LIST_PENDING_ACTIONS,
    GENERAL_CHAT,
    UNKNOWN
}
