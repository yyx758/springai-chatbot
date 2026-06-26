# Tokenizer 机制说明

日期：2026-06-25

本文结合当前代码说明 AI Studio 上下文压缩模块里的 tokenizer 是什么、`jtokkit` 和 `CL100K_BASE` 的关系、token 数是怎么计算的，以及它如何参与上下文压缩决策。

## 1. Tokenizer 解决什么问题

大模型的上下文窗口不是按“字符数”计算，而是按“token 数”计算。

例如同样是几十个字符，下面几类内容的 token 密度不同：

```text
中文自然语言
English text
Java 代码
JSON
Markdown
Git diff
工具调用结果
```

如果只用字符数判断上下文大小，会出现两个问题：

1. 本地觉得 prompt 没超，但模型 API 返回 `prompt too long`。
2. 本地过早裁剪，浪费模型上下文窗口。

所以当前上下文压缩模块引入 tokenizer，用它在请求前估算每个 `ContextSegment` 的 token 数。

当前代码路径：

```text
chatbot-service/src/main/java/com/example/chatbot/context/ContextTokenEstimator.java
```

## 2. jtokkit 是什么

`jtokkit` 是一个 Java tokenizer 库，用来在 Java 进程内按指定 encoding 计算文本 token 数。

当前项目引入方式：

```xml
<dependency>
    <groupId>com.knuddels</groupId>
    <artifactId>jtokkit</artifactId>
    <version>1.1.0</version>
</dependency>
```

它的作用类似 Python 生态里的 `tiktoken`：给定一段文本和一个 encoding，返回这段文本会被切成多少个 token。

在当前项目里，`jtokkit` 不是模型，也不负责摘要、RAG 或生成回答。它只是一个本地 token 估算工具。

## 3. CL100K_BASE 是什么

`CL100K_BASE` 是一种 tokenizer encoding。

可以把它理解为：

```text
tokenizer 的词表 + 分词合并规则
```

同一段文本，用不同 encoding 计算，token 数可能不同。

当前代码使用的是：

```java
EncodingType.CL100K_BASE
```

对应代码：

```java
return Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
```

也就是说：

```text
jtokkit = Java tokenizer 库
CL100K_BASE = jtokkit 使用的一套具体 encoding 规则
```

二者关系类似：

```text
计算器 = jtokkit
计算规则 = CL100K_BASE
```

## 4. 当前为什么选择 CL100K_BASE

当前项目主要使用 DeepSeek / OpenAI 兼容模型接口。

`CL100K_BASE` 是 OpenAI GPT-3.5 / GPT-4 时代常用的 tokenizer encoding，也经常被用作 OpenAI-compatible 模型的本地近似估算。

选择它的原因：

1. Java 项目里接入简单。
2. 比字符数估算准确得多。
3. 对中英文混合、代码、JSON、Markdown、diff 的估算更稳定。
4. 不需要请求模型 API，适合每轮请求前快速预算。

但必须注意：

```text
CL100K_BASE 不等于 DeepSeek 服务端真实 tokenizer。
```

它是请求前预算用的近似值。真实计费 token 仍然以模型 API 返回的 `usage.prompt_tokens` 为准。

## 5. token 数是怎么计算的

从原理上说，`CL100K_BASE` 这类 tokenizer 通常基于 BPE / byte-level BPE 思路：

1. 先把文本转成字节或基础符号序列。
2. 根据 encoding 内置的词表和 merge 规则，把常见片段合并成 token。
3. 每个 token 对应一个 token id。
4. token 数就是 token id 序列的长度。

例如概念上：

```text
"context compression"
```

可能不是按字符切成：

```text
c o n t e x t ...
```

而是按常见片段切成：

```text
context
 compression
```

中文、代码、符号、空格、换行、标点也都会影响最终 token 切分。

当前代码没有自己实现 BPE，而是直接调用 `jtokkit`：

```java
return encoding.countTokens(text);
```

所以实际 token 数由 `jtokkit` 根据 `CL100K_BASE` 的词表和规则计算。

## 6. 当前代码实现

完整实现如下：

```java
@Component
@Slf4j
public class ContextTokenEstimator {

    private final Encoding encoding;

    public ContextTokenEstimator() {
        this.encoding = initializeEncoding();
    }

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        if (encoding != null) {
            try {
                return encoding.countTokens(text);
            } catch (Exception e) {
                log.warn("Tokenizer failed, falling back to heuristic estimate: {}", e.getMessage());
            }
        }
        return heuristicEstimate(text);
    }

    private Encoding initializeEncoding() {
        try {
            return Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        } catch (Exception e) {
            log.warn("Tokenizer unavailable, falling back to heuristic token estimator: {}", e.getMessage());
            return null;
        }
    }
}
```

