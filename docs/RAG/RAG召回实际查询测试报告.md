# RAG 召回实际查询测试报告

生成日期：2026-06-15

## 测试结论

本次用 5 条贴近项目实际问法的查询做了代码级召回评测，覆盖普通业务问题、文件问题、JWT、Kafka、Docker/ES 混合中英文技术问题。

结果：

- 代表性查询命中率：5/5。
- Query Rewrite 能把口语化问题压缩成更适合检索的关键词。
- `KeywordExtractor` 已能识别中文后紧贴的英文技术词，例如 `Kafka消息` 中的 `Kafka`、`JWT刷新令牌` 中的 `JWT`。
- 关键词召回仍可走 ES、MySQL FULLTEXT、最近 200 条扫描三层 fallback。
- 向量召回在阈值过滤为空时会取 raw top 兜底，再交给 `HybridRanker` 过滤。

注意：本机 Docker daemon 当前未运行，所以本报告不是“连接本地真实 ES/MySQL 的端到端测试”。本次验证的是真实 RAG 召回代码在 mock 数据源下的代表性查询表现；线上真实效果仍需要部署后重建 ES v2 索引再验证。

## 测试边界

已验证：

- `QueryRewriteService` 的检索词改写。
- `KeywordExtractor` 的关键词和技术词提取。
- `HybridSearchService` 的候选召回链路选择。
- `HybridRanker` 对中文短词和技术词候选的筛选。
- ES、MySQL FULLTEXT、向量 fallback 相关单元测试。

未验证：

- 本地真实 Docker ES 查询。
- 线上真实知识库数据的 `recall@3`、`recall@5`。
- ES v2 index 重建后的线上文档数量和真实高亮片段。

原因：

```text
docker ps
-> Docker daemon 未运行
```

## 修改前基线怎么来的

修改前基线不是线上真实 ES 日志，而是基于本次改造前的代码复盘和同一批 mock 文档推演出来的代码级 baseline。依据来自改造前版本中的这些实现：

- `HybridSearchService.search(...)`：直接用用户原始 query 做意图分析、向量检索和关键词检索，没有 `QueryRewriteService`。
- `ElasticsearchRagService`：ES mapping 使用 `standard analyzer`，查询主要是 `multi_match(title^3,tags^2,content)`，没有 `title.exact`、`tags.exact`、`*.ngram`、`match_phrase` 多路召回。
- `ElasticsearchRagService.queryTerms(...)`：主要按空格和标点切分 matchedTerms，对中文整句和中英文紧贴问题不友好。
- `KeywordExtractor.TECHNICAL_TERM_PATTERN`：能识别部分 PascalCase、snake_case、命令、SQL，但对 `Kafka消息`、`JWT刷新令牌`、`Docker部署ES` 这类中英文紧贴场景不稳定。
- `HybridRanker.hasStrongKeywordSignal(...)`：关键词强命中要求 PascalCase、snake_case、SQL、命令或 `term.length() >= 4`，所以 `退款`、`部署`、`刷新` 这种中文两字高价值词会被过滤。

因此，本次评估的核心不是“旧版完全不能用”，而是旧版在中文短词、口语化问题、中英文混写、ES 中文子串召回这几个场景下不稳定。

## 修改前后召回对比

