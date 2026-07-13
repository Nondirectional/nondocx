# Design — Demo 执行链路可视化: prompt/response 流式展示

> 配套 `prd.md`。本文记录 LLM trace（prompt + response + thinking）从 `LlmDocxExpert` 穿透到前端的完整契约、数据流、改动点与兼容性。

## 1. 设计目标

打通「LLM 专家内部 → 前端用户」的可见性通道。用户能实时看到：
1. 发给 LLM 的完整 prompt（静态）
2. LLM 返回的 response（逐字流式）
3. LLM 的 thinking（逐字流式，与 response 分开）

当前这条链路是黑盒：前端只收 3 个汇总进度帧，中间 LLM 调用阻塞且不可见。

## 2. 三层改动总览

```
toolkit 接口层（契约改动）
  ├─ 新增 LlmTraceEvent（事件值对象）
  └─ ExpertAgent.plan 签名加 Consumer<LlmTraceEvent>

toolkit 编排层（透传改动）
  ├─ RouterAgent.run 加 traceCallback 参数 + currentTraceCallback 栈式字段
  └─ DocxOrchestrator.run 加 traceCallback 参数

demo 层（生产 + 消费 trace）
  ├─ LlmDocxExpert.plan 实现 trace 生产（callLlm 改 streamChat）
  └─ AgentBridge.runStream 实现 trace 消费（trace → SSE 帧）

前端层（展示）
  ├─ app.js: handleSseFrame 加 trace 分支 + 进度卡折叠区渲染
  └─ style.css: 折叠区样式
```

## 3. 核心契约：LlmTraceEvent

toolkit 层新增值对象，放在 `orchestration/agent/` 包（与 ExpertAgent 同包）。

### 3.1 事件种类（4 种）

| 事件 | 时机 | 携带数据 |
|---|---|---|
| `onPrompt` | prompt 构造完成后、调 LLM 前 | agentName, prompt（完整字符串） |
| `onContentDelta` | 每收到一个 content chunk | agentName, delta（增量字符串） |
| `onThinkingDelta` | 每收到一个 thinking chunk | agentName, delta（增量字符串） |
| `onComplete` | LLM 调用结束（成功或失败） | agentName, success(bool), error(可空), usage(可空) |

### 3.2 设计选择

- **用单值对象 + 事件类型枚举，而非 4 方法接口**。ExpertAgent.plan 第 4 参数是 `Consumer<LlmTraceEvent>`，demo 用 lambda 消费。好处：RouterAgent/expert 不需关心 4 个方法，只调 `traceCb.accept(event)`；null 时跳过。
- **agentName 字段强制携带**。为未来多专家场景（BodyAgent/TableAgent 各自产 trace）预留前端分组依据。当前单专家，值恒为 `"LlmDocxExpert"`。
- **onComplete 带 success + error**。失败时 expert 仍返回空 plan（保持 `LlmDocxExpert.java:244-248` 既有行为），不阻断流程。

### 3.3 草图

```java
package com.non.docx.toolkit.orchestration.agent;

public final class LlmTraceEvent {
  public enum Kind { PROMPT, CONTENT_DELTA, THINKING_DELTA, COMPLETE }

  private final Kind kind;
  private final String agentName;
  private final String prompt;       // PROMPT 时非空
  private final String delta;        // CONTENT_DELTA / THINKING_DELTA 时非空
  private final boolean success;     // COMPLETE 时有意义
  private final String error;        // COMPLETE 失败时非空
  private final TokenUsage usage;    // COMPLETE 成功时可空（用 Object 或框架类型）

  // 工厂: static ofPrompt / ofContentDelta / ofThinkingDelta / ofComplete
}
```

> **usage 类型处理**：nonchain `ChatResult.tokenUsage()` 返回 `com.non.chain.callback.event.TokenUsage`。toolkit 已依赖 chain（ExpertAgent 用到 DocumentSnapshot 等），可直接引用。若想解耦，LlmTraceEvent 存 `Object usage`，由 demo 侧自行解释。

## 4. toolkit 接口改动

### 4.1 ExpertAgent（`agent/ExpertAgent.java:56`）

```diff
- ExpertPlan plan(OrchestratorSession session, DocumentSnapshot snapshot, String intent);
+ ExpertPlan plan(
+     OrchestratorSession session,
+     DocumentSnapshot snapshot,
+     String intent,
+     Consumer<LlmTraceEvent> traceCallback);  // 可空
```

**改原签名（非 default 重载）** —— 决策已定。所有实现类签名必改，强制面对 trace 契约。

### 4.2 RouterAgent（`RouterAgent.java:92, :117`）

新增栈式字段（仿现有 `currentCallback`，`RouterAgent.java:46`）：

```java
private PhaseCallback currentCallback;          // 既有
private Consumer<LlmTraceEvent> currentTraceCb; // 新增
```