执行流程：

```text
1. Spring 创建 ContextTokenEstimator Bean
2. 构造函数 initializeEncoding()
3. jtokkit 创建默认 encoding registry
4. 从 registry 里取 CL100K_BASE encoding
5. 每次 estimate(text) 时调用 encoding.countTokens(text)
6. 返回估算 token 数
```

## 7. 降级估算逻辑

如果 `jtokkit` 初始化失败，或者 `encoding.countTokens(text)` 执行失败，系统不会中断聊天流程，而是降级到 `heuristicEstimate()`。

当前降级代码：

```java
private int heuristicEstimate(String text) {
    int tokens = 0;
    int asciiRun = 0;
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c <= 127 && Character.isLetterOrDigit(c)) {
            asciiRun++;
            continue;
        }
        if (asciiRun > 0) {
            tokens += Math.max(1, (asciiRun + 3) / 4);
            asciiRun = 0;
        }
        if (!Character.isWhitespace(c)) {
            tokens++;
        }
    }
    if (asciiRun > 0) {
        tokens += Math.max(1, (asciiRun + 3) / 4);
    }
    return tokens;
}
```

这段逻辑的含义：

1. 连续英文和数字按约 `4 个字符 = 1 token` 估算。
2. 非 ASCII、标点、符号等非空白字符按 `1 字符 = 1 token` 估算。
3. 空白字符不直接计 token。

示例：

```text
helloWorld1234
```

长度约 14，会估算为：

```text
(14 + 3) / 4 = 4 tokens
```

中文：

```text
上下文压缩
```

会按每个非空白字符近似计数。

这个降级逻辑不如 `jtokkit` 准确，只是为了保证系统可用。

## 8. 它如何进入上下文压缩流程

压缩入口在：

```text
ContextCompressionService.compact()
```

关键代码：

```java
List<ContextSegment> segments = new ArrayList<>(inputSegments == null ? List.of() : inputSegments);
segments.forEach(this::fillEstimatedTokens);
```

`fillEstimatedTokens()`：

```java
private void fillEstimatedTokens(ContextSegment segment) {
    fillDefaults(segment);
    if (segment.getEstimatedTokens() <= 0) {
        segment.setEstimatedTokens(tokenEstimator.estimate(segment.getContent()));
    }
}
```

也就是说：

```text
每个 ContextSegment 都会被估算 token。
估算结果写入 segment.estimatedTokens。
```

随后统计总 token：

```java
int originalTokens = totalEstimatedTokens(segments);
int currentTokens = totalEstimatedTokens(segments);
```

统计方法：

```java
private int totalEstimatedTokens(List<ContextSegment> segments) {
    return segments.stream().mapToInt(ContextSegment::getEstimatedTokens).sum();
}
```

## 9. tokenizer 影响哪些压缩决策

### 9.1 是否触发 LLM 摘要压缩

当前逻辑：

```java
if (shouldAutoCompact(currentTokens, tokenBudget)) {
    autoCompact(segments, stats);
}
```

判断方法：

```java
private boolean shouldAutoCompact(int currentTokens, int tokenBudget) {
    if (!compressionProperties.isAutoCompactEnabled() || tokenBudget == Integer.MAX_VALUE || tokenBudget <= 0) {
        return false;
    }
    int threshold = (int) Math.max(1, Math.floor(tokenBudget * compressionProperties.getAutoCompactThresholdRatio()));
    return currentTokens > threshold;
}
```

配置：

```yaml
max-input-tokens: 24000
auto-compact-threshold-ratio: 0.80
```

因此当前触发阈值是：

```text
24000 * 0.80 = 19200 estimated tokens
```

如果 tokenizer 估算当前上下文超过 19200 token，就会触发 LLM 语义摘要压缩。

### 9.2 是否进入 token budget 裁剪

当前逻辑：

```java
while (currentTokens > tokenBudget && segments.size() > 2) {
    RemovedSegments removed = removeBestCandidate(segments);
    ...
    currentTokens -= removed.tokens();
}
```

含义：

```text
如果估算 token 超过 max-input-tokens，
就按 segment 优先级删除低价值上下文，
直到 token 回到预算内。
```

### 9.3 裁剪优先级和释放量

每个 segment 都带有：

```java
private int estimatedTokens;
```

删除某个 segment 后，会把它的 token 数从当前总预算里扣掉：

```java
removedTokens += removed.tokens();
currentTokens -= removed.tokens();
```

最终结果会记录：

```java
removedEstimatedTokens
originalEstimatedTokens
finalEstimatedTokens
```

这些字段用于观测压缩效果。

## 10. tokenizer 和字符预算的关系

当前模块同时保留两套预算：

```text
token budget：主预算
char budget：兜底预算
```

