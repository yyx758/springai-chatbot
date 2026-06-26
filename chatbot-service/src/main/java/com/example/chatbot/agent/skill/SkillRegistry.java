package com.example.chatbot.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class SkillRegistry {

    private final Map<String, SkillDefinition> skills;

    public SkillRegistry(List<SkillDefinition> skillBeans) {
        this.skills = skillBeans.stream()
                .collect(Collectors.toMap(SkillDefinition::getId, Function.identity()));
        log.info("Registered {} skills: {}", skills.size(), skills.keySet());
    }

    public Optional<SkillDefinition> getById(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public List<SkillDefinition> getAll() {
        return skills.values().stream()
                .sorted(Comparator.comparingInt(SkillDefinition::getPriority).reversed())
                .collect(Collectors.toList());
    }

    public List<SkillDefinition> findAllMatching(String userMessage) {
        return skills.values().stream()
                .filter(s -> s.matches(userMessage))
                .sorted(Comparator.comparingInt(SkillDefinition::getPriority).reversed())
                .collect(Collectors.toList());
    }
}
