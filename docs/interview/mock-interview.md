# AI Studio — 模拟面试与参考答案

---

## 第一部分：项目深度问题

---

### Q1：简单介绍一下你的项目

**参考回答：**

AI Studio 是一个基于 Spring Boot 3.2 + Spring AI 的智能客服平台，支持双模型对话（DeepSeek + Ollama）、RAG 知识库检索增强、AI Agent 工具调用、多模态图文分析。

技术栈包括 Spring Cloud Gateway + Nacos 微服务架构，Kafka 事件驱动异步通信，PGVector 向量数据库，Redis 缓存，MySQL 持久化，Docker 容器化部署。

核心亮点是 **AI Agent 工具治理框架**——对工具做三级风险分级（只读/低风险写/需确认），每次调用写审计日志，危险操作需要用户二次确认才能执行。还有一个 **混合 RAG 检索系统**，结合关键词评分和 PGVector 向量检索，用加权 RRF 融合排序。

---

### Q2：为什么要做 Agent 工具风险分级？不能直接让大模型调用所有工具吗？

**参考回答：**

不能。三个原因：

1. **LLM 会犯错**——用户说"怎么删除文档"是问方法，LLM 可能误判成要执行删除
2. **LLM 不承担后果**——它说"我帮你删了"，但删错了它不负责，人类必须有最终确认权
3. **没有审计日志 = 没有追溯能力**——出了问题不知道谁在什么时候做了什么

所以我做了三级分类：
- READ_ONLY：搜索、列出、查看，无风险直接执行
- LOW_RISK_WRITE：创建、更新，有审计日志但直接执行
- REQUIRE_CONFIRMATION：删除，不直接执行，创建 PendingAction，用户点确认才执行

核心思想是：**让 LLM 有建议权，让人类有最终决策权。**

---

### Q3：Pending Action 两阶段确认是怎么实现的？

**参考回答：**

删除操作分两步：

**第一阶段：LLM 调用删除工具，但不真正删除**

```
LLM 调用 requestDeleteKnowledgeDocument(documentId=13)
    → 不直接执行
    → 创建 PendingAction 记录（status=PENDING, expireTime=+10min）
    → 返回 { requiresConfirmation: true, actionId: 456 }
    → SSE 推送到前端
    → 前端渲染 Confirm 按钮
```

**第二阶段：用户点确认，才真正执行**

```
用户点击 Confirm
    → POST /api/chat/agent/actions/456/confirm
    → 后端校验：userId 匹配 + 状态是 PENDING + 未过期
    → ragService.deleteDocument() 执行删除
    → 状态改为 CONFIRMED
```

**防重复点击**：前端按钮点击后立即 disabled；后端状态检查 PENDING → CONFIRMED 后再点报错。

**防越权**：查询时加 userId 条件，只能操作自己的 PendingAction。

**防过期**：10 分钟后自动失效。

---

### Q4：LLM 怎么知道要调用什么工具？

**参考回答：**

通过两个机制：

**1. 工具注册**——Spring AI 自动把 `@Tool` 注解转换为 OpenAI Function Calling 格式：

```java
ChatClient.create(model)
    .tools(knowledgeReadTools, knowledgeWriteTools, ...)
    .toolContext(toolContext)
    .stream()...
```

Spring AI 读取每个方法的 `@Tool(description="...")` 和 `@ToolParam(description="...")`，自动生成 JSON Schema 发给 LLM。

**2. System Prompt 引导**——在提示词里明确告诉 LLM 什么时候该调什么工具：

```
"列出/查看/搜索/管理/所有/全部" → 调 listAllKnowledgeDocuments
"XXX是什么/怎么用/原理" → 调 searchKnowledge
"创建/保存/写入" → 调 createKnowledgeDocument
"删除" → 先调 listAllKnowledgeDocuments，再调 requestDeleteKnowledgeDocument
```

LLM 收到工具列表 + System Prompt + 用户消息，自己决定调哪个。

---

### Q5：如果用户只是问"怎么删除知识库文档"，模型误判成要调用删除工具怎么办？

**参考回答：**

