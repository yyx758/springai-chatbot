package com.example.chatbot.agent.skill;

import java.util.Collections;
import java.util.List;

public interface SkillDefinition {

    String getId();

    String getName();

    String getDescription();

    default List<String> getRequiredToolBeanNames() {
        return Collections.emptyList();
    }

    String getSystemPromptFragment();

    default int getPriority() {
        return 0;
    }

    default boolean matches(String userMessage) {
        return false;
    }
}
