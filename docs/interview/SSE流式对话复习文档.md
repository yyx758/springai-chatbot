# SSE 流式对话复习文档

本文用于复习本项目的 AI 流式对话实现，重点讲清楚 SSE 是什么、后端 `SseEmitter` 怎么工作、Spring AI chunk 怎么推送给前端、前端如何持续接收并动态渲染。

## 1. SSE 是什么

SSE 全称是 Server-Sent Events。

它是一种基于 HTTP 的服务端单向推送机制：

```text
客户端发起一个 HTTP 请求
服务端不马上结束响应
服务端持续往响应流里写事件
客户端边接收边处理
```

适合场景：

```text
AI 流式输出
消息通知
任务进度
日志推送
状态更新
```

SSE 是单向的：

```text
Client -> Server: 发起请求
Server -> Client: 持续推送事件
```

如果需要持续双向通信，比如多人协作、实时游戏、在线编辑，WebSocket 更适合。

## 2. SSE 和普通 HTTP 的区别

普通 HTTP：

```text
请求 -> 后端处理完成 -> 一次性返回完整响应 -> 连接结束
```

SSE：

```text
请求 -> 后端建立响应流 -> 后端多次写入事件 -> 前端多次接收 -> 后端 complete 后连接结束
```

AI 对话为什么适合 SSE：

```text
模型不是一次性返回完整答案，而是不断生成 chunk。
每生成一段，后端就推一段，前端就渲染一段。
用户看到的是边生成边显示。
```

## 3. SSE 协议格式

SSE 响应的 Content-Type 是：

```http
Content-Type: text/event-stream
```

一条普通事件大致长这样：

```text
data: {"content":"你好"}

```

带事件名：

```text
event: references
data: {"references":[{"title":"Redis","snippet":"..."}]}

```

格式要点：

```text
event: 事件名，可选，不写时默认 message
data: 事件数据，可以是字符串，也可以是 JSON 字符串
空行: 表示一条事件结束
```

服务端可以连续发送：

```text
data: {"content":"你"}

data: {"content":"好"}

data: {"content":"，我是 AI"}

```

前端收到后不断追加渲染。

## 4. 本项目为什么用 SSE

本项目聊天流式输出是典型的：

```text
用户发一条消息
模型持续生成回答
服务端持续推 chunk
前端持续渲染
```

所以使用：

```text
Spring MVC + SseEmitter
```

没有直接使用 WebSocket，是因为：

```text
1. 当前主要是服务端向客户端单向推送。
2. SSE 基于 HTTP，部署简单。
3. Spring MVC 中 SseEmitter 接入成本低。
4. 不需要维护复杂的双向连接状态。
```

没有直接全量使用 WebFlux，是因为：

```text
1. 项目主体是 Spring MVC。
2. MyBatis、RedisTemplate、RestTemplate、文件操作等链路多为阻塞式。
3. 如果只把 Controller 改成 WebFlux，内部阻塞调用仍然存在，收益有限。
4. 当前目标是稳定接入流式体验，而不是重构成全链路 reactive。
```

## 5. SseEmitter 是什么

`SseEmitter` 是 Spring MVC 提供的一个异步响应对象。

它的作用是：

```text
让 Controller 先返回一个不断开的响应连接，
后续业务线程可以不断调用 emitter.send(...) 往客户端写数据。
```

可以把它理解成：

```text
后端握着一根通向浏览器的输出管道。
模型每生成一段，就往管道里写一段。
```

## 6. SseEmitter 常用方法

### 6.1 构造方法

```java
SseEmitter emitter = new SseEmitter(180_000L);
```

含义：

```text
创建一个 SSE emitter。
180_000L 表示超时时间 180 秒。
```

如果不传超时时间，使用 Spring MVC 默认超时配置。

### 6.2 send(Object data)

```java
emitter.send(Map.of("content", chunk));
```

发送默认 message 事件。

前端收到的大致是：

```text
data: {"content":"模型输出片段"}

```

### 6.3 send(SseEmitter.event())

```java
emitter.send(SseEmitter.event()
        .name("references")
        .data(Map.of("references", references)));
```

发送带事件名的 SSE。

前端收到大致是：