三层防护：

**第一层：System Prompt 区分意图**

Prompt 里明确告诉 LLM 区分"询问方法"和"请求执行"。但 LLM 可能仍会误判。

**第二层：工具风险分级**

即使 LLM 误判调用了删除工具，`requestDeleteKnowledgeDocument` 是 REQUIRE_CONFIRMATION 级别，不会直接执行。

**第三层：Pending Action 确认**

```
LLM 误判 → 调用 requestDeleteKnowledgeDocument
    → 创建 PendingAction（不删除）
    → 返回 { requiresConfirmation: true }
    → 前端渲染 Confirm 按钮
    → 用户不点确认 → 不执行
```

**即使 LLM 误判，用户不点确认就不会删。最终执行权在人类手里。**

---

### Q6：混合检索是怎么做的？为什么不用单一检索？

**参考回答：**

单一检索各有缺陷：

| | 关键词检索 | 向量检索 |
|---|---|---|
| "健身计划"搜"训练方案" | ❌ 字面不匹配 | ✅ 语义相似 |
| "NullPointerException"搜"空指针" | ✅ 精确匹配 | ⚠️ 可能不精确 |

混合检索取两者之长：

```
用户查询
    ↓
QueryIntentAnalyzer 判断问题类型，动态调整权重
    ↓
┌─────────────────┐  ┌─────────────────┐
│ 关键词检索       │  │ 向量检索         │
│ 2-gram + 短语    │  │ PGVector        │
│ + 技术词提取     │  │ 余弦相似度       │
└────────┬────────┘  └────────┬────────┘
         │                    │
         └────────┬───────────┘
                  ↓
         加权 RRF 融合排序
         score = vectorWeight/(k+vectorRank)
               + keywordWeight/(k+keywordRank)
                  ↓
         规则型 Rerank 过滤
         向量分数 + 关键词命中双重验证
                  ↓
         top-3 进入 LLM Prompt
```

**权重动态调整**：精确查询（类名、命令）加大关键词权重；原理问题加大向量权重。

---

### Q7：RRF 是怎么算的？为什么不用简单的分数相加？

**参考回答：**

RRF（Reciprocal Rank Fusion）用**排名**而不是原始分数来融合：

```
score = vectorWeight / (k + vectorRank) + keywordWeight / (k + keywordRank)
```

不用分数相加的原因：**关键词分数和向量分数量纲完全不同。**

```
关键词分数：0~200+（自定义打分，标题+40，正文+30...）
向量分数：0~1（cosine similarity）

直接相加：100 + 0.5 = 100.5 → 关键词分数碾压向量分数
RRF：用排名代替分数，消除量纲差异
```

RRF 是 Elasticsearch、Weaviate、Pinecone 等主流系统的标准做法。

---

### Q8：你做了哪些安全防御？

**参考回答：**

五层安全防御：

| 层 | 措施 | 实现 |
|---|------|------|
| 认证 | JWT 双令牌 | Access Token 30min + Refresh Token 7天 |
| 鉴权 | 双重校验 | Gateway + Service 两层 JWT 验证 |
| 工具治理 | 三级风险分级 | READ_ONLY / LOW_RISK_WRITE / REQUIRE_CONFIRMATION |
| 确认机制 | Pending Action | 删除操作需用户二次确认 |
| 安全防护 | Anti-SSRF + 路径穿越 | URL 校验 + 路径 normalization |

---

### Q9：Kafka 在你项目里做什么？怎么保证消息不丢？

**参考回答：**

Kafka 用于三个场景的异步通信：

1. **聊天记录持久化**：对话完成后发事件，Consumer 异步写 MySQL + Redis
2. **知识库索引更新**：文档创建/删除时发事件，Consumer 异步更新向量索引
3. **邮件通知**：验证码发送通过 Kafka 异步处理

消息可靠性保证：

| 机制 | 实现 |
|------|------|
| 手动 ACK | `ack-mode: manual_immediate`，处理成功才确认 |
| 重试 | `DefaultErrorHandler` 重试 3 次，间隔 1 秒 |
| 死信队列 | 重试失败后路由到 `chat.events.DLT` |
| DLT 消费者 | 专门记录死信消息，便于排查 |

