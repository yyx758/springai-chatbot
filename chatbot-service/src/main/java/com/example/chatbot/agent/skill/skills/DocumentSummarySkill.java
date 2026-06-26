package com.example.chatbot.agent.skill.skills;

import com.example.chatbot.agent.skill.SkillDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class DocumentSummarySkill implements SkillDefinition {

    private static final Pattern TRIGGER = Pattern.compile(
            "(总结|摘要|概括|归纳|提炼|梳理|帮我总结|帮我摘要|帮我概括|帮我归纳)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getId() {
        return "document-summary";
    }

    @Override
    public String getName() {
        return "文档总结";
    }

    @Override
    public String getDescription() {
        return "用户需要对文档、知识库内容或上传的文件进行总结、摘要、归纳时触发";
    }

    @Override
    public List<String> getRequiredToolBeanNames() {
        return List.of("fileReadTools", "knowledgeReadTools", "workspaceTools", "chatHistoryTools");
    }

    @Override
    public String getSystemPromptFragment() {
        return """
                【当前技能：文档总结】

                你正在执行「文档总结」任务，请遵循以下规则：

                1. **定位文档**
                   - 如果用户提到了上传的文件，用 listUserFiles 查找，再用 getFileInfo 获取详情
                   - 如果用户提到了知识库文档，用 searchKnowledge 或 listAllKnowledgeDocuments 查找
                   - 如果用户提到了工作区文件，用 readWorkspaceFile 读取内容

                2. **总结策略**
                   - 先通读全文，提取核心主题和结构
                   - 分层次输出：核心观点 → 关键细节 → 行动建议
                   - 如果文档较长，按章节分别总结后再给整体摘要

                3. **交互追问**
                   - 总结完成后，主动提示用户可以追问某个部分
                   - 支持用户要求"展开第X部分"或"重点说说YYY"

                4. **格式要求**
                   - 使用 Markdown 标题和列表
                   - 关键术语加粗
                   - 总结长度控制在原文的 20%-30%
                """;
    }

    @Override
    public boolean matches(String userMessage) {
        return TRIGGER.matcher(userMessage).find();
    }

    @Override
    public int getPriority() {
        return 10;
    }
}
