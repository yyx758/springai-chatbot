package com.example.chatbot.agent.review.conversation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedCodeReviewIntentClassifier implements CodeReviewIntentClassifier {

    private static final Pattern PATH_PATTERN = Pattern.compile("([\\w./\\-]+\\.(?:java|kt|xml|yml|yaml|properties|js|ts|tsx|jsx|vue|html|css|sql))");

    @Override
    public CodeReviewIntent classify(String sessionId, String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isBlank()) {
            return unknown(sessionId, raw);
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> focusAreas = extractFocusAreas(lower);

        if (containsAny(lower, "git diff", "diff", "这次改动", "本次改动", "变更", "changed files")
                && containsAny(lower, "review", "审查", "审核", "检查", "看一下", "看看")) {
            return intent(sessionId, raw, CodeReviewIntentType.REVIEW_GIT_DIFF, null, List.of(), focusAreas, 0.92);
        }

        if (containsAny(lower, "workspace", "工作区", "项目", "全部", "整个")
                && containsAny(lower, "review", "审查", "审核", "检查")) {
            return intent(sessionId, raw, CodeReviewIntentType.REVIEW_WORKSPACE, null, List.of(), focusAreas, 0.82);
        }

        List<String> paths = extractPaths(raw);
        if (!paths.isEmpty() && containsAny(lower, "review", "审查", "审核", "检查", "看一下", "看看")) {
            return intent(sessionId, raw, CodeReviewIntentType.REVIEW_FILE, paths.get(0), paths, focusAreas, 0.82);
        }

        if (containsAny(lower, "解释", "为什么", "详细说", "说明")
                && containsAny(lower, "问题", "issue", "#")) {
            return intent(sessionId, raw, CodeReviewIntentType.EXPLAIN_REVIEW_ISSUE, null, List.of(), focusAreas, 0.76);
        }

        if (containsAny(lower, "修复", "patch", "修复草案", "生成修复", "fix")) {
            return intent(sessionId, raw, CodeReviewIntentType.GENERATE_PATCH_PREVIEW, null, paths, focusAreas, 0.72);
        }

        if (containsAny(lower, "创建待确认", "apply request", "待确认操作", "创建 pending", "pending action")) {
            return intent(sessionId, raw, CodeReviewIntentType.CREATE_PATCH_APPLY_REQUEST, null, List.of(), focusAreas, 0.72);
        }

        return CodeReviewIntent.builder()
                .type(CodeReviewIntentType.GENERAL_CHAT)
                .sessionId(sessionId)
                .userInstruction(raw)
                .confidence(0.0)
                .build();
    }

    private CodeReviewIntent unknown(String sessionId, String raw) {
        return CodeReviewIntent.builder()
                .type(CodeReviewIntentType.UNKNOWN)
                .sessionId(sessionId)
                .userInstruction(raw)
                .confidence(0.0)
                .build();
    }

    private CodeReviewIntent intent(String sessionId,
                                    String raw,
                                    CodeReviewIntentType type,
                                    String targetPath,
                                    List<String> targetPaths,
                                    List<String> focusAreas,
                                    double confidence) {
        return CodeReviewIntent.builder()
                .type(type)
                .sessionId(sessionId)
                .targetPath(targetPath)
                .targetPaths(new ArrayList<>(targetPaths))
                .focusAreas(new ArrayList<>(focusAreas))
                .userInstruction(raw)
                .confidence(confidence)
                .build();
    }

    private List<String> extractPaths(String message) {
        Matcher matcher = PATH_PATTERN.matcher(message);
        List<String> paths = new ArrayList<>();
        while (matcher.find()) {
            paths.add(matcher.group(1).replace('\\', '/'));
        }
        return paths;
    }

    private List<String> extractFocusAreas(String lower) {
        List<String> focus = new ArrayList<>();
        if (containsAny(lower, "权限", "鉴权", "认证", "auth", "authorization", "permission")) {
            focus.add("权限/鉴权");
        }
        if (containsAny(lower, "空指针", "npe", "null pointer", "null")) {
            focus.add("空指针");
        }
        if (containsAny(lower, "安全", "漏洞", "注入", "security", "injection")) {
            focus.add("安全");
        }
        if (containsAny(lower, "并发", "线程", "concurrency", "thread")) {
            focus.add("并发");
        }
        return focus;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