---

### Q10：Redis 在你项目里做什么？缓存策略是什么？

**参考回答：**

Redis 有三个用途：

1. **聊天历史缓存**：保留最近 N 轮对话的完整对象
2. **Refresh Token 存储**：UUID token 存 Redis，支持吊销
3. **验证码存储**：邮箱验证码的临时存储

缓存策略是 **Write-Behind + 滑动窗口**，不是经典的 Cache-Aside：

```
写入：MySQL 写入成功 → 异步追加到 Redis List → trim 保留最近 5 条
读取：直接从 Redis List 读，不需要查 MySQL
降级：Redis 失败 → 删除缓存 → 下次从 MySQL 重建
```

选择这种方案的原因：聊天历史是追加型数据，读多写多，用滑动窗口让最近 N 轮对话永远在内存里。

---

### Q11：JWT 双令牌是怎么设计的？

**参考回答：**

```
登录成功
    ↓
签发 Access Token（HS256，30 分钟有效）
签发 Refresh Token（UUID，存 Redis，7 天有效）
    ↓
前端存储：localStorage

Access Token 过期
    ↓
前端自动用 Refresh Token 调 /api/auth/refresh
    ↓
后端：Redis 查找 Refresh Token → 删除旧的 → 签发新的 Access Token + Refresh Token
    ↓
原子轮转：旧 Refresh Token 用完即删，防止重放
```

**Refresh Token 存 Redis 而不是 JWT 的原因**：JWT 一旦签发无法撤销，Refresh Token 存 Redis 可以随时吊销。

---

### Q12：微服务之间怎么通信的？

**参考回答：**

两种方式：

**同步调用**：chatbot-service → file-service（HTTP RestTemplate）

```
chatbot-service 需要文件操作时
    → RestTemplate 调 file-service:8081/api/files/...
    → 等待响应
```

**异步通信**：chatbot-service ↔ Kafka → Consumer

```
对话完成 → 发 Kafka 事件 → Consumer 异步写 MySQL + Redis
文档创建 → 发 Kafka 事件 → Consumer 异步更新向量索引
```

服务发现通过 Nacos，Gateway 通过 `lb://chatbot-service` 负载均衡。

---

### Q13：Docker 部署是怎么做的？生产环境怎么优化？

**参考回答：**

7 个容器：chatbot-service、file-service、gateway、MySQL、Redis、Kafka、Nacos（+ PGVector 可选）。

生产环境针对 2GB 内存优化：

| 组件 | 优化措施 |
|------|---------|
| chatbot-service | SerialGC + 堆限制 256MB |
| gateway/file-service | 堆限制 128MB |
| Redis | maxmemory 32MB + allkeys-lru |
| MySQL | 关闭 performance schema + binlog |
| Kafka | 堆限制 200MB |

Dockerfile 用多阶段构建：Maven 编译 → Alpine JRE 运行，镜像体积小。

---

## 第二部分：Java 八股

---

### Q14：HashMap 底层实现？JDK 8 有什么变化？

**参考回答：**

JDK 7：数组 + 链表，头插法，多线程扩容可能死循环。

JDK 8：数组 + 链表 + 红黑树，尾插法。

```
put 流程：
1. 计算 hash：(h = key.hashCode()) ^ (h >>> 16)
2. 定位桶：(n-1) & hash
3. 桶为空 → 直接插入
4. 桶不为空：
   a. key 相同 → 覆盖 value
   b. 是 TreeNode → 红黑树插入
   c. 是链表 → 遍历链表，尾插法
      - 链表长度 >= 8 且数组长度 >= 64 → 树化为红黑树
      - 链表长度 >= 8 且数组长度 < 64 → 先扩容
5. size > threshold → 扩容（2 倍）
```

---

### Q15：ConcurrentHashMap 怎么保证线程安全？

**参考回答：**

JDK 7：分段锁（Segment），每个段独立加锁。

JDK 8：CAS + synchronized，锁粒度细化到桶级别。

