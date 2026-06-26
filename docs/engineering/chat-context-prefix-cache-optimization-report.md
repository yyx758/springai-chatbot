# 聊天上下文前缀缓存优化测试报告

日期：2026-06-25

## 测试范围

本次优化覆盖普通聊天和 Agent 聊天两条上下文构造链路。

- 普通聊天：`ChatbotService -> ChatContextService.buildConversationContext`
- Agent 聊天：`AgentService -> ChatContextService.buildConversationMessages -> ChatClient.tools(...)`

本次测试没有调用 DeepSeek、OpenAI 或 Ollama 真实模型。测试对象是本地上下文构造逻辑，使用 mock Redis 历史记录，统计上下文构造耗时、字符数和估算 token 数。

## 基线测试

执行命令：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceBenchmarkTest" test
```

优化前结果：

| 指标 | 数值 |
| --- | ---: |
| 执行次数 | 50 |
| 消息数 | 24 |
| Prompt 字符数 | 3129 |
| 估算输入 token | 1764 |
| 稳定前缀字符数 | 45 |
| 稳定前缀占比 | 1.44% |
| 平均上下文构造耗时 | 2.656 ms |

说明：token 是测试里的粗略估算，不是模型服务商返回的真实 token usage。估算规则是中文、标点大致按 1 token 计算，连续英文/数字按约 4 字符 1 token 计算。

## 本次改动

1. 调整普通聊天中的 RAG 上下文位置。

   原顺序：

   ```text
   主 System Prompt
   RAG 知识库上下文
   早期对话滚动摘要
   相关历史片段
   最近 N 轮完整对话
   当前用户输入
   ```

   新顺序：

   ```text
   主 System Prompt
   早期对话滚动摘要
   相关历史片段
   最近 N 轮完整对话
   RAG 知识库上下文
   当前用户输入
   ```

   这样做的目的有两个：

   - 让主 System Prompt 保持在最前面，减少动态 RAG 内容对稳定前缀的干扰。
   - 让 RAG 证据靠近当前用户问题，语义上更像“回答当前问题时可参考的材料”。

2. 调整 Agent 链路中的动态上下文插入位置。

   Agent 自动网页抓取结果和自动 RAG 结果原来是追加到当前用户消息之后。现在改为插入到当前用户消息之前。

   避免这种不自然顺序：

   ```text
   System / 历史上下文
   当前用户输入
   动态网页 / RAG 证据
   ```

3. 增加最近历史单条消息长度上限。

   新配置：

   ```yaml
   app.chatbot.context.recent-message-max-chars: 2000
   ```

   环境变量覆盖：

   ```bash
   APP_CHATBOT_CONTEXT_RECENT_MESSAGE_MAX_CHARS=2000
   ```

   这个配置用于限制最近历史中单条 user/assistant 消息的最大字符数，避免某一条超长历史回答吃掉大部分上下文预算。

4. 增加回归测试和基准测试。

   覆盖点：

   - RAG 上下文位于历史之后、当前用户输入之前。
   - 最近历史单条消息会先被截断，再进入全局上下文预算裁剪。
   - 基准测试打印消息数、字符数、估算 token 和构造耗时。

## 优化后测试

执行命令：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceBenchmarkTest" test
```

优化后结果：

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 执行次数 | 50 | 50 |
| 消息数 | 24 | 24 |
| Prompt 字符数 | 3129 | 3129 |
| 估算输入 token | 1764 | 1764 |
| 稳定前缀字符数 | 45 | 45 |
| 稳定前缀占比 | 1.44% | 1.44% |
| 平均上下文构造耗时 | 2.656 ms | 2.715 ms |

后续单独复测基准测试时，平均上下文构造耗时为 `2.685 ms`。

## 为什么这次没有测出明显 token / 耗时收益

这次测试没有比较出明显的 token 下降或耗时下降，原因是测试边界决定的，不代表优化没有价值。

1. 本次没有调用真实模型。

   当前测试只构造 Spring AI 的 `Message` 列表，不请求 DeepSeek、OpenAI 或 Ollama。因此无法拿到真实的：

   - prompt tokens
   - completion tokens
   - cached input tokens
   - first-token latency
   - full response latency

2. 当前项目没有记录模型服务商的 usage metadata。

   即使真实调用模型，如果不把 provider 返回的 usage 信息记录下来，也无法判断前缀缓存是否命中、缓存了多少 token、实际节省了多少费用。

3. 这次基准样本没有超过 `recent-message-max-chars=2000` 的超长历史消息。

   所以新增的单条历史上限不会改变这组样本的最终字符数和估算 token。它主要保护真实长会话场景，而不是压缩普通中等长度样本。

4. 这次主要优化的是上下文布局，而不是压缩策略。

   调整 RAG 和 Agent 动态上下文的位置，目标是让 prompt 结构更利于模型理解和未来接入 provider prompt caching。它不会天然减少本地构造出来的字符数。

5. 本地构造耗时本来就很小。

   基线约 `2.656 ms`，优化后约 `2.685 ms`，差异在 JVM 单测噪声范围内。真实响应耗时主要来自模型推理、网络和流式输出，不在本次 mock 测试范围内。

## 真实模型 token 测试

用户要求使用真实模型测试后，补充了 Ollama 实测脚本：

```bash
powershell -ExecutionPolicy Bypass -File scripts/measure-chat-context-ollama.ps1 -Model qwen2.5:0.5b -Rounds 3 -NumPredict 48
```

测试模型：

- 服务：`http://localhost:11434`
- 模型：`qwen2.5:0.5b`
- 统计来源：Ollama `/api/chat` 返回的真实字段
  - `prompt_eval_count`：真实 prompt token 数
  - `eval_count`：真实 completion token 数
  - `prompt_eval_duration`：prompt eval 耗时
  - `eval_duration`：生成耗时
  - `total_duration`：模型端总耗时

