package com.example.chatbot.agent.skill;

import com.example.chatbot.agent.skill.skills.DocumentSummarySkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillRouterTest {

    @Test
    void route_matchesDocumentSummary() {
        DocumentSummarySkill skill = new DocumentSummarySkill();
        SkillRegistry registry = new SkillRegistry(List.of(skill));
        SkillRouter router = new SkillRouter(registry);

        SkillMatchResult result = router.route("帮我总结一下这篇文章");
        assertNotNull(result.getSkill());
        assertEquals("document-summary", result.getSkill().getId());
    }

    @Test
    void route_noMatch() {
        DocumentSummarySkill skill = new DocumentSummarySkill();
        SkillRegistry registry = new SkillRegistry(List.of(skill));
        SkillRouter router = new SkillRouter(registry);

        SkillMatchResult result = router.route("今天天气怎么样");
        assertNull(result.getSkill());
    }

    @Test
    void route_emptyInput() {
        DocumentSummarySkill skill = new DocumentSummarySkill();
        SkillRegistry registry = new SkillRegistry(List.of(skill));
        SkillRouter router = new SkillRouter(registry);

        SkillMatchResult result = router.route("");
        assertNull(result.getSkill());
    }

    @Test
    void route_nullInput() {
        DocumentSummarySkill skill = new DocumentSummarySkill();
        SkillRegistry registry = new SkillRegistry(List.of(skill));
        SkillRouter router = new SkillRouter(registry);

        SkillMatchResult result = router.route(null);
        assertNull(result.getSkill());
    }
}