```
put 流程：
1. 计算 hash，定位桶
2. 桶为空 → CAS 插入（无锁）
3. 桶不为空 → synchronized 锁住桶头节点
4. 链表/红黑树操作
```

**为什么不用 Hashtable 或 Collections.synchronizedMap？**
- Hashtable：全表锁，并发度低
- synchronizedMap：同上
- ConcurrentHashMap：分段锁/CAS，并发度高

---

### Q16：线程池参数怎么设计？

**参考回答：**

```
corePoolSize：核心线程数，一直存活
maximumPoolSize：最大线程数
keepAliveTime：非核心线程空闲存活时间
workQueue：等待队列
handler：拒绝策略
```

**任务类型决定参数：**

| 任务类型 | corePoolSize | 队列 | 说明 |
|---------|-------------|------|------|
| CPU 密集型 | N+1 | 小队列 | 线程数 ≈ CPU 核心数 |
| IO 密集型 | 2N | 大队列 | 线程数 ≈ 2 × CPU 核心数 |

**拒绝策略：**
- AbortPolicy：抛异常（默认）
- CallerRunsPolicy：调用者执行
- DiscardPolicy：静默丢弃
- DiscardOldestPolicy：丢弃最老的任务

---

### Q17：Spring Bean 的生命周期？

**参考回答：**

```
实例化 → 属性注入 → 初始化 → 使用 → 销毁

详细：
1. 实例化（构造函数）
2. 属性注入（@Autowired, @Value）
3. Aware 接口回调（BeanNameAware, BeanFactoryAware...）
4. BeanPostProcessor.postProcessBeforeInitialization
5. @PostConstruct
6. InitializingBean.afterPropertiesSet
7. init-method
8. BeanPostProcessor.postProcessAfterInitialization
9. 使用
10. @PreDestroy
11. DisposableBean.destroy
12. destroy-method
```

---

### Q18：Spring AOP 的实现原理？

**参考回答：**

Spring AOP 基于动态代理：

- **JDK 动态代理**：目标类实现了接口 → `Proxy.newProxyInstance()`
- **CGLIB 代理**：目标类没有实现接口 → 生成子类字节码

```
调用流程：
1. Spring 为 Bean 创建代理对象
2. 调用方法时，先进入代理的 invoke 方法
3. 代理执行 Before 通知
4. 代理执行目标方法
5. 代理执行 After 通知
6. 返回结果
```

---

### Q19：MySQL 索引原理？什么情况索引失效？

**参考回答：**

MySQL InnoDB 使用 B+ 树索引：

```
B+ 树特点：
- 非叶子节点只存键值（索引）
- 叶子节点存数据，叶子节点之间有链表
- 范围查询高效（叶子节点链表遍历）
```

**索引失效的场景：**

| 场景 | 示例 |
|------|------|
| 最左前缀原则违反 | 联合索引 (a,b,c)，WHERE b=1 失效 |
| 对索引列使用函数 | WHERE YEAR(create_time) = 2026 |
| 隐式类型转换 | varchar 列 WHERE id = 123（数字） |
| LIKE 左模糊 | WHERE name LIKE '%abc' |
| OR 连接非索引列 | WHERE indexed_col=1 OR non_indexed_col=2 |
| NOT IN / NOT EXISTS | 某些情况下失效 |

---

### Q20：Redis 有哪些数据类型？各自的应用场景？

**参考回答：**

| 类型 | 底层结构 | 场景 |
|------|---------|------|
| String | SDS | 缓存、计数器、分布式锁 |
| List | 双向链表 | 消息队列、最新列表、**聊天历史** |
| Hash | 哈希表 | 对象存储（用户信息） |
| Set | 哈希表（无序） | 标签、共同好友 |
| ZSet | 跳表 + 哈希表 | 排行榜、延迟队列 |
| Bitmap | 位数组 | 签到、布隆过滤器 |
| HyperLogLog | 概率算法 | UV 统计 |

**你的项目用到的：**
- List：聊天历史缓存（最近 N 轮对话）
- String：Refresh Token 存储、验证码存储

