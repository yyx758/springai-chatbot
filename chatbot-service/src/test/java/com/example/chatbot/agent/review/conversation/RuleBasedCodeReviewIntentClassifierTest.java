package com.example.chatbot.agent.review.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedCodeReviewIntentClassifierTest {

    private final RuleBasedCodeReviewIntentClassifier classifier = new RuleBasedCodeReviewIntentClassifier();

    @Test
    void classifyGitDiffReviewIntentWithFocusAreas() {
        CodeReviewIntent intent = classifier.classify("7_session", "帮我审查这次 Git diff，重点看权限和空指针问题");

        assertEquals(CodeReviewIntentType.REVIEW_GIT_DIFF, intent.getType());
        assertEquals("7_session", intent.getSessionId());
        assertTrue(intent.getConfidence() > 0.9);
        assertTrue(intent.getFocusAreas().contains("权限/鉴权"));
        assertTrue(intent.getFocusAreas().contains("空指针"));
    }

    @Test
    void classifyGeneralChatWhenMessageIsNotReviewRelated() {
        CodeReviewIntent intent = classifier.classify("7_session", "解释一下 Spring Bean 生命周期");

        assertEquals(CodeReviewIntentType.GENERAL_CHAT, intent.getType());
    }

    @Test
    void classifyFileReviewAndExtractPath() {
        CodeReviewIntent intent = classifier.classify("7_session", "请审查 src/main/java/App.java");

        assertEquals(CodeReviewIntentType.REVIEW_FILE, intent.getType());
        assertEquals("src/main/java/App.java", intent.getTargetPath());
    }
}
