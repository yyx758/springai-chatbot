package com.example.chatbot.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMatchResult {

    private SkillDefinition skill;
    private double confidence;
    private String matchReason;

    public static SkillMatchResult noMatch() {
        return SkillMatchResult.builder()
                .skill(null)
                .confidence(0.0)
                .matchReason("no skill matched")
                .build();
    }

    public static SkillMatchResult matched(SkillDefinition skill, double confidence, String reason) {
        return SkillMatchResult.builder()
                .skill(skill)
                .confidence(confidence)
                .matchReason(reason)
                .build();
    }
}