| 原始问题 | 修改前检索输入 | 修改前召回/入选情况 | 暴露的问题 | 修改后检索输入 | 修改后召回/入选情况 |
| --- | --- | --- | --- | --- | --- |
| 如何退款 | 如何退款 | 关键词候选可能命中 `退款政策`，`matchedTerms=[退款]`，但旧 `HybridRanker` 认为 2 字中文词不是强关键词，最终容易被 `FILTERED_NO_VALID_SIGNAL` 过滤。 | 中文两字核心词被误判为弱信号。 | 退款 | 命中 `退款政策`，`keywordScore=8.0`，`selectedReason=SELECTED_STRONG_KEYWORD`。 |
| 帮我查一下文件上传失败怎么办 | 帮我查一下文件上传失败怎么办 | 可能通过 fallback 扫描命中 `文件上传限制`，但检索 query 很长，包含 `帮我/查一下/怎么办` 等无效成分，FULLTEXT/ES 粗召回不稳定。 | 口语化前后缀污染检索词。 | 文件上传失败 | 命中 `文件上传限制`，`matchedTerms=[文件上传, 上传失败]`，`keywordScore=68.0`。 |
| JWT刷新令牌怎么轮转 | JWT刷新令牌怎么轮转 | 旧技术词正则不稳定识别 `JWT`，中文词如 `刷新/轮转` 又可能因长度不足被过滤，最终容易没有有效入选候选。 | 英文技术词紧贴中文时漏提取，中文动作词又被强关键词规则过滤。 | JWT刷新令牌轮转 | 命中 `JWT 双 Token 机制`，`matchedTerms=[JWT, 刷新, 轮转]`，`keywordScore=16.0`。 |
| Kafka消息为什么会丢失 | Kafka消息为什么会丢失 | 旧技术词正则不稳定识别 `Kafka`，如果文档没有完整中文短语“消息会丢失”，关键词候选可能为空或弱相关。 | 中英文紧贴导致技术实体丢失。 | Kafka消息会丢失 | 命中 `Kafka 可靠性设计`，`matchedTerms=[Kafka]`，`keywordScore=8.0`。 |
| Docker部署ES内存怎么配置 | Docker部署ES内存怎么配置 | 旧规则对 `Docker`、`ES` 这类短技术词识别不足；即使命中 `部署`，2 字中文词也可能被过滤。 | 短英文技术词和中文短词都不稳。 | Docker部署ES内存配置 | 命中 `Elasticsearch 部署说明`，`matchedTerms=[部署]`，`keywordScore=8.0`。 |

这个对比可以解释为什么需要优化：

1. 如果候选阶段就拿不到文档，后面的融合排序没有材料可排。
2. 如果候选能拿到但 `matchedTerms` 太弱，`HybridRanker` 会过滤掉，不会进入 prompt。
3. 如果 query 里有大量口语噪声，ES / FULLTEXT / 向量检索的候选召回会更不稳定。
4. 如果技术词识别失败，技术类知识库的标题和标签优势发挥不出来。

## 无效词到底影响什么

`请问`、`帮我`、`查一下`、`介绍一下`、`如何`、`怎么`、`为什么`、`怎么办`、`有没有`、`相关内容` 这类词，需要准确理解它们的影响范围。

它们通常不是直接影响最终 Java 精排评分。比如 `KeywordExtractor` 里已经有停用词、坏 bigram、虚词过滤，`HybridRanker` 也不会因为出现“请问”“帮我”就直接扣分。

真正的影响主要在“候选召回阶段”：

```text
用户原始 query
  -> ES / MySQL FULLTEXT / 向量检索先召回候选
  -> Java 关键词评分
  -> HybridRanker 过滤
  -> 进入 prompt
```

### 1. 对 ES / FULLTEXT 的影响

ES 和 MySQL FULLTEXT 先负责从大量文档里粗筛候选。无效词不一定会让相关文档完全召不回来，但会让 query 表达变长、变散，相关性计算更容易波动。

例如：

```text
帮我查一下文件上传失败怎么办
```

真实有检索价值的是：

```text
文件上传失败
```

如果文档规模很小，二者可能都能命中。但文档多起来以后，召回只取 TopK，口语词会让 `文件上传失败` 这个核心信号不够集中，相关文档更容易被挤出候选池。

### 2. 对向量检索的影响

向量检索会把整句话做 embedding。短问题里，口语词越多，语义中心越分散。

更稳定的检索表达是：

```text
文件上传失败
```

而不是：

```text
帮我查一下文件上传失败怎么办
```

所以 query rewrite 不是为了让模型“不理解礼貌话”，而是为了让 embedding 更贴近知识库文档的主题表达。

### 3. 对关键词提取和日志的影响

旧逻辑下，长口语 query 会产生更多低质量片段，例如：

```text
帮我、我查、查一、一下、下文、么办
```

这些片段不一定会参与最终加分，但会让 `matchedTerms`、`coveredTerms` 和调试日志变脏，后续排查“为什么没召回”会更困难。

### 4. 更严谨的结论

因此，文档里对无效词的判断应理解为：

> 无效问句词通常不会直接作为高价值命中词加分，也不会直接扣最终评分；它们主要污染召回阶段的 query 表达，降低 ES/FULLTEXT/向量检索的候选稳定性，并增加关键词提取噪声。因此 query rewrite 主要优化的是候选召回质量和日志可解释性，而不是简单修正最终评分公式。

