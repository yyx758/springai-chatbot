package com.example.chatbot.agent.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class SkillRouter {

    private final SkillRegistry skillRegistry;

    public SkillMatchResult route(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return SkillMatchResult.noMatch();
        }

        List<SkillDefinition> candidates = skillRegistry.findAllMatching(userMessage);
        if (candidates.isEmpty()) {
            log.debug("No skill matched for input: {}", truncate(userMessage, 60));
            return SkillMatchResult.noMatch();
        }

        SkillDefinition best = candidates.get(0);
        log.debug("Skill matched: {} (priority={}) for input: {}", best.getId(), best.getPriority(), truncate(userMessage, 60));
        return SkillMatchResult.matched(best, 1.0, "keyword");
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
