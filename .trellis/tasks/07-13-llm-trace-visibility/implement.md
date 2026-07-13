# Implement — Demo 执行链路可视化: prompt/response 流式展示

> 执行计划，配套 `prd.md` + `design.md`。按顺序执行，每个 step 有验证命令与回滚点。

## 实施顺序原则

**自底向上**：先改契约（toolkit 接口），再改实现（toolkit 专家 + demo），最后改前端。这样每一步编译范围明确，失败能快速定位。

## Step 1 · 新增 LlmTraceEvent（toolkit）

**文件**：`nondocx-toolkit/src/main/java/com/non/docx/toolkit/orchestration/agent/LlmTraceEvent.java`（新增）

**做什么**：
- 单值对象 + `Kind` 枚举（PROMPT / CONTENT_DELTA / THINKING_DELTA / COMPLETE）
- 字段：kind / agentName / prompt / delta / success / error / usage
- 4 个 static 工厂：`ofPrompt(agentName, prompt)` / `ofContentDelta(agentName, delta)` / `ofThinkingDelta(agentName, delta)` / `ofComplete(agentName, success, error, usage)`
- usage 类型决策：用 nonchain `com.non.chain.callback.event.TokenUsage`（toolkit 已依赖 chain），或存 `Object` 由 demo 解释。**推荐用 Object 解耦** —— toolkit 不应硬绑框架类型。

**验证**：
```bash
cd nondocx-toolkit && mvn -q compile
```

**回滚**：删文件。无依赖。

---

## Step 2 · 改 ExpertAgent.plan 签名（toolkit 接口）

**文件**：`nondocx-toolkit/.../agent/ExpertAgent.java:56`

**做什么**：
```diff
- ExpertPlan plan(OrchestratorSession session, DocumentSnapshot snapshot, String intent);
+ ExpertPlan plan(
+     OrchestratorSession session,
+     DocumentSnapshot snapshot,
+     String intent,
+     java.util.function.Consumer<LlmTraceEvent> traceCallback);
```
- 更新 javadoc：说明 traceCallback 可空、何时会收到事件、不调 LLM 的实现可忽略。

**注意**：此步后**整个 toolkit 编译会断**（6 个实现类 + 2 个测试 mock）。这是预期，由 Step 3-4 修复。

**验证**：此步预期编译失败，不停在这里。

---

## Step 3 · 对齐 5 个启发式专家签名（toolkit，死参数）

**文件**（5 个，都不调 LLM，traceCb 实现里忽略）：
- `nondocx-toolkit/.../body/BodyAgent.java:61`
- `nondocx-toolkit/.../table/TableAgent.java:51`
- `nondocx-toolkit/.../specialist/HeaderTocAgent.java:60`
- `nondocx-toolkit/.../specialist/QualityAgent.java:65`
- `nondocx-toolkit/.../specialist/RevisionAgent.java:50`

**做什么**：每个 plan 方法签名加第 4 参数 `Consumer<LlmTraceEvent> traceCallback`，方法体不动（参数名加 `_` 或 javadoc 标注「本专家不调 LLM，忽略 traceCallback」）。统一加 `@SuppressWarnings` 或注释说明忽略是有意的。

**验证**：
```bash
cd nondocx-toolkit && mvn -q compile   # 主代码编译通过（测试还断）
```

**回滚**：revert 5 文件签名。

---

## Step 4 · 改 RouterAgent 透传 traceCb（toolkit 编排）

**文件**：`nondocx-toolkit/.../RouterAgent.java`

**做什么**（详见 design §4.2）：
1. 加字段 `private Consumer<LlmTraceEvent> currentTraceCb;`（仿 `currentCallback` 栈式）
2. 新增四参 run 重载 `run(session, intent, callback, traceCb)`，用 try/finally 保存/恢复
3. 既有两参 `run(session, intent, callback)` 改为委托四参（传 null traceCb）
4. 既有单参 `run(session, intent)` 改为委托四参（传两个 null）
5. PLAN 调用点 `:117` 改 `a.plan(session, snapshot, intent, currentTraceCb)`

**验证**：
```bash
cd nondocx-toolkit && mvn -q compile
```

---

## Step 5 · 改 DocxOrchestrator 透传 traceCb（toolkit 门面）

**文件**：`nondocx-toolkit/.../DocxOrchestrator.java:228`

**做什么**（详见 design §4.3）：
1. 新增四参 `run(convId, intent, phaseCallback, traceCb)`，透传给 `router.run`
2. 既有三参 `run(convId, intent, phaseCallback)` 改为委托（传 null traceCb）
3. 既有单参/双参 run 不动

**验证**：
```bash
cd nondocx-toolkit && mvn -q test-compile && mvn -q test
```

**回滚点**：到 Step 5 结束，toolkit 全部编译 + 测试通过。可提交一个中间 commit「toolkit: trace 接口契约」。

---

## Step 6 · 对齐测试 mock 签名（toolkit 测试）

**文件**（2 个）：
- `nondocx-toolkit/src/test/.../body/InsertHeadingTest.java:85`（`HeadingExpert`）
- `nondocx-toolkit/src/test/.../DocxOrchestratorTest.java:76`（`NoopExpert`）