run 重载加 traceCb 参数：

```java
public RouterResult run(OrchestratorSession session, String intent) {
  return run(session, intent, null, null);
}

public RouterResult run(OrchestratorSession session, String intent, PhaseCallback callback) {
  return run(session, intent, callback, null);
}

public RouterResult run(OrchestratorSession session, String intent,
                        PhaseCallback callback, Consumer<LlmTraceEvent> traceCb) {
  PhaseCallback prevCb = this.currentCallback;
  Consumer<LlmTraceEvent> prevTrace = this.currentTraceCb;
  this.currentCallback = callback;
  this.currentTraceCb = traceCb;
  try {
    return runInternal(session, intent);
  } finally {
    this.currentCallback = prevCb;
    this.currentTraceCb = prevTrace;
  }
}
```

PLAN 阶段调用点（`:117`）透传：

```diff
- ExpertPlan ep = a.plan(session, snapshot, intent);
+ ExpertPlan ep = a.plan(session, snapshot, intent, currentTraceCb);
```

### 4.3 DocxOrchestrator（`DocxOrchestrator.java:228`）

run 重载加 traceCb，透传给 RouterAgent：

```java
public RouterResult run(String conversationId, String intent, PhaseCallback phaseCallback) {
  return run(conversationId, intent, phaseCallback, null);
}

public RouterResult run(String conversationId, String intent,
                        PhaseCallback phaseCallback, Consumer<LlmTraceEvent> traceCb) {
  // ... 既有 session 解析
  return router.run(session, intent, phaseCallback, traceCb);
}
```

## 5. demo 层改动

### 5.1 LlmDocxExpert（trace 生产者）

**`plan()`（`:69`）**：构造 prompt 后先发 `onPrompt`，再调 callLlm。

```java
String prompt = buildPrompt(snapshot, intent);
if (traceCb != null) traceCb.accept(LlmTraceEvent.ofPrompt(name(), prompt));
String llmOutput = callLlm(prompt, traceCb);  // traceCb 传入，内部流式回调
```

**`callLlm()`（`:238`）改 streamChat**：

```java
private String callLlm(String prompt, Consumer<LlmTraceEvent> traceCb) {
  try {
    Message message = Message.user(prompt);
    StringBuilder acc = new StringBuilder();
    ChatResult result = llm.streamChat(List.of(message), chunk -> {
      if (traceCb == null) return;
      if (chunk.hasContent()) {
        acc.append(chunk.deltaContent());
        traceCb.accept(LlmTraceEvent.ofContentDelta(name(), chunk.deltaContent()));
      }
      if (chunk.hasThinking()) {
        traceCb.accept(LlmTraceEvent.ofThinkingDelta(name(), chunk.deltaThinking()));
      }
    });
    if (traceCb != null) {
      traceCb.accept(LlmTraceEvent.ofComplete(name(), true, null, result.tokenUsage()));
    }
    return result.content().trim();
  } catch (RuntimeException e) {
    log.warn("LLM 调用失败,返回空 plan: {}", rootMessage(e));
    if (traceCb != null) {
      traceCb.accept(LlmTraceEvent.ofComplete(name(), false, rootMessage(e), null));
    }
    return "{\"operations\":[]}";
  }
}
```

### 5.2 AgentBridge（trace 消费者 → SSE）

**`runStream()`（`:114`）**：构造 traceCallback lambda，与 phaseCallback 并行：

```java
PhaseCallback callback = event -> { /* 既有 step 帧逻辑 */ };

java.util.function.Consumer<LlmTraceEvent> traceCb = trace -> {
  Map<String, Object> traceFrame = buildTraceFrame(turnId, trace);
  writeFrame(ctx, traceFrame);
  flush(ctx);
};

RouterResult result = orchestrator.run(conversationId, message, callback, traceCb);
```

**`buildTraceFrame()`（新增）**：LlmTraceEvent → SSE 帧 JSON。

## 6. SSE 帧协议扩展

现有帧：`step` / `doc_changed` / `error` / `done`。**新增 `trace`**。

### 6.1 帧格式

```json
// prompt（一次性）
{"type":"trace","turnId":"turn-1","agent":"LlmDocxExpert","event":"prompt","prompt":"..."}

// response 逐字
{"type":"trace","turnId":"turn-1","agent":"LlmDocxExpert","event":"content_delta","delta":"..."}

// thinking 逐字
{"type":"trace","turnId":"turn-1","agent":"LlmDocxExpert","event":"thinking_delta","delta":"..."}

// 完成
{"type":"trace","turnId":"turn-1","agent":"LlmDocxExpert","event":"complete","success":true}
{"type":"trace","turnId":"turn-1","agent":"LlmDocxExpert","event":"complete","success":false,"error":"..."}
```

### 6.2 时序（关键）