## 从问题到优化动作

| 发现的问题 | 代码位置 | 优化动作 | 预期效果 |
| --- | --- | --- | --- |
| 用户原始 query 直接进入检索，口语噪声多。 | `HybridSearchService.search(...)` | 新增 `QueryRewriteService`，先生成 `retrievalQuery`，后续向量、ES、FULLTEXT 都用改写后的检索词。 | 让“帮我查一下文件上传失败怎么办”压缩成“文件上传失败”。 |
| ES 只用 standard analyzer + 单一路径 multi_match。 | `ElasticsearchRagService.initializeIndexIfNeeded(...)` 和 `searchKeywordCandidates(...)` | 先升级到 `ai_studio_knowledge_v2` 增加 ngram；后续关键词主链路重构已切到 `ai_studio_knowledge_v3`，由 ES BM25 做主排序。 | 提升中文短词、标题、标签、chunk 级内容召回。 |
| ES matchedTerms 只是简单按标点切分。 | `ElasticsearchRagService.queryTerms(...)` | 复用 `KeywordExtractor` 提取技术词、中文短语、bigram，再做最长匹配抑制。 | 日志里能看到真正命中的关键词，后续方便调参。 |
| `Kafka消息`、`JWT刷新` 这种中英文紧贴技术词识别不稳。 | `KeywordExtractor.TECHNICAL_TERM_PATTERN` | 增加 standalone tech token 规则：`(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9+#.-]{1,}(?![A-Za-z0-9])`。 | 能提取 `Kafka`、`JWT`、`Redis`、`Docker`、`ES`、`TTL`。 |
| 中文两字核心词会被过滤。 | `HybridRanker.hasStrongKeywordSignal(...)` | 允许包含中文且长度 >= 2 的词作为强关键词信号。 | `退款`、`部署`、`刷新` 这类短词能进入 prompt。 |
| 向量检索阈值过严时可能完全无候选。 | `VectorRagService.retrieve(...)` | 阈值过滤为空时取 raw top 3 兜底，再交给 `HybridRanker` 过滤。 | 避免语义召回链路直接断掉。 |
| embedding 只看正文 chunk，标题/标签信号弱。 | `VectorIndexingService` | embedding 输入增加 `标题：...`、`标签：...`。 | 标题型、标签型提问更容易召回对应 chunk。 |

## 实际查询样例

测试类：`chatbot-service/src/test/java/com/example/chatbot/rag/RagRecallEvaluationTest.java`

| 原始问题 | 改写后的检索词 | 期望命中文档 | 实际命中文档 | 结果 |
| --- | --- | --- | --- | --- |
| 如何退款 | 退款 | 退款政策 | 退款政策 | 通过 |
| 帮我查一下文件上传失败怎么办 | 文件上传失败 | 文件上传限制 | 文件上传限制 | 通过 |
| JWT刷新令牌怎么轮转 | JWT刷新令牌轮转 | JWT 双 Token 机制 | JWT 双 Token 机制 | 通过 |
| Kafka消息为什么会丢失 | Kafka消息会丢失 | Kafka 可靠性设计 | Kafka 可靠性设计 | 通过 |
| Docker部署ES内存怎么配置 | Docker部署ES内存配置 | Elasticsearch 部署说明 | Elasticsearch 部署说明 | 通过 |

## 改写前后与命中过程

这一组测试的重点不是只看“最后有没有命中”，而是看 query 从用户口语问题进入检索链路后，哪些词被保留、哪些词被删除、最终为什么能命中对应文档。

### 1. 如何退款

改写过程：

```text
原始问题：如何退款
检索词：退款
```

命中结果：

```text
命中文档：退款政策
命中原因：保留核心业务词“退款”，删除问句词“如何”。
keywordScore：8.0
selectedReason：SELECTED_STRONG_KEYWORD
```

微调思路：

用户经常不会直接输入“退款政策”，而是输入“如何退款”“怎么退款”。这类问题里，“如何/怎么”没有检索价值，真正决定召回的是“退款”。所以 query rewrite 的第一类规则就是删除问句成分，保留业务实体词。

对应规则：

```java
.replaceAll("(?i)(怎么办|是什么|怎么做|怎么|如何|为什么|有没有|相关内容)", " ")
```

### 2. 帮我查一下文件上传失败怎么办

改写过程：

