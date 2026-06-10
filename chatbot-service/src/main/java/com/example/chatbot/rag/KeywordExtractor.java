package com.example.chatbot.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词提取器：负责从 query 和 content 中提取高质量关键词。
 * <p>
 * 提供三类提取能力：
 * 1. 2-gram（过滤停用词和虚词后）
 * 2. 3~6 字中文短语
 * 3. 英文技术标识符
 */
@Component
@Slf4j
public class KeywordExtractor {

    // ========== 停用词 ==========
    private static final Set<String> STOP_WORDS = Set.of(
            "什么", "怎么", "如何", "为什么", "是否", "一个", "一种", "一些",
            "这个", "那个", "好的", "可以", "应该", "需要", "里面", "相关",
            "内容", "实现", "原理", "区别", "作用", "的是", "是一个", "有没有",
            "什么样", "什么样的", "什么的", "怎么样", "怎么回事"
    );

    // ========== 虚词字符（2-gram 中包含这些字则是低质量词）==========
    private static final Set<String> FILLER_CHARS = Set.of(
            "的", "是", "了", "吗", "呢", "啊", "吧", "和", "与", "在",
            "对", "把", "被", "让", "有", "个", "一", "不", "也", "都",
            "就", "还", "很", "会", "能", "要", "到", "从", "为", "以"
    );

    // ========== bad bigrams（明显跨词边界的垃圾 2-gram）==========
    private static final Set<String> BAD_BIGRAMS = Set.of(
            "身计", "的健", "是一", "么样", "样的", "划是", "个好", "的什",
            "么是", "是什", "什么", "怎么", "如何", "好一", "的一",
            "了的", "是的", "了吗", "的呢", "么的", "一的", "好什"
    );

    // ========== 英文技术术语正则 ==========
    private static final Pattern TECHNICAL_TERM_PATTERN = Pattern.compile(
            "\\b[A-Z][a-zA-Z0-9_]+(?:Exception|Error|Config|Service|Controller|Mapper|Repository|Client)|"  // PascalCase + 后缀
            + "\\b[A-Z][a-z]+[A-Z][a-zA-Z]+\\b|"    // PascalCase: ChatMemory, NullPointerException
            + "\\b[a-z]+_[a-z]+\\b|"                 // snake_case: vector_dims
            + "\\b(docker|kubectl|psql|mysql|grep|curl|ssh|nslookup|systemctl|git|npm|mvn)\\b|"  // 命令
            + "\\b(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|COUNT|CREATE|ALTER|DROP)\\b|"  // SQL
            + "\\b\\d+\\.\\d+\\.\\d+\\b|"            // 版本号: 3.2.0
            + "\\b-Xms|-Xmx|-XX:"                    // JVM 参数
    );

    // ========== 中文字符正则 ==========
    private static final Pattern CHINESE_CHAR_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");

    /**
     * 提取高质量 2-gram（已过滤停用词、虚词、badBigrams）。
     */
    public List<String> extractBigrams(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.toLowerCase();
        List<String> bigrams = new ArrayList<>();
        for (int i = 0; i < normalized.length() - 1; i++) {
            String gram = normalized.substring(i, i + 2);
            if (isChineseBigram(gram) && !isBadBigram(gram) && !STOP_WORDS.contains(gram)) {
                bigrams.add(gram);
            }
        }
        return bigrams.stream().distinct().toList();
    }

    /**
     * 提取 3~6 字中文短语。
     * 从连续中文序列中切分出 3~6 字子串。
     */
    public List<String> extractPhrases(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.toLowerCase();
        List<String> phrases = new ArrayList<>();

        // 提取连续中文序列，然后切分
        Matcher matcher = Pattern.compile("[\\u4e00-\\u9fff]+").matcher(normalized);
        while (matcher.find()) {
            String sequence = matcher.group();
            for (int len = Math.min(6, sequence.length()); len >= 3; len--) {
                for (int i = 0; i <= sequence.length() - len; i++) {
                    String phrase = sequence.substring(i, i + len);
                    if (!STOP_WORDS.contains(phrase) && !isOnlyFillerChars(phrase)) {
                        phrases.add(phrase);
                    }
                }
            }
        }

        return phrases.stream().distinct().toList();
    }

    /**
     * 提取英文技术标识符。
     */
    public List<String> extractTechnicalTerms(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> terms = new ArrayList<>();
        Matcher matcher = TECHNICAL_TERM_PATTERN.matcher(text);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        return terms.stream().distinct().toList();
    }

    /**
     * 从 query 中提取查询短语（过滤问句成分后）。
     * 例如 "什么样的健身计划是一个好的健身计划" → ["健身计划", "健身", "计划"]
     */
    public List<String> extractQueryPhrases(String query) {
        if (query == null || query.isBlank()) return List.of();

        // 移除常见问句成分
        String cleaned = query
                .replaceAll("什么样的?", "")
                .replaceAll("什么是", "")
                .replaceAll("是什么", "")
                .replaceAll("怎么(样|回事)?", "")
                .replaceAll("如何", "")
                .replaceAll("为什么", "")
                .replaceAll("是一个?好的?", "")
                .replaceAll("[?？!！。，,、;；:：\\s]+", "")
                .toLowerCase();

        List<String> phrases = new ArrayList<>();

        // 提取连续中文序列，然后按 2~6 字切分
        Matcher matcher = Pattern.compile("[\\u4e00-\\u9fff]+").matcher(cleaned);
        while (matcher.find()) {
            String sequence = matcher.group();
            // 对长序列提取所有 2~6 字子串
            for (int len = Math.min(6, sequence.length()); len >= 2; len--) {
                for (int i = 0; i <= sequence.length() - len; i++) {
                    String phrase = sequence.substring(i, i + len);
                    if (!STOP_WORDS.contains(phrase) && !isOnlyFillerChars(phrase)) {
                        phrases.add(phrase);
                    }
                }
            }
        }

        return phrases.stream().distinct().toList();
    }

    // ========== 判断方法 ==========

    public boolean isStopWord(String term) {
        return STOP_WORDS.contains(term);
    }

    public boolean isBadBigram(String term) {
        return BAD_BIGRAMS.contains(term);
    }

    public boolean isTechnicalTerm(String term) {
        return TECHNICAL_TERM_PATTERN.matcher(term).matches();
    }

    private boolean isChineseBigram(String gram) {
        return CHINESE_CHAR_PATTERN.matcher(gram.substring(0, 1)).find()
                && CHINESE_CHAR_PATTERN.matcher(gram.substring(1, 2)).find();
    }

    private boolean isChineseNgram(String ngram) {
        for (char c : ngram.toCharArray()) {
            if (c < '一' || c > '鿿') return false;
        }
        return true;
    }

    private boolean isOnlyFillerChars(String text) {
        for (char c : text.toCharArray()) {
            if (!FILLER_CHARS.contains(String.valueOf(c))) return false;
        }
        return true;
    }
}