实测结果：

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 平均 prompt tokens | 1046 | 1046 |
| 平均 completion tokens | 48 | 48 |
| 平均 prompt eval 耗时 | 94.47 ms | 18.03 ms |
| 平均模型端总耗时 | 429.52 ms | 347.62 ms |
| 平均本地 wall time | 430.53 ms | 348.41 ms |

逐轮结果：

| 轮次 | 版本 | prompt tokens | prompt eval ms | completion tokens | total ms |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | 优化前 | 1046 | 275.39 | 48 | 629.03 |
| 1 | 优化后 | 1046 | 46.11 | 48 | 412.52 |
| 2 | 优化前 | 1046 | 4.07 | 48 | 336.68 |
| 2 | 优化后 | 1046 | 4.07 | 48 | 317.44 |
| 3 | 优化前 | 1046 | 3.94 | 48 | 322.86 |
| 3 | 优化后 | 1046 | 3.92 | 48 | 312.89 |

结论：

- 真实 prompt token 数没有下降，前后都是 `1046`。
- 这符合预期，因为本次主要调整上下文顺序，没有删除内容。
- Ollama 第二轮以后 prompt eval 耗时明显降低，说明本地模型存在预热或 prompt 处理缓存效应。
- 第一轮优化后更快，但这种差异受运行顺序、模型内存状态和本地缓存影响，不能严格证明是 prompt 顺序带来的收益。
- Ollama 返回真实 token 计数，但不返回 provider prompt cache 命中 token，因此仍不能测试类似 Claude/OpenAI 的 `cached input tokens`。

## DeepSeek 真实接口测试

随后使用本地 `.env` 中配置的 `DEEPSEEK_API_KEY` 调用 DeepSeek OpenAI 兼容接口。测试脚本不会打印密钥。

脚本：

```bash
powershell -ExecutionPolicy Bypass -File scripts/measure-chat-context-deepseek.ps1 -EnvFile .env -Model deepseek-chat -Rounds 3 -MaxTokens 48
```

测试模型：

- 服务：`https://api.deepseek.com/chat/completions`
- 模型：`deepseek-chat`
- 统计来源：接口返回的真实 `usage`
  - `prompt_tokens`
  - `completion_tokens`
  - `total_tokens`
  - `prompt_cache_hit_tokens`
  - `prompt_cache_miss_tokens`

平均结果：

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 平均 prompt tokens | 977 | 977 |
| 平均 completion tokens | 48 | 48 |
| 平均 total tokens | 1025 | 1025 |
| 平均 prompt cache hit tokens | 597.33 | 597.33 |
| 平均 prompt cache miss tokens | 379.67 | 379.67 |
| 平均本地 wall time | 1455.70 ms | 1143.92 ms |

逐轮结果：

| 轮次 | 版本 | prompt tokens | completion tokens | total tokens | cache hit tokens | cache miss tokens | wall ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 优化前 | 977 | 48 | 1025 | 0 | 977 | 1711.86 |
| 1 | 优化后 | 977 | 48 | 1025 | 0 | 977 | 1234.21 |
| 2 | 优化前 | 977 | 48 | 1025 | 896 | 81 | 1278.95 |
| 2 | 优化后 | 977 | 48 | 1025 | 896 | 81 | 1067.17 |
| 3 | 优化前 | 977 | 48 | 1025 | 896 | 81 | 1376.29 |
| 3 | 优化后 | 977 | 48 | 1025 | 896 | 81 | 1130.37 |

结论：

- DeepSeek 返回的真实 prompt token 前后相同，都是 `977`。
- DeepSeek 返回的 prompt cache 命中 token 前后也相同。第 1 轮未命中缓存，第 2、3 轮命中 `896` tokens，miss `81` tokens。
- 这说明在当前测试样本中，调整 RAG 位置没有减少真实输入 token，也没有提高 DeepSeek 的缓存命中 token 数。
- 优化后 wall time 平均更低，但 3 轮样本太少，且外部 API 延迟受网络和服务端调度影响，不能严格归因为上下文顺序调整。
- 从本次真实接口结果看，本次改动的价值仍主要是“上下文语义顺序更合理”和“超长历史保护”，不是 token 压缩。

## 当前已经确认的收益

可以确认的收益：

- 普通聊天中，动态 RAG 不再紧跟主 System Prompt，稳定前缀布局更干净。
- RAG 证据更靠近当前用户输入，语义顺序更合理。
- Agent 的网页抓取和自动 RAG 上下文不再排在用户输入后面。
- 最近历史支持单条消息上限，降低超长历史挤占上下文预算的风险。
- 回归测试已经锁定这些顺序和截断行为。

不能确认的收益：

- 真实 provider prompt cache 命中率。
- 真实输入 token 计费下降。
- 真实首 token 延迟下降。
- 真实完整响应耗时下降。

这些需要接入模型端 usage 和耗时观测后才能判断。

## 验证命令

已执行：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceTest" test
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceTest,ChatContextServiceBenchmarkTest" test
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceBenchmarkTest" test
```

结果：全部通过。

## 后续建议

下一步建议增加模型调用观测字段：

- 模型名称
- prompt tokens
- completion tokens
- cached input tokens，如果服务商返回
- 首 token 延迟
- 完整响应耗时
- 上下文消息数
- 上下文字符数
- RAG 是否开启
- RAG topK

只有加入这些数据，才能真正比较“优化前后真实 token 成本和响应耗时”，而不是只比较本地 prompt 构造结果。