```
analyze step 帧（既有）
┊ trace/prompt 帧（新）          ← PLAN 进行中
┊ trace/thinking_delta × N（新）
┊ trace/content_delta × N（新）
┊ trace/complete 帧（新）
plan step 帧（既有，汇总 operation 人话清单）  ← PLAN 完成后
commit step 帧（既有）
doc_changed / done 帧（既有）
```

trace 帧与 plan step 帧**共存**（决策 R4）：trace 是 LLM 思考过程，step 是编排层翻译产物，语义分层不冲突。

## 7. 前端改动（app.js + style.css，不改 index.html）

### 7.1 frame 分发（`app.js:183` switch）

加 `trace` 分支，按 `event` 字段分发到 4 个 handler：
- `prompt` → 在当前进度卡内创建折叠区，写 prompt 只读块
- `content_delta` → 追加到 response 区域（复用 `appendAssistantText` 死代码逻辑）
- `thinking_delta` → 追加到 thinking 区域
- `complete` → 标记完成/错误

### 7.2 进度卡折叠区结构

在现有 progress-card 内（按 turnId 缓存），对涉及 trace 的阶段追加：

```html
<details class="trace-panel" open>
  <summary>LLM 推理过程</summary>
  <div class="trace-tabs">
    <button data-tab="response">Response</button>
    <button data-tab="thinking">Thinking</button>
  </div>
  <pre class="trace-prompt">...</pre>          <!-- 可复制只读 -->
  <div class="trace-response" data-active></div> <!-- 逐字追加 -->
  <div class="trace-thinking" hidden></div>      <!-- 逐字追加，默认隐藏 -->
</details>
```

不引框架，用原生 `<details>` + 简单 tab 切换。

## 8. 兼容性

### 8.1 toolkit 向后兼容
- `ExpertAgent.plan` 签名变更 → 所有实现类必改。grep 确认共 **6 个实现类 + 2 个测试 mock**：
  - **调 LLM 的（1 个）**：`demo/LlmDocxExpert` —— 真正生产 trace。
  - **不调 LLM 的启发式专家（5 个）**：`toolkit/.../body/BodyAgent`、`table/TableAgent`、`specialist/HeaderTocAgent`、`specialist/QualityAgent`、`specialist/RevisionAgent` —— traceCb 是纯死参数（实现里忽略），但签名必须加，为未来接入 LLM 时不用再改签名（决策：仍改原签名）。
  - **测试 mock（2 个）**：`InsertHeadingTest.HeadingExpert`、`DocxOrchestratorTest.NoopExpert` —— 签名对齐。
- `RouterAgent.run` / `DocxOrchestrator.run` 保留旧重载（传 null traceCb），既有调用方不改也能编译。

### 8.2 测试影响
- toolkit 既有测试若 mock 了 `ExpertAgent.plan`，签名变更后需更新 mock。
- RouterAgent 单测若调 `run(session, intent, callback)` 三参重载，不受影响（新增的四参重载内部委托）。

### 8.3 运行时
- `traceCb == null` 时（如无前端场景 / 单测），`LlmDocxExpert` 的 `if (traceCb != null)` 守卫跳过所有 trace 逻辑，性能与现状一致。

## 9. 风险与权衡

| 风险 | 缓解 |
|---|---|
| thinking 内容含 prompt 复述/内部术语，展示给最终用户可能不妥 | 本次只打通架构；产品 toggle（默认开/关）后续再加。前端折叠区默认折叠 thinking tab。 |
| prompt 几 KB，一次性推 trace/prompt 帧占带宽 | 前端折叠区可滚动；SSE 无硬上限。可接受。 |
| streamChat 回调在 CHAT_LOCK 持锁线程内 | 单请求内串行，回调直接写 OutputStream，Javalin 单连接安全。 |
| 多专家并发时 trace 交叉（未来） | 事件带 agentName，前端按 agent 分组。当前单专家无此问题。 |

## 10. 文件改动清单

| 文件 | 改动类型 |
|---|---|
| `toolkit/.../agent/LlmTraceEvent.java` | 新增 |
| `toolkit/.../agent/ExpertAgent.java` | 改 plan 签名 |
| `toolkit/.../RouterAgent.java` | 加 traceCb 字段 + run 重载 + 透传 |
| `toolkit/.../DocxOrchestrator.java` | 加 run 重载 + 透传 |
| `demo/.../LlmDocxExpert.java` | plan 发 trace + callLlm 改 streamChat |
| `demo/.../AgentBridge.java` | 构造 traceCb + buildTraceFrame |
| `demo/.../static/app.js` | trace 帧分发 + 折叠区渲染 |
| `demo/.../static/style.css` | 折叠区样式 |
| toolkit 其他 ExpertAgent 实现类（如有） | 签名对齐 |
| toolkit 测试 mock（如有） | 签名对齐 |
