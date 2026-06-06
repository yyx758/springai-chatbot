# AI Agent 搭建指南

> 从聊天机器人升级为有"动手能力"的智能代理

---

## 目录

- [聊天机器人 vs Agent](#聊天机器人-vs-agent)
- [Agent 四大核心能力](#agent-四大核心能力)
- [Function Calling（工具调用）](#function-calling工具调用)
- [ReAct 循环](#react-循环)
- [Spring AI 实现 Agent](#spring-ai-实现-agent)
- [实战：搭建你的第一个 Agent](#实战搭建你的第一个-agent)
- [常用工具设计](#常用工具设计)
- [进阶：多工具协作](#进阶多工具协作)
- [与当前项目集成](#与当前项目集成)

---

## 聊天机器人 vs Agent

```
聊天机器人（你现在的能力）：
  用户："帮我查一下今天天气"
  AI："我无法获取实时天气信息，建议你打开天气应用查看。"
  → 只能生成文本，无法行动

Agent（升级后的能力）：
  用户："帮我查一下今天天气"
  AI：[调用天气API] → 获取到"重庆，晴，28°C"
  AI："今天重庆天气晴朗，气温28°C，适合出门。"
  → 能调用工具获取真实信息并行动
```

**核心区别：**

| | 聊天机器人 | Agent |
|---|---|---|
| 输入 | 用户消息 | 用户消息 |
| 处理 | LLM 生成文本 | LLM 推理 → 调用工具 → 观察结果 → 继续推理 |
| 输出 | 文本回复 | 文本回复 + 实际行动（查数据库、调API、操作文件等） |
| 能力 | 限于训练数据 | 无限扩展（加工具就能做新事情） |

---

## Agent 四大核心能力

```
┌─────────────────────────────────────────────┐
│                   Agent                     │
│                                             │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐    │
│  │  工具    │  │  规划    │  │  记忆    │    │
│  │ (Tools)  │  │(Planning)│  │(Memory)  │    │
│  └─────────┘  └─────────┘  └─────────┘    │
│       ▲            ▲            ▲          │
│       │            │            │          │
│       └────────────┼────────────┘          │
│                    │                       │
│              ┌──────────┐                  │
│              │  推理引擎  │                  │
│              │   (LLM)   │                  │
│              └──────────┘                  │
│                    │                       │
│              ┌──────────┐                  │
│              │  观察结果  │                  │
│              │(Observation)│                │
│              └──────────┘                  │
└─────────────────────────────────────────────┘
```

### 1. 工具（Tools）

Agent 可以调用的函数，每个工具完成一个具体任务：

```java
// 工具：查天气
@Tool(description = "查询指定城市的天气信息")
public WeatherInfo getWeather(String city) {
    return weatherApi.query(city);
}

// 工具：查数据库
@Tool(description = "查询用户的订单记录")
public List<Order> queryOrders(Long userId) {
    return orderMapper.selectByUserId(userId);
}

// 工具：发邮件
@Tool(description = "发送邮件到指定地址")
public String sendEmail(String to, String subject, String body) {
    emailService.send(to, subject, body);
    return "邮件已发送";
}
```

### 2. 规划（Planning）

LLM 根据用户需求，决定需要调用哪些工具、按什么顺序调用：

```
用户："帮我查一下重庆天气，如果下雨就发邮件提醒我带伞"

LLM 推理：
  Step 1: 需要先查天气 → 调用 getWeather("重庆")
  Step 2: 看结果是否下雨 → 观察结果
  Step 3: 如果下雨 → 调用 sendEmail(...)
```

### 3. 记忆（Memory）

- **短期记忆**：当前对话上下文（聊天历史）
- **长期记忆**：向量数据库中的知识（RAG）

### 4. 观察（Observation）

工具执行后的返回结果，LLM 看到结果后决定下一步：

```
LLM 调用 getWeather("重庆")
  ↓
观察结果：{ "city": "重庆", "weather": "雨", "temp": "22°C" }
  ↓
LLM 判断：下雨了，需要发邮件
  ↓
LLM 调用 sendEmail("user@example.com", "天气提醒", "今天重庆下雨，记得带伞")
  ↓
观察结果："邮件已发送"
  ↓
LLM 回复用户："今天重庆有雨，我已经发邮件提醒你带伞了。"
```

---

## Function Calling（工具调用）

### 原理

Function Calling 是 OpenAI 在 2023 年提出的协议，现在所有主流大模型都支持。

```
传统方式：
  用户消息 → LLM → 文本回复（只能动嘴）

Function Calling：
  用户消息 → LLM → "我需要调用 getWeather 函数，参数是 {city: '重庆'}"
                    ↓
                 系统执行函数，拿到结果
                    ↓
                 结果返回给 LLM
                    ↓
                 LLM → "今天重庆天气晴朗，28°C"
```

**关键点：LLM 不直接执行函数，而是输出"我要调用什么函数、传什么参数"，由系统执行后把结果喂回去。**

### 支持 Function Calling 的模型

| 模型 | 支持情况 |
|------|---------|
| GPT-4o / GPT-4 | 完美支持 |
| DeepSeek V3 | 支持 |
| Claude 3.5 | 支持 |
| Qwen 2.5 | 支持（需 7B+） |
| Ollama 本地模型 | 部分支持（qwen2.5:7b+ 支持） |

### Spring AI 的 Function Calling

Spring AI 1.0 GA 原生支持 Function Calling，有两种方式定义工具：

**方式一：`@Tool` 注解（推荐）**

```java
public class WeatherTools {

    @Tool(description = "查询指定城市的当前天气信息，返回城市名、天气状况和温度")
    public WeatherInfo getWeather(
        @ToolParam(description = "城市名称，如：北京、上海、重庆") String city
    ) {
        // 调用天气 API
        return weatherApi.query(city);
    }
}
```

**方式二：`FunctionCallback` 手动注册**

```java
@Bean
public FunctionCallback weatherFunction() {
    return FunctionCallback.builder()
        .function("getWeather", (String city) -> weatherApi.query(city))
        .description("查询指定城市的当前天气信息")
        .inputType(String.class)
        .build();
}
```

---

## ReAct 循环

Agent 的核心执行模式是 **ReAct（Reasoning + Acting）**：

```
┌──────────────────────────────────────────────────────┐
│                    ReAct 循环                         │
│                                                      │
│  用户提问                                            │
│      ↓                                               │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐       │
│  │ Reason  │ ──→ │  Act    │ ──→ │ Observe │       │
│  │ (推理)   │     │ (行动)   │     │ (观察)   │       │
│  └─────────┘     └─────────┘     └─────────┘       │
│      ↑                                   │          │
│      └───────────────────────────────────┘          │
│                  循环直到任务完成                      │
│                      ↓                              │
│                 返回最终答案                          │
└──────────────────────────────────────────────────────┘
```

**详细流程：**

```
用户："帮我查重庆天气，如果下雨就发邮件提醒我"

Round 1:
  Reason: 用户想查天气，我需要调用天气工具
  Act:    call getWeather("重庆")
  Observe: { "weather": "雨", "temp": "22°C" }

Round 2:
  Reason: 天气是雨，用户说下雨就发邮件，我需要调用邮件工具
  Act:    call sendEmail("user@example.com", "带伞提醒", "今天重庆下雨")
  Observe: "邮件已发送"

Round 3:
  Reason: 任务完成，返回最终答案
  Output: "今天重庆有雨（22°C），我已经发邮件提醒你带伞了。"
```

**终止条件：**
1. LLM 判断任务完成，直接输出文本回复（不再调用工具）
2. 达到最大循环次数（防止死循环）
3. 工具调用失败，LLM 需要处理错误

---

## Spring AI 实现 Agent

### 核心配置

```java
@Configuration
public class AgentConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultSystem("""
                你是一个智能助手，拥有以下工具能力。
                根据用户需求，判断是否需要调用工具。
                如果需要，调用合适的工具完成任务。
                如果不需要工具，直接回答问题。
                """)
            .defaultTools(new WeatherTools(), new EmailTools(), new DatabaseTools())
            .build();
    }
}
```

### 定义工具集

```java
public class WeatherTools {

    @Tool(description = "查询指定城市的当前天气信息")
    public WeatherInfo getWeather(
        @ToolParam(description = "城市名称") String city
    ) {
        // 实际调用天气 API
        return restTemplate.getForObject(
            "https://api.weather.com/" + city,
            WeatherInfo.class
        );
    }
}

public class EmailTools {

    @Tool(description = "发送邮件到指定邮箱地址")
    public String sendEmail(
        @ToolParam(description = "收件人邮箱") String to,
        @ToolParam(description = "邮件主题") String subject,
        @ToolParam(description = "邮件正文") String body
    ) {
        emailService.send(to, subject, body);
        return "邮件发送成功";
    }
}

public class DatabaseTools {

    @Tool(description = "查询用户的订单列表")
    public List<Order> queryOrders(
        @ToolParam(description = "用户ID") Long userId,
        @ToolParam(description = "查询条数，默认10") @Nullable Integer limit
    ) {
        return orderMapper.selectByUserId(userId, limit != null ? limit : 10);
    }
}
```

### Agent 执行

```java
@Service
public class AgentService {

    @Autowired
    private ChatClient chatClient;

    /**
     * Agent 处理用户请求
     * Spring AI 自动处理 ReAct 循环：
     * 1. 把用户消息 + 工具定义发给 LLM
     * 2. LLM 返回要调用的工具和参数
     * 3. Spring AI 执行工具，把结果返回给 LLM
     * 4. 重复直到 LLM 不再调用工具，输出最终回复
     */
    public String chat(String userMessage) {
        return chatClient.prompt()
            .user(userMessage)
            .call()
            .content();
    }

    /**
     * 流式 Agent（SSE）
     */
    public Flux<String> streamChat(String userMessage) {
        return chatClient.prompt()
            .user(userMessage)
            .stream()
            .content();
    }
}
```

**就这么简单！** Spring AI 内部自动完成了整个 ReAct 循环。

---

## 实战：搭建你的第一个 Agent

### 场景：智能客服 Agent

让你的客服机器人能够：
1. 查询用户的订单状态
2. 查询商品信息
3. 帮用户退款
4. 回答常见问题（RAG）

### 第 1 步：定义工具

```java
@Component
public class CustomerServiceTools {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RefundService refundService;

    @Tool(description = "根据订单号查询订单状态，返回订单号、状态、金额、下单时间")
    public OrderInfo queryOrder(
        @ToolParam(description = "订单号，如：ORD20240101001") String orderId
    ) {
        Order order = orderMapper.selectByOrderId(orderId);
        if (order == null) {
            return new OrderInfo(null, "未找到该订单", null, null);
        }
        return new OrderInfo(
            order.getOrderId(),
            order.getStatus(),
            order.getAmount(),
            order.getCreatedTime()
        );
    }

    @Tool(description = "查询用户的最近订单列表，返回最近N条订单")
    public List<OrderInfo> queryUserOrders(
        @ToolParam(description = "用户ID") Long userId,
        @ToolParam(description = "查询条数") @Nullable Integer limit
    ) {
        return orderMapper.selectRecentByUserId(userId, limit != null ? limit : 5);
    }

    @Tool(description = "根据商品名称模糊搜索商品，返回商品ID、名称、价格、库存")
    public List<ProductInfo> searchProduct(
        @ToolParam(description = "商品名称关键词") String keyword
    ) {
        return productMapper.selectByKeyword(keyword);
    }

    @Tool(description = "发起退款申请，需要订单号和退款原因")
    public String applyRefund(
        @ToolParam(description = "订单号") String orderId,
        @ToolParam(description = "退款原因") String reason
    ) {
        boolean success = refundService.apply(orderId, reason);
        return success ? "退款申请已提交，预计3-5个工作日到账" : "退款申请失败，订单可能已超过退款期限";
    }
}
```

### 第 2 步：配置 Agent

```java
@Configuration
public class AgentConfig {

    @Bean
    public ChatClient agentChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultSystem("""
                你是 AI Studio 的智能客服助手。你拥有以下能力：
                1. 查询订单状态和历史
                2. 搜索商品信息
                3. 帮助用户申请退款

                工作原则：
                - 优先使用工具获取真实数据，不要编造信息
                - 查询订单时需要订单号，如果没有请向用户询问
                - 退款前需要确认用户意图，避免误操作
                - 回复要简洁友好
                """)
            .defaultTools(new CustomerServiceTools())
            .build();
    }
}
```

### 第 3 步：接入现有项目

```java
// 在 ChatbotController 中新增 Agent 接口
@PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter agentStream(@RequestBody ChatRequest request,
                               HttpServletRequest httpServletRequest) {
    String userId = resolveUserId(httpServletRequest);
    SseEmitter emitter = new SseEmitter(180_000L);

    // Agent 流式对话
    agentService.streamChat(request.getMessage(), userId)
        .subscribe(
            chunk -> {
                try { emitter.send(Map.of("content", chunk)); }
                catch (Exception e) { log.warn("SSE 发送失败: {}", e.getMessage()); }
            },
            err -> {
                sendStreamError(emitter, err.getMessage());
            },
            () -> {
                emitter.complete();
            }
        );

    return emitter;
}
```

### 第 4 步：前端切换

```html
<select class="form-select custom-select w-auto" id="modelSelect">
    <option value="deepseek">DeepSeek v3</option>
    <option value="ollama">Ollama Local</option>
    <option value="agent">Agent 模式</option>  <!-- 新增 -->
</select>
```

---

## 常用工具设计

### 1. 数据库查询工具

```java
@Tool(description = "执行SQL查询，返回结果集。只允许SELECT语句")
public List<Map<String, Object>> queryDatabase(
    @ToolParam(description = "SQL查询语句，只能是SELECT") String sql
) {
    // 安全检查：只允许 SELECT
    if (!sql.trim().toUpperCase().startsWith("SELECT")) {
        throw new IllegalArgumentException("只允许SELECT查询");
    }
    return jdbcTemplate.queryForList(sql);
}
```

### 2. 文件操作工具

```java
@Tool(description = "读取指定路径的文件内容")
public String readFile(
    @ToolParam(description = "文件路径") String path
) throws IOException {
    return Files.readString(Path.of(path));
}

@Tool(description = "将内容写入指定文件")
public String writeFile(
    @ToolParam(description = "文件路径") String path,
    @ToolParam(description = "文件内容") String content
) throws IOException {
    Files.writeString(Path.of(path), content);
    return "文件已写入: " + path;
}
```

### 3. HTTP 请求工具

```java
@Tool(description = "发送HTTP GET请求到指定URL，返回响应内容")
public String httpGet(
    @ToolParam(description = "请求URL") String url
) {
    return restTemplate.getForObject(url, String.class);
}

@Tool(description = "发送HTTP POST请求，用于调用外部API")
public String httpPost(
    @ToolParam(description = "请求URL") String url,
    @ToolParam(description = "请求体JSON") String body
) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> entity = new HttpEntity<>(body, headers);
    return restTemplate.postForObject(url, entity, String.class);
}
```

### 4. 计算工具

```java
@Tool(description = "执行数学计算，支持加减乘除、幂运算、三角函数")
public double calculate(
    @ToolParam(description = "数学表达式，如：(3 + 5) * 2") String expression
) {
    // 使用 ExpressionParser 解析数学表达式
    ExpressionParser parser = new SpelExpressionParser();
    return parser.parseExpression(expression).getValue(Double.class);
}
```

### 5. 代码执行工具（沙箱）

```java
@Tool(description = "在安全沙箱中执行Python代码，返回输出结果")
public String executeCode(
    @ToolParam(description = "Python代码") String code
) {
    // 在 Docker 容器中执行，限制资源和时间
    ProcessBuilder pb = new ProcessBuilder(
        "docker", "run", "--rm", "--network=none",
        "--memory=256m", "--cpus=0.5",
        "python:3.11-slim",
        "python", "-c", code
    );
    // ... 执行并返回输出
}
```

---

## 进阶：多工具协作

### 复合任务示例

```
用户："帮我分析上个月的销售数据，生成报告并发邮件给老板"

Agent 推理过程：

Round 1:
  Reason: 需要先查询上个月的销售数据
  Act:    call queryDatabase("SELECT product, SUM(amount) FROM orders
           WHERE created_time >= '2024-04-01' AND created_time < '2024-05-01'
           GROUP BY product ORDER BY SUM(amount) DESC")
  Observe: [{product: "手机", total: 50000}, {product: "电脑", total: 30000}, ...]

Round 2:
  Reason: 数据拿到了，需要生成分析报告
  Act:    call writeFile("/tmp/report.md", "## 上月销售报告\n\n1. 手机：¥50,000\n...")
  Observe: "文件已写入"

Round 3:
  Reason: 报告生成了，需要发邮件给老板
  Act:    call sendEmail("boss@company.com", "上月销售报告", reportContent)
  Observe: "邮件发送成功"

Round 4:
  Reason: 所有任务完成
  Output: "已为您完成以下操作：
           1. 查询了上月销售数据（手机¥50,000 位居第一）
           2. 生成了销售报告
           3. 已发送邮件给 boss@company.com"
```

### 工具编排模式

```java
@Tool(description = "执行复杂的多步骤任务，自动规划和调用多个工具完成")
public String executeComplexTask(
    @ToolParam(description = "任务描述") String taskDescription
) {
    // 这个工具本身就是一次 Agent 调用
    // LLM 会自动规划需要调用哪些子工具
    return chatClient.prompt()
        .user(taskDescription)
        .call()
        .content();
}
```

---

## 与当前项目集成

### 最小改动方案

你现在的项目已经有 `ChatbotController` 和 `ChatbotService`，只需要：

**1. 新增 Agent 配置类**

```java
// AgentConfig.java
@Configuration
public class AgentConfig {

    @Bean
    public ChatClient agentChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultSystem("你是智能助手，拥有工具能力...")
            .defaultTools(new AgentTools())
            .build();
    }
}
```

**2. 新增 Agent Service**

```java
// AgentService.java
@Service
public class AgentService {

    @Autowired
    private ChatClient agentChatClient;

    public Flux<String> streamChat(String message) {
        return agentChatClient.prompt()
            .user(message)
            .stream()
            .content();
    }
}
```

**3. 新增 Controller 接口**

```java
// ChatbotController.java 中新增
@PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter agentStream(@RequestBody ChatRequest request, ...) {
    // 类似现有 streamChat，调用 agentService
}
```

**4. 前端加一个选项**

```html
<option value="agent">Agent 模式</option>
```

### 注意事项

1. **模型选择**：Function Calling 需要模型支持，DeepSeek V3 支持，Ollama 需要 qwen2.5:7b+
2. **循环次数限制**：设置最大 ReAct 轮数（如 10 轮），防止死循环
3. **工具权限**：敏感操作（删除、退款）需要二次确认
4. **超时控制**：工具调用可能很慢，设置合理的超时时间
5. **错误处理**：工具执行失败时，LLM 应该能处理错误并给出合理回复

---

## 总结

```
聊天机器人 → Agent 的升级路径：

1. 定义工具（@Tool 注解）
   ↓
2. 配置 ChatClient（注册工具 + System Prompt）
   ↓
3. 调用 chatClient.prompt().user(msg).call()
   ↓
4. Spring AI 自动完成 ReAct 循环
   ↓
5. 用户得到：文本回复 + 实际行动结果
```

**核心就一件事：给 LLM 注册工具函数，剩下的 ReAct 循环 Spring AI 帮你搞定。**
