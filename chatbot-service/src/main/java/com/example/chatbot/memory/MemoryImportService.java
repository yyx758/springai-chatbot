package com.example.chatbot.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MemoryImportService {

    private final LongTermMemoryService memoryService;
    private final MemoryProperties properties;

    public List<LongTermMemoryRequest> preview(String content, String scopeType, String scopeKey) {
        String source = content == null || content.isBlank() ? defaultSeedSource() : content;
        List<LongTermMemoryRequest> drafts = new ArrayList<>();
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("<") || trimmed.startsWith("</")) {
                continue;
            }
            String normalized = trimmed.replaceFirst("^-\\s*", "").replaceFirst("^\\d+\\.\\s*", "");
            if (normalized.length() < 8) {
                continue;
            }
            LongTermMemoryRequest request = new LongTermMemoryRequest();
            request.setScopeType(scopeType == null || scopeType.isBlank() ? "PROJECT" : scopeType);
            request.setScopeKey(scopeKey == null || scopeKey.isBlank() ? properties.getDefaultProjectKey() : scopeKey);
            request.setMemoryType(resolveMemoryType(normalized));
            request.setName(nameFrom(normalized));
            request.setDescription(limit(normalized, 220));
            request.setContent(normalized);
            request.setLoadHint(loadHintFor(request.getMemoryType()));
            request.setSourceType("IMPORTED");
            request.setStatus("ACTIVE");
            drafts.add(request);
            if (drafts.size() >= 30) {
                break;
            }
        }
        return drafts;
    }

    public List<?> apply(Long userId, List<LongTermMemoryRequest> drafts) {
        return (drafts == null ? List.<LongTermMemoryRequest>of() : drafts).stream()
                .map(draft -> memoryService.create(userId, draft))
                .toList();
    }

    private String resolveMemoryType(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("用户") || lower.contains("喜欢") || lower.contains("偏好")) {
            return "feedback";
        }
        if (lower.contains("docs/") || lower.contains("controller") || lower.contains("接口") || lower.contains("命令")) {
            return "reference";
        }
        return "project";
    }

    private String nameFrom(String line) {
        String compact = line.replace("：", ":");
        int split = compact.indexOf(':');
        if (split > 0 && split < 80) {
            compact = compact.substring(0, split);
        }
        return limit(compact, 80);
    }

    private String loadHintFor(String memoryType) {
        return switch (memoryType) {
            case "feedback" -> "Load when deciding response style or task execution approach.";
            case "reference" -> "Load when locating project files, docs, commands, or entry points.";
            default -> "Load when tasks touch project architecture, deployment, security, or review rules.";
        };
    }

    private String defaultSeedSource() {
        return """
                当前系统是受控的工程化代码审查 Agent，不是泛用聊天助手。
                生产环境用户入口只走 Gateway :9000。
                chatbot-service:8080 和 file-service:8081 不应暴露给宿主机。
                GitReviewService 只能做只读 Git 操作。
                真正修改 workspace 文件必须经过 Pending Action 二次确认。
                不自动 commit / push / 操作真实 Git 仓库。
                用户喜欢直接推进，实现、验证、总结，不希望只停留在建议层面。
                代码审查阶段文档位于 docs/agent/。
                代码审查核心 Controller 是 CodeReviewAgentController。
                """;
    }

    private String limit(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
