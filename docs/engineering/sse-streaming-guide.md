# SSE 流式响应工程说明

本文用于说明 AI Studio 当前流式输出链路中使用的 SSE（Server-Sent Events）机制，包括协议原理、Spring MVC 后端 `SseEmitter` 的用法、前端如何接收和渲染，以及当前项目应优先优化的点。

## 1. SSE 是什么

SSE，全称 Server-Sent Events，是一种基于 HTTP 的服务端单向推送机制。

它的典型用途是：

- AI 回复逐字输出；
- 任务进度推送；
- 审查状态更新；
- 日志流；
- 通知流；
- 轻量实时数据看板。

SSE 的核心特点：

- 客户端发起一个普通 HTTP 请求；
- 服务端不立刻结束响应，而是持续写入事件；
- 响应类型通常是 `text/event-stream`；
- 浏览器边收到边处理；
- 方向是服务端到客户端单向推送；
- 底层仍然是 HTTP，不需要 WebSocket 握手。

对于大模型流式输出，SSE 很合适，因为模型本身也是按 token/chunk 逐段返回，后端只需要把 chunk 继续推给浏览器。

## 2. SSE 和 WebFlux 的关系

SSE 是通信格式，WebFlux 是 Spring 的响应式编程模型。

这两个概念不要混在一起：

- `SseEmitter`：Spring MVC 里发送 SSE 的工具。
- `Flux<ServerSentEvent<?>>`：Spring WebFlux 里发送 SSE 的常见方式。
- `text/event-stream`：两者最终返回给浏览器的响应格式。

也就是说，换成 WebFlux 后，大概率仍然是在用 SSE，只是后端代码从 `SseEmitter` 改成了 `Flux` 返回。

当前项目的 `chatbot-service` 是 Spring MVC：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

所以当前使用 `SseEmitter` 是合理的。

## 3. SSE 协议格式

SSE 返回内容是纯文本流，基本格式如下：

```text
event: status
data: {"status":"processing","message":"AI is thinking..."}

event: message
data: {"content":"你好"}

event: message
data: {"content":"，我是"}

event: message
data: {"content":"AI。"}

event: done
data: {"success":true}

```

注意：

- 每条事件由一个或多个字段组成；
- 常见字段有 `event:`、`data:`、`id:`、`retry:`；
- 每条事件之间用空行分隔；
- `data:` 后面通常放 JSON 字符串；
- 如果不写 `event:`，浏览器默认认为事件名是 `message`。

常见字段：

```text
event: custom-event-name
id: 10001
retry: 3000
data: {"content":"hello"}

```

含义：

- `event`：事件名称，前端可以按事件名区分处理逻辑；
- `id`：事件 ID，可用于断线重连续传；
- `retry`：浏览器原生 `EventSource` 的重连间隔；
- `data`：真正的业务数据。

## 4. SseEmitter 是什么

`SseEmitter` 是 Spring MVC 提供的异步响应对象，包路径是：

```java
org.springframework.web.servlet.mvc.method.annotation.SseEmitter
```

它的作用是：

1. Controller 立即返回一个 `SseEmitter`；
2. Servlet 请求进入异步模式，不马上关闭响应；
3. 后端可以在其他线程或异步回调里不断调用 `emitter.send(...)`；
4. 最后调用 `emitter.complete()` 结束响应。

最小示例：

```java
@GetMapping(value = "/demo/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(180_000L);

    executor.submit(() -> {
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(Map.of("content", "hello")));

            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(Map.of("content", " world")));

            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    return emitter;
}
```

## 5. SseEmitter 常用方法

### 5.1 构造方法

```java
new SseEmitter();
new SseEmitter(180_000L);
```

说明：

- 无参构造使用默认超时；
- 带参数构造可以设置超时时间，单位是毫秒；
- AI 流式输出一般建议配置为 3 到 10 分钟；
- 项目里目前使用的是 `180_000L`，也就是 3 分钟。

### 5.2 send

发送普通数据：

```java
emitter.send(Map.of("content", chunk));
```

Spring 会把对象序列化成 JSON，并按 SSE 格式写出去。

发送带事件名的数据：

```java
emitter.send(SseEmitter.event()
        .name("status")
        .data(Map.of("status", "processing")));
```

推荐 AI 场景使用带事件名的写法，因为前端可以清晰区分：

- `message`：正文 token；
- `status`：排队/处理中；
- `references`：RAG 引用；
- `agent_status`：Agent 生命周期；
- `tool_event`：工具调用过程；
- `error`：错误。

### 5.3 complete

正常结束：

```java
emitter.complete();
```

调用后 HTTP 响应关闭，前端 `reader.read()` 会读到 `done = true`。

### 5.4 completeWithError

异常结束：

```java
emitter.completeWithError(e);
```

适合无法继续输出时使用。

