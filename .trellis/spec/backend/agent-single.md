# 单 Agent 文档编辑

> `nondocx-toolkit` 只提供工具与共享文档会话；Agent 拓扑属于应用层。demo 使用**单 Agent**：一个 agent 持有全部只读 + 写 + 质检工具，但**不持有保存工具**——保存由应用层在 agent 循环结束时强制执行。禁止恢复 SubAgent 委派层、RouterAgent、Dispatcher、操作计划或提交协调器。

## 历史：为何从 SubAgent 回归单 Agent

2026-07-13 曾引入"主 Agent + 无状态 SubAgent"双层拓扑（commit `dbae09f`），用一等 SubAgent 工具 `invoke_subagent` 替换旧编排层。但它随即引入了它本想消灭的复杂度：SubAgent 的 LLM 自述（漏调 `save_current_document` / 谎报成功）与磁盘真相之间存在鸿沟，逼出 `attemptFallbackSave` + `correctSubAgentResult` + `DocumentExecutionState` 三件套"真相弥合"逻辑（commit `0e2bca7`）。

2026-07-15 回归单 Agent（本 spec）。核心洞察：**鸿沟的根因不是"有两个 agent"，而是"保存是 LLM 显式触发的动作"**。把保存从 LLM 职责移到应用层代码强制（`AgentEvent.Complete` 时 flush），"漏调 / 谎报"在源头消失，整层真相弥合逻辑删除。

## Scenario: 当前文档的单 Agent 编辑

### 1. Scope / Trigger

demo 或应用需要根据自然语言直接编辑当前 docx 时，单个 Agent 同时承担咨询（只读）与编辑（写 + 质检）。Agent 没有保存工具；保存由应用层在 agent 主循环结束（`AgentEvent.Complete`）时强制执行。

### 2. Signatures

```java
ToolRegistry tools =
    new ToolRegistry()
        .scan(documentTools)      // current_document（只读句柄）；不含 save
        .scan(toolkit.view)
        .scan(toolkit.body)
        .scan(toolkit.table)
        .scan(toolkit.headerFooterToc)
        .scan(toolkit.trackedChangeQuery)
        .scan(toolkit.trackedChangeAuthoring)
        .scan(toolkit.qualityCheck);

Agent agent =
    Agent.builder(llm, tools)
        .memory(memory)
        .maxIterations(24)
        .addBeforeToolCall(this::beforeToolCall)   // 取消拦截
        .addAfterToolCall(this::afterToolCall)     // dirty 检测 + 结果瘦身 + note
        .systemPrompt("...")
        .build();
```

- `POST /api/chat`：`{"message":"..."}`；咨询与编辑均在此请求内完成。
- `POST /api/cancel`：协作式取消当前编辑（block 后续写工具）。
- `GET /api/trace`：返回 JSONL trace。
- 不存在 `/api/execute`、授权 token、SubAgent 委派、计划或提交端点。

### 3. Contracts

- Agent registry 含 `view_*`/`read_*`/写工具/`check_quality`/`current_document`。**不含** `save_current_document`/`save_docx`/`open_docx`/`close_docx`——保存是应用层方法，不是 LLM 工具。
- Agent 先调用 `current_document` 取得当前 `doc_id`，不能猜测路径。
- **保存代码强制**：`AgentEvent.Complete` 时，若本轮 `dirty`（发生过写工具调用），应用层调 `DocumentTools.saveCurrentDocument(cancelled)`——复用"质检 error 拒绝 / warning 允许 + 落盘"门控。
- **dirty 检测**：写工具的 `AfterToolCall` 把 `dirty` 置 true。只读判定（`isReadonly`）按工具名前缀：`view_`/`read_`/`get_`/`list_`/`search_`/`check_` 及精确集合 `current_document`/`describe_capabilities` 为只读；**未知工具视为写**（安全默认：漏标 dirty 会丢编辑，多标只是浪费一次 flush）。
- **漏调 check 兜底**：`Complete` 时若 `dirty && 未显式调 check_quality`，`saveCurrentDocument` 内部的质检门控自动补跑，不绕过质检。
- SSE 使用 `edit_outcome` 帧返回权威成败状态（`status`: `noop`/`saved`/`rolled_back`/`cancelled`）；`doc_changed` 仅在服务端保存成功后发送。
- `trace` 保留 Agent 的工具 `tool_start`/`tool_end`（单 Agent 下写工具事件直接可见，可观测性优于 SubAgent 模式）。

### 4. Validation & Error Matrix

| 条件 | 服务端行为 |
|---|---|
| Agent 只调只读工具（咨询） | `dirty=false`，`Complete` 发 `edit_outcome.status=noop`，零写入 |
| 写工具成功 + 质检通过 | `Complete` flush 落盘，`status=saved`，发 `doc_changed` |
| 质量检查有 `error` | `saveCurrentDocument` 拒绝保存，`reopen` 回滚，`status=rolled_back` |
| 质量检查只有 `warning` | 保存，`status=saved`，`qualityReport` 随 `edit_outcome` 回传 |
| Agent 改了文档但漏调 `check_quality` | flush 时门控自动补跑质检，按结果落盘或回滚 |
| 收到取消 | `BeforeToolCall` block 后续写工具，`Complete` 时 `reopen` 回滚，`status=cancelled` |
| 保存失败（IO 等） | `reopen` 回滚，`status=rolled_back` |