```text
原始问题：帮我查一下文件上传失败怎么办
检索词：文件上传失败
```

命中结果：

```text
命中文档：文件上传限制
命中原因：保留“文件上传”“上传失败”两个高价值短语。
matchedTerms：[文件上传, 上传失败]
coveredTerms：[文件上, 件上传, 上传失, 传失败, 文件, 件上, 上传, 传失, 失败]
keywordScore：68.0
selectedReason：SELECTED_STRONG_KEYWORD
```

微调思路：

这个例子暴露的是“口语化前缀 + 问句后缀”会污染检索。`帮我`、`查一下`、`怎么办` 本身不会帮助召回，反而会产生很多无意义 2-gram。微调后先删除礼貌前缀和查询动作，再删除问句后缀，把问题压缩成“文件上传失败”。

对应规则：

```java
.replaceAll("(?i)^(请问|帮我|帮忙|麻烦|我想知道|能不能|可以告诉我)", "")
.replaceAll("(?i)(查一下|介绍一下|说一下|讲一下|讲讲|具体说说|详细讲讲)", " ")
.replaceAll("(?i)(怎么办|是什么|怎么做|怎么|如何|为什么|有没有|相关内容)", " ")
```

这里 `KeywordExtractor` 还会做最长匹配抑制：如果“文件上传”“上传失败”已经命中，就把更碎的“文件”“上传”“失败”等短片段放入 `coveredTerms`，避免重复放大同一个命中信号。

### 3. JWT刷新令牌怎么轮转

改写过程：

```text
原始问题：JWT刷新令牌怎么轮转
检索词：JWT刷新令牌轮转
```

命中结果：

```text
命中文档：JWT 双 Token 机制
命中原因：保留技术词“JWT”和业务动作“刷新”“轮转”。
matchedTerms：[JWT, 刷新, 轮转]
keywordScore：16.0
selectedReason：SELECTED_STRONG_KEYWORD
```

微调思路：

第一次测试时，这类问题的压缩不够干净，因为规则只处理了“怎么做”，没有处理单独出现的“怎么”。但技术类提问很常见的结构就是“XXX怎么配置/怎么轮转/怎么部署”。所以规则里必须单独删除“怎么”，让“轮转”这种真正有检索价值的动作词留下来。

同时，技术词 `JWT` 不能因为紧贴中文就被漏掉。`KeywordExtractor` 增加了 standalone tech token 的正则，用来识别中文文本中的英文技术词。

对应规则：

```java
(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9+#.-]{1,}(?![A-Za-z0-9])
```

### 4. Kafka消息为什么会丢失

改写过程：

```text
原始问题：Kafka消息为什么会丢失
检索词：Kafka消息会丢失
```

命中结果：

```text
命中文档：Kafka 可靠性设计
命中原因：保留技术词“Kafka”，删除问句词“为什么”。
matchedTerms：[Kafka]
keywordScore：8.0
selectedReason：SELECTED_STRONG_KEYWORD
```

微调思路：

这个例子的关键不是中文短语，而是中英文混写。`Kafka消息` 里 `Kafka` 后面直接跟中文，没有英文空格分隔。如果技术词识别只依赖传统英文单词边界，在中文上下文中容易不稳定。微调后用“前后不是英数字”的方式识别技术 token，所以 `Kafka消息`、`Redis缓存`、`Docker部署` 都能提取出英文技术词。

这里也保留了“会丢失”这类中文信息，但最终测试数据中最强命中来自 `Kafka`。这说明当前测试集下文档标题和内容里的技术词是主要召回信号。

### 5. Docker部署ES内存怎么配置

改写过程：

```text
原始问题：Docker部署ES内存怎么配置
检索词：Docker部署ES内存配置
```

命中结果：

```text
命中文档：Elasticsearch 部署说明
命中原因：保留“Docker”“ES”“部署”“内存”“配置”等部署类关键词。
matchedTerms：[部署]
keywordScore：8.0
selectedReason：SELECTED_STRONG_KEYWORD
```

微调思路：

这个例子说明了两点：

第一，query rewrite 需要删除“怎么”，但不能删除“配置”。因为“配置”既可能是问句尾词，也可能是文档主题词，比如“ES 内存配置”。所以当前规则删除“怎么”，保留“配置”。