不过在 AI 产品里，通常更推荐先发送一个业务错误事件，再 `complete()`：

```java
emitter.send(SseEmitter.event()
        .name("error")
        .data(Map.of("error", "AI 服务超时，请重试")));
emitter.complete();
```

这样前端可以用统一的业务逻辑渲染错误，而不是只拿到网络异常。

### 5.5 onCompletion

客户端连接正常关闭或服务端 `complete()` 后触发：

```java
emitter.onCompletion(() -> {
    log.info("SSE completed");
});
```

常用于：

- 清理连接；
- 释放资源；
- 取消上游模型流；
- 释放信号量。

### 5.6 onTimeout

SSE 超时触发：

```java
emitter.onTimeout(() -> {
    log.warn("SSE timeout");
    emitter.complete();
});
```

AI 场景里不能只关闭 emitter，还应该取消上游模型流，否则模型可能继续跑。

### 5.7 onError

连接异常或发送失败时触发：

```java
emitter.onError(error -> {
    log.warn("SSE error: {}", error.getMessage());
});
```

常见原因：

- 用户关闭页面；
- 用户点击停止；
- 浏览器网络断开；
- Gateway/代理断开连接；
- 服务端写入响应失败。

## 6. 当前项目的后端流式链路

当前普通聊天入口：

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestBody ChatRequest request, HttpServletRequest httpServletRequest) {
    String userId = resolveUserId(httpServletRequest);
    ...
    SseEmitter emitter = new SseEmitter(180_000L);
    chatbotService.streamChat(request, emitter, userId);
    return emitter;
}
```

当前 Agent 聊天入口：

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamAgent(@RequestBody ChatRequest request, HttpServletRequest httpServletRequest) {
    String userId = resolveUserId(httpServletRequest);
    ...
    SseEmitter emitter = new SseEmitter(180_000L);
    agentService.streamAgent(request, emitter, userId);
    return emitter;
}
```

Service 层调用 Spring AI 的流式接口：

```java
model.stream(new Prompt(conversationContext.messages()))
        .map(res -> res.getResult().getOutput().getText())
        .subscribe(
                chunk -> {
                    fullRes.append(chunk);
                    emitter.send(Map.of("content", chunk));
                },
                err -> {
                    sendStreamError(emitter, resolveErrorMessage(err));
                },
                () -> {
                    asyncSaveChatRecord(...);
                    emitter.complete();
                }
        );
```

这个链路的本质是：

```text
浏览器 fetch
  -> Gateway :9000
    -> chatbot-service /api/chat/stream
      -> Spring MVC SseEmitter
        -> Spring AI model.stream(...)
          -> chunk
        <- emitter.send(chunk)
  <- text/event-stream
<- 前端 ReadableStream 渲染
```

## 7. 后端一般怎么处理 SSE

一个成熟的后端 SSE 处理逻辑通常包括：

1. 认证和权限校验；
2. 创建 `SseEmitter`；
3. 绑定生命周期回调；
4. 启动上游流式任务；
5. 每个 chunk 发送给前端；
6. 发生错误时发送业务错误事件；
7. 正常结束时保存完整响应；
8. 用户断开或超时时取消上游任务；
9. 释放信号量、线程、上下文等资源。

推荐结构：

```java
SseEmitter emitter = new SseEmitter(timeoutMs);
AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
AtomicBoolean finished = new AtomicBoolean(false);

Runnable cleanup = () -> {
    Disposable subscription = subscriptionRef.get();
    if (subscription != null && !subscription.isDisposed()) {
        subscription.dispose();
    }
    releaseResources();
};

emitter.onCompletion(cleanup);
emitter.onTimeout(() -> {
    cleanup.run();
    emitter.complete();
});
emitter.onError(error -> cleanup.run());

Disposable subscription = model.stream(prompt)
        .map(...)
        .subscribe(
                chunk -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(Map.of("content", chunk)));
                    } catch (Exception sendError) {
                        cleanup.run();
                    }
                },
                error -> {
                    sendStreamError(emitter, resolveErrorMessage(error));
                    cleanup.run();
                },
                () -> {
                    saveRecord();
                    finished.set(true);
                    emitter.complete();
                    cleanup.run();
                }
        );

subscriptionRef.set(subscription);
```

重点是：不要只管 `emitter.send`，还要管上游模型流的取消。

## 8. 当前项目需要注意的问题

当前项目已经能正常流式输出，但生命周期还有优化空间。

### 8.1 用户停止输出后，上游模型流可能还在跑

前端现在会调用：

```javascript
currentAbortController.abort();
```

这会中断浏览器请求。但后端当前没有保存 Reactor `Disposable`，也没有在 `SseEmitter` 关闭时取消 `model.stream(...)` 的订阅。

结果可能是：

