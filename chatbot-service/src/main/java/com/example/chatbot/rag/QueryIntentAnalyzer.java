package com.example.chatbot.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 通用查询意图分析器。
 * 根据 query 的文本特征判断问题类型，返回对应的向量/关键词检索权重。
 * 不做领域增强，只做通用类型识别。
 */
@Component
@Slf4j
public class QueryIntentAnalyzer {

    // === 精确查询：英文标识符、类名、方法名、配置项 ===
    private static final Pattern EXACT_KEYWORD_PATTERN = Pattern.compile(
            "[A-Z][a-zA-Z0-9_]+\\.[a-zA-Z]|"
            + "[a-z]+\\.[a-z]+\\.[a-z]+|"
            + "\\b[A-Z][a-zA-Z]+Exception\\b|"
            + "\\b[A-Z][a-zA-Z]+Error\\b|"
            + "\\b(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE)\\b|"
            + "[a-zA-Z_]+\\([a-zA-Z_, ]*\\)|"
            + "\\b[a-z]+_[a-z]+\\b|"
            + "\\b-Xms|-Xmx|-XX:|"
            + "\\b[A-Z][a-z]+[A-Z][a-zA-Z]+\\b"  // PascalCase: ChatMemory, NullPointerException
    );

    // === 命令解释：shell/docker 命令 ===
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "\\b(docker|kubectl|psql|mysql|grep|curl|ssh|nslookup|systemctl|"
            + "git|npm|mvn|pip|chmod|chown|tar|curl|wget|awk|sed|vim|nano|"
            + "cat|ls|cd|mkdir|rm|cp|mv|find|top|htop|df|du|free|ps|kill|"
            + "echo|export|source|crontab|systemd|journalctl)\\b"
    );

    // === 报错排查 ===
    private static final Pattern ERROR_DEBUG_PATTERN = Pattern.compile(
            "报错|异常|失败|不生效|启动不了|连接失败|密码错误|空指针|日志|"
            + "错误|error|exception|fail|crash|timeout|refused|denied|"
            + "排查|调试|debug|定位|原因"
    );

    // === 原理解释 ===
    private static final Pattern SEMANTIC_EXPLAIN_PATTERN = Pattern.compile(
            "是什么|什么是|原理|机制|怎么理解|有什么作用|含义|概念|为什么|怎么工作的|"
            + "是什么意思|代表什么|用来做什么"
    );

    // === 实现方案 ===
    private static final Pattern SOLUTION_DESIGN_PATTERN = Pattern.compile(
            "怎么实现|如何实现|怎么设计|如何设计|怎么让|方案|架构|落地|优化|"
            + "怎么做|如何做|怎么搭建|如何搭建|怎么部署|如何部署|"
            + "工程化|集成|接入|改造"
    );

    // === 对比区别 ===
    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
            "区别|对比|相比|不同|一样吗|哪个好|差异|优劣|比较|"
            + "和.*有什么|跟.*有什么|与.*有什么"
    );

    // === 泛词（低价值，不应该给高分）===
    private static final Pattern GENERIC_WORDS_PATTERN = Pattern.compile(
            "怎么|什么|如何|大模型|上下文|人工智能|机器学习|深度学习|"
            + "框架|技术|方案|系统|平台|服务|功能|模块|接口"
    );

    // === 高价值专有词（应该给高分）===
    private static final Pattern TECHNICAL_TERM_PATTERN = Pattern.compile(
            "[A-Z][a-zA-Z]+|" +                           // PascalCase 类名
            "[a-z]+(?:Exception|Error|Config|Service|Controller|Mapper|Repository)|" + // 技术后缀
            "\\b(SPRING|BOOT|REDIS|KAFKA|MYSQL|DOCKER|KUBERNETES|PGVECTOR|"
            + "HNSW|BM25|RRF|SSE|JWT|RBAC|NACOS|GATEWAY)\\b" // 大写缩写
    );

    /**
     * 分析查询意图，返回类型和权重。
     */
    public QueryIntent analyze(String query) {
        if (query == null || query.isBlank()) {
            return new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);
        }

        String lower = query.toLowerCase();

        // 对比区别（优先级最高，避免被 PascalCase 误匹配）
        if (COMPARISON_PATTERN.matcher(lower).find()) {
            return logIntent(query, QueryType.COMPARISON, 1.2, 0.8);
        }

        // 精确查询：包含英文标识符、类名、SQL
        if (EXACT_KEYWORD_PATTERN.matcher(query).find()) {
            return logIntent(query, QueryType.EXACT_KEYWORD, 0.8, 1.2);
        }

        // 命令解释：包含 shell/docker 命令
        if (COMMAND_PATTERN.matcher(lower).find() && lower.length() < 100) {
            return logIntent(query, QueryType.COMMAND_EXPLAIN, 0.8, 1.2);
        }

        // 报错排查
        if (ERROR_DEBUG_PATTERN.matcher(lower).find()) {
            return logIntent(query, QueryType.ERROR_DEBUG, 0.8, 1.2);
        }

        // 原理解释
        if (SEMANTIC_EXPLAIN_PATTERN.matcher(lower).find()) {
            return logIntent(query, QueryType.SEMANTIC_EXPLAIN, 1.3, 0.7);
        }

        // 实现方案
        if (SOLUTION_DESIGN_PATTERN.matcher(lower).find()) {
            return logIntent(query, QueryType.SOLUTION_DESIGN, 1.3, 0.7);
        }

        // 默认
        return logIntent(query, QueryType.DEFAULT, 1.1, 0.9);
    }

    private QueryIntent logIntent(String query, QueryType type, double vectorWeight, double keywordWeight) {
        log.info("[HybridRAG-Intent] query='{}', queryType={}, vectorWeight={}, keywordWeight={}",
                query.length() > 50 ? query.substring(0, 50) + "..." : query,
                type, vectorWeight, keywordWeight);
        return new QueryIntent(type, vectorWeight, keywordWeight);
    }
}