```text
event: references
data: {"references":[...]}

```

常用 builder 方法：

```java
SseEmitter.event()
    .name("eventName")   // 设置事件名
    .id("eventId")       // 设置事件 ID，可选
    .data(data)          // 设置事件数据
    .comment("comment")  // 注释/心跳，可选
    .reconnectTime(3000) // 建议客户端重连时间，可选
```

### 6.4 complete()

```java
emitter.complete();
```

表示正常结束 SSE 连接。

AI 模型输出完成后要调用。

### 6.5 completeWithError(Throwable)

```java
emitter.completeWithError(e);
```

表示异常结束连接。

实际项目中也可以先发一个 error 事件，再 `complete()`：

```java
emitter.send(SseEmitter.event()
        .name("error")
        .data(Map.of("error", e.getMessage())));
emitter.complete();
```

### 6.6 onTimeout / onCompletion / onError

常见用法：

```java
emitter.onTimeout(() -> {
    // 超时清理资源
});

emitter.onCompletion(() -> {
    // 连接完成后清理资源
});

emitter.onError(error -> {
    // 连接异常后记录日志
});
```

本项目当前主要靠 `subscribe` 的 error/complete 回调做处理，后续可以补这些回调做资源清理和审计。

## 7. 后端 Controller 怎么写

本项目聊天接口类似：

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestBody ChatRequest request,
                             HttpServletRequest httpServletRequest) {
    String userId = resolveUserId(httpServletRequest);

    if (request.getSessionId() == null || request.getSessionId().isBlank()) {
        request.setSessionId(buildSessionId(userId));
    }

    SseEmitter emitter = new SseEmitter(180_000L);

    try {
        chatbotService.streamChat(request, emitter, userId);
    } catch (Exception e) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("error", "流式对话初始化失败: " + e.getMessage())));
        } catch (Exception ignored) {
        }
        emitter.complete();
    }

    return emitter;
}
```

关键点：

```text
1. produces 必须是 text/event-stream。
2. Controller 返回 SseEmitter。
3. Controller 创建 emitter 后交给 Service。
4. Service 负责后续不断 send。
5. 出错时发 error 事件并 complete。
```

## 8. 后端 Service 怎么推模型 chunk

本项目核心逻辑是：

```java
model.stream(new Prompt(conversationContext.messages()))
        .map(res -> res.getResult().getOutput().getText())
        .subscribe(
                chunk -> {
                    try {
                        if (chunk != null) {
                            fullRes.append(chunk);
                            emitter.send(Map.of("content", chunk));
                        }
                    } catch (Exception e) {
                        log.warn("SSE chunk send failed: {}", e.getMessage());
                    }
                },
                err -> {
                    log.error("stream chat failed", err);
                    asyncSaveChatRecord(
                            request.getSessionId(),
                            request.getMessage(),
                            fullRes.length() > 0 ? fullRes + "\n\n[回答中断]" : ""
                    );
                    sendStreamError(emitter, resolveErrorMessage(err));
                },
                () -> {
                    asyncSaveChatRecord(request.getSessionId(),
                            request.getMessage(),
                            fullRes.toString());
                    emitter.complete();
                }
        );
```

这里 `model.stream(...)` 返回的是 Reactor 响应式流，可以理解为：

```text
Flux<ChatResponse>
```

每个元素就是模型生成的一小段响应。

`subscribe` 三个回调：

```text
onNext(chunk):
  每来一个模型 chunk，就 emitter.send 给前端。

onError(err):
  模型流失败，保存已生成内容，发送错误事件。

onComplete():
  模型正常结束，保存完整聊天记录，关闭 SSE。
```

## 9. 后端还会推哪些事件

除了普通内容：

```java
emitter.send(Map.of("content", chunk));
```

项目还会推其他事件。

### 9.1 RAG 引用

```java
emitter.send(SseEmitter.event()
        .name("references")
        .data(Map.of("references", conversationContext.references())));
```

前端用于渲染知识库命中片段。

### 9.2 排队/处理中状态

本地 Ollama 有并发限制，排队时发送：

```java
emitter.send(SseEmitter.event()
        .name("status")
        .data(Map.of("status", "queued", "message", "AI 正在处理其他请求")));