**做什么**：mock 类的 plan 方法签名加第 4 参数（实现里忽略）。

**验证**：
```bash
cd nondocx-toolkit && mvn -q test
```

> **注意**：Step 5 的验证已含 test，但 Step 6 单列是为了说明 mock 改动是独立的一类改动。实际可并入 Step 5。

---

## Step 7 · LlmDocxExpert 生产 trace（demo）

**文件**：`nondocx-demo/.../LlmDocxExpert.java`

**做什么**（详见 design §5.1）：
1. `plan()`（`:69`）：buildPrompt 后发 `onPrompt`，callLlm 改为传 traceCb
2. `callLlm()`（`:238`）：`llm.chat()` 改 `llm.streamChat(msg, chunk -> {...})`，在 consumer 里按 chunk 类型发 content_delta / thinking_delta，完成后发 complete（成功/失败两条路径）
3. 既有 `log.info("发送 LLM prompt...")` 保留（后端日志仍有用）

**验证**：
```bash
cd nondocx-demo && mvn -q compile
```

**关键点**：
- streamChat 返回 ChatResult（含完整 content），consumer 里的 StringBuilder 只是冗余累积，**实际返回值用 `result.content().trim()`**（与现状一致），delta 只用于推 trace。
- catch 块要发 `onComplete(false, error, null)` 再返回空 plan。

---

## Step 8 · AgentBridge 消费 trace → SSE 帧（demo）

**文件**：`nondocx-demo/.../AgentBridge.java`

**做什么**（详见 design §5.2）：
1. `runStream()`（`:114`）：构造 `Consumer<LlmTraceEvent> traceCb` lambda，与既有 phaseCallback 并行
2. `orchestrator.run(...)`（`:131`）改调四参重载，传入 traceCb
3. 新增 `buildTraceFrame(turnId, trace)`：LlmTraceEvent → SSE 帧 Map（type=trace）

**验证**：
```bash
cd nondocx-demo && mvn -q compile
```

**回滚点**：到 Step 8 结束，后端链路全通（toolkit 接口 + demo 生产消费）。可手动跑 demo 验证 SSE 帧是否产出（用 curl 看 `/api/chat` 输出）。

**手动验证**：
```bash
# 启动 demo 后（需 DASHSCOPE_API_KEY + OnlyOffice server）
curl -N -X POST http://localhost:8080/api/chat -d 'message=在开头加一个标题'
# 预期看到 trace/prompt → trace/thinking_delta × N → trace/content_delta × N → trace/complete → step(plan) ...
```

---

## Step 9 · 前端 trace 帧分发（app.js）

**文件**：`nondocx-demo/src/main/resources/static/app.js`

**做什么**（详见 design §7）：
1. `handleSseFrame` switch（`:183`）加 `trace` 分支
2. 按 `event` 字段分发：prompt / content_delta / thinking_delta / complete
3. prompt → 在当前 turnId 的进度卡内创建折叠区 + 写 prompt 只读块
4. content_delta → 追加到 response 区域（复用 `appendAssistantText` 思路，`:303`）
5. thinking_delta → 追加到 thinking 区域
6. complete → 标记完成（success=false 时标红）

**验证**：浏览器手动验证（无法自动化）。

---

## Step 10 · 前端折叠区样式（style.css）

**文件**：`nondocx-demo/src/main/resources/static/style.css`

**做什么**：
- `.trace-panel`（折叠区容器）
- `.trace-prompt`（只读 pre 块，可滚动）
- `.trace-response` / `.trace-thinking`（逐字追加区）
- `.trace-tabs`（tab 切换按钮）
- `.trace-error`（错误标红）

**验证**：浏览器手动验证。

---

## Step 11 · 全链路验证

**做什么**：
1. 完整跑一遍 demo（启动 + 浏览器对话），核对 AC1-AC5。
2. 跑 toolkit + demo 全部测试，核对 AC6-AC7。
3. 确认 `index.html` 零改动（AC8）。

**验证命令**：
```bash
cd nondocx-toolkit && mvn -q test
cd nondocx-demo && mvn -q test && mvn -q compile
git diff --stat -- nondocx-demo/src/main/resources/static/index.html   # 应为空
```

## 风险点

| 风险 | 应对 |
|---|---|
| streamChat 的 ChatChunk 在 qwen3.7-plus 下字段填充情况（thinking 是否真的流式分块） | Step 7 后用 curl 验证 trace 帧是否真实产出 thinking_delta。若不分块，complete 帧带完整 thinkingContent 兜底。 |
| 5 个启发式专家签名改动遗漏（忘记改某个） | 编译会断，Step 3 验证能抓住。 |
| 前端折叠区在 prompt 很大时性能 | pre 块加 `max-height` + `overflow:auto`。 |
| CHAT_LOCK 持锁期间 streamChat 回调阻塞 | streamChat 是非阻塞流式（框架处理），回调只写 SSE 帧，不阻塞。 |

## 不做的事

- 不改 PhaseCallback（决策）。
- 不引入 nonchain Agent/ToolRegistry（决策）。
- 不改 index.html 顶层布局（决策）。
- 不动硬编码 API key（既有问题，非本任务）。
- 不加 thinking toggle（产品决策，后续任务）。