- 前端已经停止；
- 后端还在接收模型 chunk；
- `emitter.send(...)` 失败并记录 warn；
- 模型资源仍然消耗；
- Ollama 单并发信号量可能释放不及时。

这是当前比“要不要换 WebFlux”更优先的问题。

### 8.2 超时时间不统一

Controller 中：

```java
new SseEmitter(180_000L)
```

配置中：

```yaml
spring:
  mvc:
    async:
      request-timeout: 600000
```

一个是 3 分钟，一个是 10 分钟。建议统一为配置项，例如：

```yaml
app:
  stream:
    sse-timeout-ms: 600000
```

然后 Controller 或工厂类统一读取。

### 8.3 send 失败时应终止上游流

当前代码里 `emitter.send` 失败后只是 warn：

```java
catch (Exception e) {
    log.warn("SSE chunk send failed: {}", e.getMessage());
}
```

更稳妥的处理是：

- 标记连接已关闭；
- dispose 上游 subscription；
- 释放资源；
- 不再继续消费 chunk。

## 9. 前端怎么接收 SSE

前端有两种常见方式：

1. `EventSource`
2. `fetch + ReadableStream`

### 9.1 EventSource

原生 `EventSource` 用法简单：

```javascript
const source = new EventSource('/api/chat/stream');

source.onmessage = function (event) {
  const data = JSON.parse(event.data);
  console.log(data);
};

source.addEventListener('status', function (event) {
  const data = JSON.parse(event.data);
  console.log('status', data);
});

source.onerror = function () {
  source.close();
};
```

但 `EventSource` 有几个限制：

- 默认只能 GET；
- 自定义 Header 不方便；
- 带 JWT Authorization header 比较麻烦；
- POST body 不适合；
- 对当前项目这种 `POST JSON + JWT + AbortController` 场景不够灵活。

所以当前项目使用 `fetch + ReadableStream` 是合理的。

### 9.2 fetch + ReadableStream

当前项目的前端方式大致是：

```javascript
const response = await fetch('/api/chat/stream', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream'
  },
  signal: currentAbortController.signal,
  body: JSON.stringify({
    message,
    sessionId,
    model,
    useRag,
    ragTopK
  })
});

const reader = response.body.getReader();
const decoder = new TextDecoder();
let buffer = '';
let currentEventName = 'message';

while (true) {
  const { value, done } = await reader.read();
  if (done) break;

  buffer += decoder.decode(value, { stream: true });
  const lines = buffer.split('\n');
  buffer = lines.pop();

  for (const line of lines) {
    const trimmed = line.trim();

    if (trimmed.startsWith('event:')) {
      currentEventName = trimmed.substring(6).trim() || 'message';
      continue;
    }

    if (!trimmed.startsWith('data:')) {
      continue;
    }

    const data = trimmed.substring(5).trim();
    const json = JSON.parse(data);

    if (currentEventName === 'status') {
      renderStatus(json);
    } else if (currentEventName === 'references') {
      renderReferences(json.references);
    } else if (currentEventName === 'error') {
      renderError(json.error);
    } else {
      appendAssistantText(json.content);
    }

    currentEventName = 'message';
  }
}
```

这种方式的优点：

- 支持 POST；
- 支持 JSON body；
- 支持 Authorization header；
- 支持 `AbortController` 停止输出；
- 可以和现有 `authFetch` 刷新 token 逻辑集成。

## 10. 前端渲染策略

AI 流式渲染不建议每个 token 都完整重建复杂 DOM。

基础策略：

```javascript
let fullText = '';

function appendAssistantText(chunk) {
  fullText += chunk;
  bubble.innerHTML = sanitizeHtml(marked.parse(fullText));
}
```

这个简单，但如果 chunk 很多，Markdown 解析和 DOM 更新会比较频繁。

更稳的策略：

- chunk 到达时先追加到字符串；
- 使用 `requestAnimationFrame` 或短节流批量渲染；
- 状态事件和正文事件分开处理；
- 错误事件覆盖 loading 状态；
- 流结束后再做一次完整 Markdown 高亮。

示例：

```javascript
let fullText = '';
let pendingRender = false;

function appendChunk(chunk) {
  fullText += chunk;

  if (pendingRender) {
    return;
  }

  pendingRender = true;
  requestAnimationFrame(() => {
    bubble.innerHTML = sanitizeHtml(marked.parse(fullText));
    pendingRender = false;
  });
}
```

## 11. 错误处理

建议后端统一发送错误事件：

```java
emitter.send(SseEmitter.event()
        .name("error")
        .data(Map.of("error", "AI 服务超时，请重试")));
emitter.complete();
```

前端统一处理：

```javascript
if (currentEventName === 'error' || json.error) {
  targetBubble.innerHTML = `<span class="text-danger">${escapeHtml(json.error)}</span>`;
  continue;
}
```