---

### Q21：Redis 缓存穿透、击穿、雪崩怎么解决？

**参考回答：**

| 问题 | 描述 | 解决方案 |
|------|------|---------|
| **穿透** | 查询不存在的数据，每次都打到 DB | 布隆过滤器 / 缓存空值 |
| **击穿** | 热点 key 过期，大量请求同时打到 DB | 互斥锁 / 逻辑过期 / 永不过期 |
| **雪崩** | 大量 key 同时过期 | 过期时间加随机值 / 多级缓存 |

---

### Q22：Spring Boot 自动配置原理？

**参考回答：**

```
@SpringBootApplication
    ↓
@EnableAutoConfiguration
    ↓
SpringFactoriesLoader.loadFactoryNames()
    ↓
读取 META-INF/spring.factories
    ↓
找到所有 AutoConfiguration 类
    ↓
@ConditionalOnClass / @ConditionalOnMissingBean 等条件过滤
    ↓
满足条件的配置类生效，创建 Bean
```

---

### Q23：Spring Cloud Gateway 工作原理？

**参考回答：**

Gateway 基于 WebFlux（响应式），核心组件：

```
请求 → Route（路由匹配） → Filter Chain（过滤器链） → Proxy（代理到后端）
```

- **Route**：根据 Path、Header 等匹配请求，转发到目标服务
- **Filter**：Pre Filter（鉴权、限流）→ 路由 → Post Filter（响应处理）
- **服务发现**：从 Nacos 获取服务地址，`lb://service-name` 负载均衡

你的项目里 Gateway 做了：
1. JWT 鉴权（AuthGlobalFilter）
2. 路由转发（lb://chatbot-service）
3. CORS 配置

---

## 第三部分：场景设计题

---

### Q24：如果让你重新设计这个项目，你会改什么？

**参考回答：**

1. **数据库隔离**：chatbot-service 和 file-service 目前共用一个 MySQL，应该各自独立
2. **熔断器**：服务间调用加 Resilience4j 熔断，防止级联故障
3. **MCP 独立部署**：当前 MCP 耦合在 chatbot-service 里，应该拆成独立服务
4. **WebSocket**：替换 SSE，支持真正的双向通信
5. **向量检索默认开启**：混合检索已经是默认配置，但阈值需要根据实际数据调优

---

### Q25：知识库文档量增大到 10 万篇，系统会有什么瓶颈？

**参考回答：**

| 瓶颈 | 原因 | 解决方案 |
|------|------|---------|
| 关键词检索变慢 | 全表扫描 10 万文档 | 加 MySQL 索引 / 引入 Elasticsearch |
| 向量检索变慢 | PGVector 线性扫描 | HNSW 索引已经用了，调参 |
| Embedding API 成本 | 每次创建文档都要调 API | 批量处理 / 缓存已计算的向量 |
| RAG 注入上下文过长 | top-3 chunk 拼接后超 token 限制 | 减少 chunk 数 / 压缩 snippet |

---

### Q26：如果 Kafka 挂了怎么办？

**参考回答：**

当前设计有降级：

```
Kafka 挂了
    ↓
聊天记录：同步写 MySQL（降级模式）
    ↓
向量索引：indexStatus 停留在 PENDING_INDEX
    ↓
Kafka 恢复后：重新消费积压消息
    ↓
死信队列：重试失败的消息记录在 DLT
```

但当前代码没有实现同步写 MySQL 的降级路径，这是可以改进的地方。

---

### Q27：你怎么保证 RAG 检索结果的质量？

**参考回答：**

四层保障：

1. **QueryIntent 根据问题类型调权重**——精确查询加大关键词权重，避免语义偏移
2. **加权 RRF 按排名融合**——两路都命中的文档分数叠加，排更前
3. **规则型 Rerank 过滤**——向量分数 + 关键词命中双重验证
4. **自动注入 LLM 上下文**——检索结果直接拼入 System Prompt，LLM 基于知识库回答

还有混合检索的 fallback 机制：向量失败时自动降级到关键词模式，保证服务可用。