第二，`Docker`、`ES` 这种短英文技术词必须保留。当前单元测试已验证 `KeywordExtractor` 能提取 `Docker` 和 `ES`，但这条样例最终 mock 文档里的 strongest matched term 是中文“部署”。这说明后续如果要继续提升真实召回，应该让知识库文档标题、标签中也保留 `Docker`、`ES`、`Elasticsearch` 等同义表达。

## 微调方法总结

本次微调遵循的是“少做语义猜测，多保留检索信号”的原则。

### 1. 删除低价值问句成分

删除对象：

```text
请问、帮我、查一下、介绍一下、如何、怎么、为什么、怎么办、有没有、相关内容
```

目标：

- 降低无效 2-gram。
- 避免“怎么”“如何”这类高频词干扰关键词评分。
- 让 ES / FULLTEXT / Java 精排都更聚焦核心词。

### 2. 保留业务实体和技术实体

保留对象：

```text
退款、文件上传失败、JWT、Kafka、Docker、ES、Redis、TTL
```

目标：

- 业务类问题靠中文实体词召回。
- 技术类问题靠英文技术词 + 中文动作词召回。
- 中英文紧贴时也能提取技术词。

### 3. 中文空格只做局部合并

处理方式：

```java
.replaceAll("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])", "")
```

目标：

- `JWT刷新令牌 轮转` 合并为 `JWT刷新令牌轮转`。
- 不破坏 `Redis TTL` 这种英文技术词之间的空格。

### 4. 用最长匹配抑制降低重复计分

例子：

```text
命中长词：文件上传、上传失败
被覆盖短词：文件、上传、失败、文件上、件上传
```

目标：

- 保留最有解释力的命中词。
- 避免一个长短语被拆成多个短片段后重复抬高分数。
- 让日志里的 `matchedTerms` 更适合排查召回问题。

### 5. 后续微调方向

当前 5 条样例能覆盖常见模式，但还不够形成严格离线评测集。下一步应该把真实聊天中的失败 query 收集出来，按类型扩充：

- 问法同义：`退款`、`退钱`、`退费`。
- 故障同义：`失败`、`报错`、`不能用`、`没反应`。
- 技术缩写同义：`ES`、`Elasticsearch`。
- 模块别名：`文件上传`、`图片上传`、`附件上传`。

这些样例积累后，再按 `recall@3` 和 `recall@5` 调整 ES boost、query rewrite 和标签规范，而不是只凭单条日志手动调参。

从测试日志观察到的关键词分数：

| 原始问题 | 命中文档 | keywordScore |
| --- | --- | --- |
| 如何退款 | 退款政策 | 8.0 |
| 帮我查一下文件上传失败怎么办 | 文件上传限制 | 68.0 |
| JWT刷新令牌怎么轮转 | JWT 双 Token 机制 | 16.0 |
| Kafka消息为什么会丢失 | Kafka 可靠性设计 | 8.0 |
| Docker部署ES内存怎么配置 | Elasticsearch 部署说明 | 8.0 |

这里的分数不是最终用户可见分数，而是 `HybridCandidate.keywordScore`，用于后续融合排序。实际 prompt 中是否放入候选，还要经过 `HybridRanker` 的强关键词、向量相似度、fallback 阈值等规则。

## 本次发现的问题

### 1. 口语化问题会污染检索词

问题：

```text
帮我查一下文件上传失败怎么办
```

如果不做改写，`帮我`、`查一下`、`怎么办` 会参与关键词切分，增加无效 2-gram 和低质量短语。

微调：

`QueryRewriteService` 增加口语动词和问句成分清理：

```java
.replaceAll("(?i)(查一下|介绍一下|说一下|讲一下|讲讲|具体说说|详细讲讲)", " ")
.replaceAll("(?i)(怎么办|是什么|怎么做|怎么|如何|为什么|有没有|相关内容)", " ")
```

效果：

```text
帮我查一下文件上传失败怎么办 -> 文件上传失败
```

### 2. “怎么轮转”这类技术问法没有被压缩

问题：

```text
JWT刷新令牌怎么轮转
```

初始规则只处理了 `怎么做`，没有处理单独的 `怎么`，导致检索词里保留问句成分。

微调：

把 `怎么` 也纳入清理范围。

效果：

```text
JWT刷新令牌怎么轮转 -> JWT刷新令牌轮转
```

### 3. 中文词之间残留空格

问题：

