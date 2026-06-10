package com.example.chatbot.rag;

/**
 * 查询意图类型枚举，用于动态调整向量/关键词检索权重。
 */
public enum QueryType {

    /** 精确查询：类名、方法名、配置项、SQL、异常名 */
    EXACT_KEYWORD,

    /** 报错排查：包含报错、异常、失败、日志等关键词 */
    ERROR_DEBUG,

    /** 命令解释：docker、psql、mysql 等命令的含义 */
    COMMAND_EXPLAIN,

    /** 原理解释：是什么、为什么、原理、机制 */
    SEMANTIC_EXPLAIN,

    /** 实现方案：怎么实现、如何设计、架构、落地 */
    SOLUTION_DESIGN,

    /** 对比区别：区别、对比、相比、哪个好 */
    COMPARISON,

    /** 默认：无法判断时使用 */
    DEFAULT
}