### 5. Good / Base / Bad Cases

- Good：用户请求"在文档开头添加居中标题"，Agent 调 `current_document` → `insert_paragraph(body_index=0, heading_level=H1, alignment=CENTER)` → `check_quality`；循环结束，应用层 flush 落盘。
- Base：用户询问文档有几段，Agent 只调用 `view_stats`，`dirty=false`，不触发 flush。
- Bad：给 Agent 注册 `save_docx`/`save_current_document` 工具，或恢复 SubAgent 委派层、计划/分派/提交。

### 6. Tests Required

- 单元测试：`saveCurrentDocument` 保存带警告的文档；取消拒绝保存；重新打开后未保存改动不存在；save 不暴露为工具。
- dirty 检测测试：只读前缀不置 dirty；写工具置 dirty；未知工具视为写（安全默认）。
- 结果瘦身测试：写工具结果瘦身为一行确认（不回显 data）；质检摘要截断 + note 存档提示。
- `edit_outcome` status 派生测试：noop/saved/rolled_back/cancelled 矩阵；取消优先。
- 集成测试：真实 VLLM 单 Agent 直接调写工具（不再有 `invoke_subagent`），临时 docx 第一段为"项目周汇报"且居中。

### 7. Wrong vs Correct

```java
// Wrong：给 Agent 保存工具，或恢复 SubAgent/编排层。
ToolRegistry tools = toolkit.scanAll(new ToolRegistry());  // 含 save_docx
primaryTools.registerSubAgent("invoke_subagent", ...).toolRegistry(childTools).build();

// Correct：单 Agent 持有写工具但不持有保存；保存由 Complete 时应用层强制。
ToolRegistry tools = new ToolRegistry().scan(documentTools).scan(toolkit.view)
    .scan(toolkit.body).scan(toolkit.table)...;
// documentTools.saveCurrentDocument() 是普通方法（无 @ToolDef），由 AgentEvent.Complete 触发。
```

## Convention: 保存不是 LLM 职责

`save_current_document` **不是** `@ToolDef`。它是 `DocumentTools` 的应用层方法，由 `AgentBridge` 在 `AgentEvent.Complete` 时调用。这从源头消灭"LLM 漏调 save / 谎报成功"，无需 SubAgent + 真相纠正编排层。一旦把 save 暴露为工具，"自述 ≠ 真相"的鸿沟立刻回归。

## Convention: 记忆污染治理（α 瘦身 + β note）

单 Agent 持久记忆窗口（`MessageWindowChatMemory`，按条数淘汰不按 token）会被写工具结果/质检报告撑爆。SubAgent 的"无记忆"曾是天然 GC 边界，回归后必须手动重建等价机制：

- **α 写工具瘦身**：写工具（insert/replace/delete body、table、修订等）的 `AfterToolCall` 把结果改写为一行确认（"✓ insert_paragraph：已插入"）。LLM 刚写完，不需回显全文。
- **β note 隔离**：`check_quality` 原文挂 `Message.note("quality_report", 全文)`（`llmVisible=false`：不占窗口、不喂 LLM），紧凑摘要作真工具结果喂 LLM。
- **读工具保持全量**：`view_*`/`current_document` 的输出是 LLM 推理的必要输入，不瘦身。

`Message.note` 的约束：`llmVisible=false` 意味着 LLM 看不到它，只能用于"给 UI/应用层重放但不喂 LLM"的数据。LLM 要据之推理的内容**必须**是真 tool result。

## Convention: 取消真相（prompt 诚实 + SSE 系统帧）

单 Agent 文本流式、不可事后改写。取消竞态下 Agent 可能流式输出"已完成"。应对分两层：

- **① prompt 诚实**：`BeforeToolCall` block 消息含强指令"禁止声称已完成/已保存"（概率性改善）。
- **② SSE 系统帧**：`edit_outcome` 帧由服务端状态（`dirty/saved/cancelled`）钉死 `status`，前端**以帧为准**渲染成败口径，Agent 文本仅作辅助（确定性）。

这是原 `correctSubAgentResult`（JSON 矩阵改写）的合理下沉：从"事后改写工具结果"→"前端按系统帧渲染口径"。净简化。

## Gotcha: VLLM 工具调用解析

`VLLM` provider 通过 `chat_template_kwargs.enable_thinking` 传递思考开关，并从 `reasoning` 字段读取思考文本。真实集成测试必须禁用思考。若响应是 `finish_reason=tool_calls` 但 `tool_calls=[]`，问题在 VLLM 服务的 tool-call parser 配置，客户端不得伪造工具调用或把集成测试改为跳过；测试应失败并显示此诊断。

## Gotcha: AgentEvent.Complete 是唯一的循环结束钩子

nonchain `Agent.Builder` 无 `onComplete`/`afterLoop`/`finally` 方法；`ChainCallback` 只有单次 LLM/工具调用粒度回调，无循环结束事件。唯一能拿到"agent 循环结束"信号的是 `run(msg, Consumer<AgentEvent>)` 流式重载里的 `AgentEvent.Complete`。`maxIterations` 耗尽（默认 `graceTurns=3`）也会发 `Complete`，因此 flush 天然覆盖超时路径。**非流式 `run(msg)` 重载不发 Complete**——demo 必须用流式重载。