先执行 token budget：

```java
while (currentTokens > tokenBudget) {
    removeBestCandidate();
}
```

再执行字符 budget：

```java
while (currentChars > charBudget) {
    removeBestCandidate();
}
```

为什么还保留字符预算？

1. tokenizer 只是近似估算。
2. 有些 provider 对请求体大小、网关大小、日志大小也可能有字符/字节层面的限制。
3. 字符预算可以作为最后一道保护。

## 11. 和真实模型 usage 的关系

当前 tokenizer 是请求前估算：

```text
request before -> local estimated tokens
```

模型 API 的 usage 是请求后真实统计：

```text
request after -> provider usage.prompt_tokens
```

两者关系：

| 来源 | 时机 | 准确性 | 用途 |
| --- | --- | --- | --- |
| `jtokkit + CL100K_BASE` | 请求前 | 近似 | 预算、压缩、是否触发摘要 |
| provider `usage.prompt_tokens` | 请求后 | 最准 | 计费、真实统计、校准 |

所以当前系统应该这样理解：

```text
本地 tokenizer 决定“发请求前要不要压缩”。
provider usage 决定“真实消耗了多少 token”。
```

## 12. 为什么 tokenizer 不等于真实 DeepSeek token

当前使用 `CL100K_BASE` 是工程近似。

DeepSeek 服务端可能使用自己的 tokenizer 或兼容 tokenizer。即使 API 兼容 OpenAI，也不代表 tokenizer 规则完全一样。

因此：

```text
estimatedTokens=1745
```

不一定等于真实：

```json
"prompt_tokens": 1745
```

两者可能接近，也可能有一定误差。

后续更严谨的做法：

1. 每次请求后保存 provider usage。
2. 比较 `estimatedTokens` 和 `usage.prompt_tokens`。
3. 按模型维护一个误差系数。
4. 压缩预算使用更保守的安全系数。

例如：

```text
safeEstimatedTokens = estimatedTokens * 1.15
```

## 13. 当前 benchmark 里的 token 数

当前测试输出：

```text
[ContextBenchmark] runs=50 messages=24 chars=3129 estimatedTokens=1745 stablePrefixChars=45 stablePrefixRatio=1.44% avgBuildMs=3.268
```

这里的：

```text
estimatedTokens=1745
```

就是 `ContextTokenEstimator` 使用 `jtokkit + CL100K_BASE` 算出来的估算 token。

它不是 API 真实 usage。

## 14. 当前实现的优点

1. 比字符数预算更接近模型真实限制。
2. Java 本地完成，不增加网络请求。
3. 支持每个 segment 独立估算，便于按价值裁剪。
4. tokenizer 失败有降级逻辑，不会阻断聊天。
5. 能支撑 auto compact 阈值判断。

## 15. 当前实现的限制

1. `CL100K_BASE` 不是 DeepSeek 的官方真实 tokenizer。
2. 没有按模型动态选择 encoding。
3. 没有把 provider usage 回填到统计系统。
4. 没有建立 estimated token 和 real token 的误差校准。
5. 每次构造上下文都会估算 segment token，后续可以做缓存优化。

## 16. 后续建议

建议下一阶段做：

1. 增加模型到 tokenizer 的配置：

   ```yaml
   app:
     chatbot:
       context:
         compression:
           tokenizer:
             provider: jtokkit
             encoding: cl100k_base
   ```

2. 记录真实 usage：

   ```text
   estimated_prompt_tokens
   actual_prompt_tokens
   prompt_cache_hit_tokens
   prompt_cache_miss_tokens
   model_name
   ```

3. 增加误差校准：

   ```text
   model=deepseek-chat
   avg(actual / estimated)=1.08
   safe_factor=1.15
   ```

4. 增加 segment token 缓存：

   ```text
   cache key = sha256(segment.content)
   value = estimatedTokens
   ```

5. 如果后续接入 Qwen、Llama、Claude、Gemini，可按模型族扩展 tokenizer：

   ```text
   OpenAI/DeepSeek 近似：cl100k_base 或 o200k_base
   Qwen/Llama：HuggingFace tokenizer 或 SentencePiece
   Claude/Gemini：优先 provider usage + 保守估算
   ```

## 17. 一句话总结

当前 tokenizer 机制是：

```text
用 jtokkit 这个 Java tokenizer 库，
按 CL100K_BASE 这套 encoding 规则，
在请求前估算每段上下文的 token 数，
并用 estimatedTokens 决定是否触发 LLM 摘要、是否裁剪低价值上下文，以及压缩前后释放了多少 token。
```

它是上下文压缩模块的“预算尺子”，不是最终计费依据。最终真实 token 仍应以模型 API 返回的 usage 为准。