```

开始处理时发送：

```java
emitter.send(SseEmitter.event()
        .name("status")
        .data(Map.of("status", "processing", "message", "")));
```

### 9.3 Agent 工具事件

Agent 模式还会推：

```text
tool_call_started
tool_call_result
tool_call_error
workspace_file_created
workspace_file_updated
knowledge_document_created
reviewSummary
```

这些不是普通回答文本，而是前端用来更新工具面板、Pending Action、文档卡片、审查结果面板。

## 10. 前端为什么不用 EventSource

浏览器原生 SSE API 是：

```js
const es = new EventSource('/api/chat/stream');
```

但 `EventSource` 主要适合 GET 请求，不方便：

```text
1. POST JSON body
2. 自定义 Authorization header
3. 携带复杂参数
4. 上传图片前置 fileKey 后再发请求
```

本项目聊天接口是 POST，并且需要：

```text
Authorization
message
sessionId
model
useRag
ragTopK
```

所以前端使用：

```text
fetch + response.body.getReader()
```

来手动读取 SSE 流。

## 11. 前端怎么发起流式请求

前端先添加用户消息，再添加一个机器人 loading 气泡：

```js
addMessage(userHtml, 'user', '', true);

const messageId = 'ai_' + Date.now();
addMessage(loadingHtml, 'bot', messageId);

const targetBubble = document.getElementById(messageId);
```

然后构造请求：

```js
const fetchOptions = {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
    },
    signal: currentAbortController.signal,
    body: JSON.stringify({
        message: msg,
        sessionId: activeSessionId,
        model: requestModel,
        useRag: ragEnabled,
        ragTopK: ragTopK
    })
};

const response = await authFetch('/api/chat/stream', fetchOptions);
```

`authFetch` 会补：

```http
Authorization: Bearer accessToken
```

## 12. 前端怎么持续读取 chunk

先确认响应类型：

```js
const contentType = response.headers.get('content-type') || '';
if (!contentType.includes('text/event-stream')) {
    const fallback = await response.text();
    throw new Error(`后端未返回 SSE 流: ${fallback}`);
}
```

然后读取流：

```js
const reader = response.body.getReader();
const decoder = new TextDecoder();

let buffer = '';
let currentEventName = 'message';