常见错误分类：

- 鉴权失败；
- 模型 API Key 缺失；
- 上游模型超时；
- 模型限流；
- 用户主动停止；
- 网络中断；
- Gateway 超时；
- 服务端异常。

## 12. Gateway 和代理注意事项

SSE 经过 Gateway 或 Nginx 时，要注意：

- 不要压缩或缓冲事件流；
- 不要设置过短的响应超时；
- 保持 `Content-Type: text/event-stream`；
- 长连接要允许持续响应；
- Gateway 不要暴露内部 `8080/8081`，生产入口仍然只走 `9000`。

当前项目生产路径是：

```text
browser
  -> http://111.229.127.171:9000
  -> gateway
  -> chatbot-service
```

这个架构可以继续保留。

## 13. SSE、WebSocket、WebFlux 怎么选

### 13.1 继续用 SSE

适合：

- AI 文本流式输出；
- 服务端单向推送；
- 任务状态更新；
- 简单可靠；
- 前端不需要双向实时通信。

当前项目属于这个场景。

### 13.2 用 WebSocket

适合：

- 双向实时通信；
- 多人协作；
- 实时编辑；
- 游戏/IM；
- 客户端需要频繁主动发送事件。

当前 AI 回复流不需要 WebSocket。

### 13.3 用 WebFlux

适合：

- 高并发长连接；
- 服务内部链路大部分是非阻塞；
- 希望基于 `Flux` 统一管理 backpressure 和 cancellation；
- 愿意承担 MVC/WebFlux 迁移成本。

当前项目可以后续局部引入，但不建议现在为了“更常用”而整体迁移。

## 14. 当前项目推荐改造路线

### 阶段 1：保留 SseEmitter，补齐生命周期

优先级最高。

目标：

- 用户停止输出后，后端立即取消模型流；
- 连接断开后不继续消耗模型资源；
- 超时后释放信号量；
- 错误事件统一。

建议改造点：

- 保存 Reactor `Disposable`；
- 增加 `emitter.onCompletion`；
- 增加 `emitter.onTimeout`；
- 增加 `emitter.onError`；
- `emitter.send` 失败后 dispose；
- timeout 配置化。

### 阶段 2：抽取统一 Stream 辅助类

当前 `ChatbotService`、`AgentService` 中有重复流式发送逻辑。

可以抽取：

```text
SseStreamSession
SseEventSender
AiStreamLifecycleManager
```

职责：

- 统一发送 `message/status/error`；
- 统一关闭；
- 统一绑定上游 `Disposable`；
- 统一记录是否已经结束；
- 统一资源释放。

### 阶段 3：再评估 WebFlux

当并发量上来后，再考虑新增 reactive endpoint：

```java
@PostMapping(value = "/stream/reactive", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Map<String, Object>>> streamReactive(@RequestBody ChatRequest request) {
    return model.stream(prompt)
            .map(...)
            .map(chunk -> ServerSentEvent.builder(Map.of("content", chunk))
                    .event("message")
                    .build());
}
```

但如果内部 DB、Redis、文件读取、RAG 仍然是 blocking，这一步收益有限，需要谨慎。

## 15. 推荐事件规范

建议项目统一 SSE event 规范：

| event | 用途 | data 示例 |
| --- | --- | --- |
| `message` | AI 正文增量 | `{"content":"hello"}` |
| `status` | 普通聊天状态 | `{"status":"queued","message":"排队中"}` |
| `references` | RAG 引用 | `{"references":[...]}` |
| `agent_status` | Agent 状态 | `{"status":"started"}` |
| `tool_event` | Agent 工具事件 | `{"tool":"readFile","status":"completed"}` |
| `pending_action` | 待确认操作 | `{"actionId":123,"type":"APPLY_PATCH"}` |
| `reviewSummary` | 代码审查摘要 | `{"runId":1,"issues":[...]}` |
| `error` | 业务错误 | `{"error":"AI 服务超时，请重试"}` |
| `done` | 显式完成 | `{"success":true}` |

是否发送 `done` 可选。即使不发送，前端也能通过 `reader.read()` 的 `done = true` 判断流结束。

## 16. 总结

当前项目使用 SSE 是正确方向。

短期不要为了追求 WebFlux 而重构整条链路。更务实的做法是：

1. 保留 Spring MVC + `SseEmitter`；
2. 补齐连接生命周期；
3. 正确取消上游模型流；
4. 统一事件格式；
5. 优化前端渲染节流；
6. 等并发和链路形态真正需要时，再局部引入 WebFlux。

一句话：

> SSE 负责“怎么把流推给浏览器”，`SseEmitter` 是 Spring MVC 对 SSE 的实现工具；当前项目的问题不在 SSE，而在后端对连接关闭、超时、取消订阅的处理还不够完整。