清理问句成分后，中文片段之间可能残留空格，影响后续中文短语提取。

微调：

只合并中文字符之间的空白，不影响英文技术词之间的必要空格。

```java
.replaceAll("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])", "")
```

效果：

```text
JWT刷新令牌 轮转 -> JWT刷新令牌轮转
```

### 4. 中英文混写技术词识别不足

问题：

`Kafka消息为什么会丢失`、`JWT刷新令牌怎么轮转` 这类问题里，英文技术词紧贴中文，原先更偏向 `\b` 单词边界，对中文邻接场景不稳定。

微调：

`KeywordExtractor` 的技术词正则增加 standalone tech token 识别：

```java
(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9+#.-]{1,}(?![A-Za-z0-9])
```

效果：

- `Kafka消息为什么会丢失` 能提取 `Kafka`。
- `JWT刷新令牌怎么轮转` 能提取 `JWT`。
- `Redis TTL怎么配置` 能提取 `Redis`、`TTL`。
- `Docker部署ES内存` 能提取 `Docker`、`ES`。

## 当前实际效果

### 关键词层

现在关键词层对以下场景更稳：

- 中文短查询：`如何退款` -> `退款`。
- 口语化业务问题：`帮我查一下文件上传失败怎么办` -> `文件上传失败`。
- 中英文混合技术问题：`Kafka消息为什么会丢失` 能保留 `Kafka`。
- 标题/标签型问题：ES v2 index 中 `title.exact`、`tags.exact`、`title.ngram` 会提供更强召回信号。

链路优先级：

```text
HybridSearchService.searchKeyword(...)
  -> ElasticsearchRagService.searchKeywordCandidates(...)
  -> MySQL FULLTEXT fallback
  -> 最近 200 条扫描 fallback
```

### 向量检索层

现在向量层更偏“召回不漏，再由融合排序过滤”：

- 候选池从 `max(topK * 2, 10)` 扩大为 `max(topK * 4, 20)`。
- 阈值过滤没有结果时，取 raw top 3 兜底。
- embedding 输入加入标题和标签：

```text
标题：...
标签：...
正文 chunk
```

这可以改善“用户问标题/标签，但正文 chunk 里没有完整关键词”的场景。

## 验证命令

已通过：

```bash
mvn -q -pl chatbot-service "-Dtest=RagRecallEvaluationTest,HybridSearchServiceCandidateTest,ElasticsearchRagServiceTest,VectorRagServiceTest" test
```

建议完整回归：

```bash
mvn -q -pl chatbot-service "-Dtest=RagRecallEvaluationTest,HybridSearchServiceCandidateTest,ElasticsearchRagServiceTest,VectorRagServiceTest,HybridRagServiceTest,KnowledgeEventVectorIndexTest" test
mvn -q -DskipTests package
```

## 线上验证计划

如果要确认线上真实效果，需要在服务器上执行：

```bash
docker compose -f docker-compose.prod.yml up -d --build chatbot-service
docker compose -f docker-compose.prod.yml ps elasticsearch
curl -sS http://127.0.0.1:9200/_cluster/health
docker exec chatbot-service sh -c 'printenv | grep APP_RAG_ELASTICSEARCH'
```

然后登录后调用：

```http
POST /api/knowledge/reindex
Authorization: Bearer <access-token>
```

最后检查：

```bash
curl -sS 'http://127.0.0.1:9200/_cat/indices?v'
```

重点看：

- 是否存在当前索引 `ai_studio_knowledge_v3`。
- `docs.count` 是否大于 0。
- 聊天日志里是否出现 ES keyword candidates。

## 后续可继续微调

短期建议：

- 补 10-20 条真实用户 query，形成固定离线评测集。
- 增加 `recall@3`、`recall@5` 指标，而不是只看 top1 是否命中。
- 给 `QueryRewriteService` 增加更多项目内高频问法，例如“报错”“失败”“不能用”“没反应”。
- 对 ES 召回日志增加 query、index、hit count、fallback reason，方便线上确认是否真正走 ES。

中期建议：

- 线上积累失败 query 后，按失败类型调 ES boost 和 query rewrite。
- 如果中文召回仍不稳定，再评估 IK analyzer 或拼音/同义词 analyzer。
- 如果语义问法明显多于关键词问法，再考虑 rerank 模型或更强 embedding 模型。