while (true) {
    const { value, done } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });

    let lines = buffer.split('\n');
    buffer = lines.pop();

    for (const line of lines) {
        const trimmedLine = line.trim();

        if (trimmedLine.startsWith('event:')) {
            currentEventName = trimmedLine.substring(6).trim() || 'message';
            continue;
        }

        if (!trimmedLine.startsWith('data:')) {
            continue;
        }

        const data = trimmedLine.substring(5).trim();
        const json = JSON.parse(data);

        // 根据 json 和 currentEventName 渲染
    }
}
```

这里必须有 `buffer`。

原因是网络流不会保证一次 `reader.read()` 就读到一条完整 SSE：

```text
第一次可能读到:
data: {"content":"你

第二次才读到:
好"}
```

所以需要：

```text
把本次读取追加到 buffer
按换行切分
最后一段不完整内容留到下一次
```

## 13. 前端怎么解析 SSE event/data

SSE 行分两类：

```text
event: references
data: {"references":[...]}
```

前端逻辑：

```js
if (trimmedLine.startsWith('event:')) {
    currentEventName = trimmedLine.substring(6).trim() || 'message';
    continue;
}

if (trimmedLine.startsWith('data:')) {
    const data = trimmedLine.substring(5).trim();
    const json = JSON.parse(data);
}
```

如果没有 event 行：

```text
默认 currentEventName = message
```

普通回答 chunk 一般是：

```json
{"content":"你好"}
```

RAG 引用一般是：

```json
{"references":[...]}
```

工具事件会根据 `currentEventName` 进入 `handleAgentStreamEvent(...)`。

## 14. 前端怎么动态渲染文本

前端维护一个状态对象：

```js
const streamState = {
    fullText: '',
    toolEvents: [],
    pendingActions: [],
    documentCards: [],
    workspaceCards: []
};
```

收到普通内容：

```js
if (json.content && json.content !== "[DONE]") {
    streamState.fullText += json.content;
    renderAssistantBubble(
        targetBubble,
        streamState.fullText,
        streamState.toolEvents,
        streamState.pendingActions,
        loadingHtml,
        streamState.documentCards,
        streamState.workspaceCards
    );
}
```

渲染函数：

```js
function renderAssistantBubble(targetBubble, fullText, toolEvents,
                               pendingActions, fallbackHtml,
                               documentCards, workspaceCards) {
    const toolHtml = renderAgentToolPanel(
        toolEvents,
        pendingActions,
        documentCards,
        workspaceCards
    );

    const contentHtml = fullText
        ? sanitizeHtml(marked.parse(fullText))
        : sanitizeHtml(fallbackHtml || '');

    targetBubble.innerHTML = toolHtml + contentHtml;
    targetBubble.querySelectorAll('pre code')
        .forEach(el => hljs.highlightElement(el));
    scrollToBottom();
}
```

也就是说：

```text
1. 每个 chunk 追加到 fullText。
2. fullText 用 marked 转 Markdown HTML。
3. 用 DOMPurify 做 XSS 过滤。
4. 覆盖机器人气泡 innerHTML。
5. 高亮代码块。
6. 滚动到底部。
```

## 15. 为什么不是直接 append DOM

直接 append 每个 chunk 也可以，但项目选择累计 `fullText` 后整体重渲染。

好处：

```text
1. Markdown 可以正确解析跨 chunk 的语法。
2. 代码块 ``` 可能分多次返回，整体重渲染更稳。
3. 表格、列表、链接等 Markdown 结构更容易保持正确。
4. 可以统一做 DOMPurify sanitize 和代码高亮。
```

缺点：

```text
1. 每来一个 chunk 都重新 parse Markdown，长文本时有性能成本。
2. 后续可以做节流，例如每 50ms 或 100ms 渲染一次。
```

## 16. 错误处理

后端错误：

```java
emitter.send(SseEmitter.event()
        .name("error")
        .data(Map.of("error", message)));
emitter.complete();
```

前端收到：

```js
if (json.error) {
    targetBubble.innerHTML = `
        <div style="color:#dc503c;">
            ${json.error}
        </div>`;
    continue;
}
```

如果接口没有返回 `text/event-stream`，前端会把普通响应体读出来并报错，便于定位后端异常或鉴权失败。

## 17. 停止输出

前端使用：

```js
currentAbortController = new AbortController();
```

请求时传入：

```js
signal: currentAbortController.signal
```

用户点击停止时：

```js
currentAbortController.abort();
```

这会中断 fetch stream。前端 catch 到：

```js
if (e.name === 'AbortError') {
    targetBubble.innerHTML = "已停止输出";
}
```

后端继续 `emitter.send(...)` 时可能会失败，因此后端发送 chunk 处需要 catch 异常，避免线程报错扩散。

## 18. SSE 的注意事项

### 18.1 超时

SSE 是长连接，要配置超时时间：

```java
new SseEmitter(180_000L)
```

同时也要注意：

```text
网关超时
Nginx 代理超时
浏览器连接超时
模型生成时间
```

### 18.2 代理缓冲

如果经过 Nginx，可能需要关闭响应缓冲，否则前端不能实时收到 chunk。

常见配置思路：

```nginx
proxy_buffering off;
proxy_cache off;
```

### 18.3 心跳

长时间无输出时，可以发送注释或状态事件做心跳：

```java
emitter.send(SseEmitter.event().comment("heartbeat"));
```

或：

```java
emitter.send(SseEmitter.event()
        .name("status")
        .data(Map.of("status", "processing")));
```

### 18.4 异常保存

模型输出中断时，不要直接丢弃已有内容。

本项目会保存：

```text
已生成内容 + [回答中断]
```

这样用户刷新页面后仍能看到部分结果。

### 18.5 前端安全

模型输出是 Markdown，但不能直接信任。

本项目流程：

```text
marked.parse(fullText)
-> DOMPurify.sanitize(html)
-> innerHTML
```

这能降低 XSS 风险。

## 19. SSE、WebSocket、WebFlux 对比

### 19.1 SSE vs WebSocket

```text
SSE:
  - HTTP 单向推送
  - 适合 AI 输出、通知、进度
  - 简单，易部署

WebSocket:
  - 双向长连接
  - 适合实时协作、游戏、IM
  - 连接状态管理更复杂
```

### 19.2 SseEmitter vs WebFlux

```text
SseEmitter:
  - Spring MVC 体系
  - 手动 emitter.send
  - 适合已有 MVC 项目低成本接入 SSE

WebFlux:
  - Reactive Web 栈
  - Controller 可直接返回 Flux<ServerSentEvent<?>>
  - 更适合全链路非阻塞和大量长连接
```

本项目当前选择：

```text
Spring MVC + SseEmitter 桥接 Spring AI Reactor Flux
```

原因：

```text
项目主体是 MVC/MyBatis/RedisTemplate/RestTemplate。
直接使用 SseEmitter 改造成本低。
AI 流式体验已经可以满足。
后续如果要做高并发 AI Gateway，可再迁移 WebFlux。
```

## 20. 面试高频问答

### Q1: SSE 是什么？

SSE 是基于 HTTP 的服务端单向推送机制。客户端建立连接后，服务端可以持续发送事件，适合 AI 流式输出、通知、任务进度等场景。

### Q2: SseEmitter 是什么？

`SseEmitter` 是 Spring MVC 提供的异步响应对象。Controller 返回它后，HTTP 连接保持打开，Service 可以持续调用 `emitter.send(...)` 往前端推送数据，最后用 `complete()` 结束。

### Q3: 后端怎么把模型 chunk 推给前端？

Spring AI 的 `model.stream(prompt)` 返回类似 `Flux<ChatResponse>` 的流。后端 `subscribe` 这个流，在 onNext 回调里拿到 chunk，然后调用 `emitter.send(Map.of("content", chunk))` 推给前端。

### Q4: 前端为什么不用 EventSource？

因为本项目聊天接口是 POST，并且需要 JSON body、Authorization、sessionId、model、RAG 参数。`EventSource` 主要适合 GET，不方便这些场景，所以使用 `fetch + response.body.getReader()` 手动读取 SSE 流。

### Q5: 前端怎么保证 chunk 不丢？

前端用 `buffer` 保存未处理完的流文本。每次 `reader.read()` 后追加到 buffer，按换行切分，最后一个可能不完整的片段留到下一轮继续拼接。

### Q6: 前端怎么渲染 Markdown？

每收到一个 `json.content`，追加到 `streamState.fullText`，然后用 `marked.parse` 转 HTML，用 `DOMPurify.sanitize` 做安全过滤，再更新机器人气泡并高亮代码块。

### Q7: SSE 和 WebSocket 怎么选？

AI 回复是服务端单向持续输出，用 SSE 更简单。WebSocket 更适合强双向实时通信，比如多人协作、游戏、IM。

### Q8: 为什么不用 WebFlux？

WebFlux 对 AI 流式输出更自然，但项目主体是 Spring MVC 和阻塞式数据访问栈。当前用 `SseEmitter` 桥接 Spring AI 的 Reactor Flux，改造成本更低。等后续需要大量长连接和全链路 reactive，再考虑 WebFlux。

## 21. 面试总结话术

可以这样说：

```text
我项目里的 AI 流式对话使用 SSE 实现。后端是 Spring MVC 的 SseEmitter，Controller 返回 text/event-stream，创建 SseEmitter 后交给 ChatbotService。Service 调用 Spring AI 的 model.stream(prompt)，模型每生成一个 chunk，Reactor Flux 的 onNext 回调就会触发，我在里面调用 emitter.send(Map.of("content", chunk)) 推给前端。出错时发送 error event，正常结束时保存聊天记录并 emitter.complete。

前端没有用 EventSource，因为聊天接口需要 POST JSON body 和 Authorization，所以我用 fetch 请求 SSE 接口，然后通过 response.body.getReader() 持续读取流，用 TextDecoder 解码，按 SSE 的 event/data 行解析。收到 json.content 后追加到 fullText，用 marked 渲染 Markdown，再用 DOMPurify 过滤并更新当前机器人气泡。RAG 引用和 Agent 工具调用则通过命名 event 单独更新引用栏和工具面板。
```
