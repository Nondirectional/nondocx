# 单 Agent 文档编辑

> `nondocx-toolkit` 只提供工具与共享文档会话；Agent 拓扑属于应用层。demo 使用**单 Agent**：一个 agent 持有全部只读 + 写工具，但**不持有保存工具**——保存由应用层在 agent 循环结束时强制执行。**禁止恢复会执行编辑/保存的 SubAgent 委派层**、RouterAgent、Dispatcher、操作计划或提交协调器。唯一允许的 SubAgent 是**只读复审 SubAgent**（见 §只读复审 SubAgent 例外）。

## 历史：为何从 SubAgent 回归单 Agent

2026-07-13 曾引入"主 Agent + 无状态 SubAgent"双层拓扑（commit `dbae09f`），用一等 SubAgent 工具 `invoke_subagent` 替换旧编排层。但它随即引入了它本想消灭的复杂度：SubAgent 的 LLM 自述（漏调 `save_current_document` / 谎报成功）与磁盘真相之间存在鸿沟，逼出 `attemptFallbackSave` + `correctSubAgentResult` + `DocumentExecutionState` 三件套"真相弥合"逻辑（commit `0e2bca7`）。

2026-07-15 回归单 Agent（本 spec）。核心洞察：**鸿沟的根因不是"有两个 agent"，而是"保存是 LLM 显式触发的动作"**。把保存从 LLM 职责移到应用层代码强制（`AgentEvent.Complete` 时 flush），"漏调 / 谎报"在源头消失，整层真相弥合逻辑删除。

2026-07-17 引入**只读复审 SubAgent** `review_intent`：质检目标从「客观版式规则自检」改为「复审用户本轮期望的修改是否达成」。该 SubAgent 仅持有 `view_*` 只读工具，不持有写工具、不接触保存，因此**物理上无法触发**历史 SubAgent 的真相鸿沟——这是本 spec 允许的唯一 SubAgent 例外。复审为软警告（不拦截保存）。

## Convention: 只读复审 SubAgent 例外

上述 SubAgent 禁令针对的是**会执行编辑/保存的委派层**——它们的根因问题是"SubAgent 自述保存成功 ≠ 磁盘真相"。判断一个 SubAgent 是否被允许，只看一条：

> **该 SubAgent 的 ToolRegistry 是否包含任何写工具或保存能力？**

- 含写/保存工具 → **禁止**。它会在 SubAgent 内触发"漏调 save / 谎报成功"，真相鸿沟回归。
- 仅含 `view_*` 等只读工具 → **允许**。它无法改文档、无法保存，自述与真相之间没有可错配的写入动作。

`review_intent` 属于后者：仅 `scan(toolkit.view)`，做意图达成度的只读复审，输出三态结论（达成/部分达成/未达成）+ 差异说明，作为软警告诊断信号。新增其它 SubAgent 必须满足"ToolRegistry 仅只读"且通过测试断言（见 §Tests Required）。

### Gotcha: SubAgent 的 contextSelector 必须注入 doc_id

只读复审 SubAgent 用 `view_*` 工具读文档，而 `view_*` 需要 `doc_id` 参数。主 Agent 通过 `current_document` 拿到的句柄**不在 SubAgent 上下文里**——框架默认从父消息链裁剪的上下文只含 user/assistant/tool 消息，不含主 Agent 私有的 `docId` 字段。

若 `contextSelector` 只注入「用户本轮请求」而漏掉 doc_id，复审 SubAgent 调 `view_*` 会因缺句柄读不到文档，把**正常完成的修改误判为「未达成」**（实测回归点：2026-07-17）。修复：`selectReviewContext` 必须在注入的 prompt 里显式带上当前 `docId`（它在 `openCurrentDocument()` 构造期已设置，复审发生时必然非空）。

`ReviewSubAgentTest#reviewContextInjectsDocIdAndRequest` 锁定此契约：断言注入的 prompt 同时含 doc_id 与本轮请求。任何重写 contextSelector 的改动都不得丢掉 doc_id。

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
        .registerSubAgent("review_intent", "...")  // 只读复审 SubAgent
            .systemPrompt(REVIEW_PROMPT)
            .toolRegistry(new ToolRegistry().scan(toolkit.view))  // 仅只读
            .llm(llm).maxIterations(6).contextSelector(...).build();

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

- Agent registry 含 `view_*`/`read_*`/写工具/`review_intent`（只读复审 SubAgent）/`current_document`。**不含** `save_current_document`/`save_docx`/`open_docx`/`close_docx`——保存是应用层方法，不是 LLM 工具。**不含** toolkit 的规则 `check_quality`（demo 不再 scan 它；toolkit 的 10 项规则作为通用能力保留，供 `view_issues` 等复用）。
- Agent 先调用 `current_document` 取得当前 `doc_id`，不能猜测路径。
- **保存代码强制（软警告）**：`AgentEvent.Complete` 时，若本轮 `dirty`，应用层调 `DocumentTools.saveCurrentDocument(cancelled)`——**dirty 即落盘，无质检门控**。意图复审（`review_intent`）为软警告：复审判「未达成」**不拦截保存**，复审结论随 `edit_outcome.qualityReport` 回传供用户判断。
- **dirty 检测**：写工具的 `AfterToolCall` 把 `dirty` 置 true。只读判定（`isReadonly`）按工具名前缀：`view_`/`read_`/`get_`/`list_`/`search_`/`check_`/`review_` 及精确集合 `current_document`/`describe_capabilities`/`get_subagent_result`/`steer_subagent` 为只读；**未知工具视为写**（安全默认：漏标 dirty 会丢编辑，多标只是浪费一次 flush）。`review_intent` 归只读——复审 SubAgent 不改文档。
- **只读复审 SubAgent 构造**：`registerSubAgent("review_intent", ...)` 的 `toolRegistry` 必须**仅 `scan(toolkit.view)`**——不 scan 任何写工具、不 scan 任何含 save 的类、不注册嵌套 SubAgent。这是 spec 允许例外的物理保证，须有测试断言。
- 无"漏调 check 兜底"：复审为软警告，不拦截保存，故 `dirty` 时无论是否调过 `review_intent` 都直接落盘；复审结论缺失只是 `qualityReport` 为空。
- SSE 使用 `edit_outcome` 帧返回权威成败状态（`status`: `noop`/`saved`/`rolled_back`/`cancelled`）；`doc_changed` 仅在服务端保存成功后发送。
- `trace` 保留 Agent 的工具 `tool_start`/`tool_end`（单 Agent 下写工具事件直接可见，可观测性优于 SubAgent 模式）。

### 4. Validation & Error Matrix

| 条件 | 服务端行为 |
|---|---|
| Agent 只调只读工具（咨询） | `dirty=false`，`Complete` 发 `edit_outcome.status=noop`，零写入 |
| 写工具成功 + 复审达成 | `Complete` flush 落盘，`status=saved`，发 `doc_changed`，复审结论随 `qualityReport` 回传 |
| 写工具成功 + 复审判未达成 | **仍落盘**（软警告），`status=saved`，复审结论（未达成 + 差异）随 `qualityReport` 回传供用户判断 |
| 写工具成功 + 漏调 `review_intent` | 仍落盘（无门控），`status=saved`，`qualityReport` 为空 |
| 收到取消 | `BeforeToolCall` block 后续写工具，`Complete` 时 `reopen` 回滚，`status=cancelled` |
| 保存失败（IO 等） | `reopen` 回滚，`status=rolled_back` |

> 复审不产生 `rolled_back`：复审为软警告，无论结论如何都落盘。`rolled_back` 仅由取消或保存失败触发。

### 5. Good / Base / Bad Cases

- Good：用户请求"在文档开头添加居中标题"，Agent 调 `current_document` → `insert_paragraph(body_index=0, heading_level=H1, alignment=CENTER)` → `review_intent`（复审 SubAgent 读文档对比请求）；循环结束，应用层 flush 落盘，复审结论随 `edit_outcome` 回传。
- Base：用户询问文档有几段，Agent 只调用 `view_stats`，`dirty=false`，不触发 flush、不触发复审。
- Bad：给 Agent 注册 `save_docx`/`save_current_document` 工具；给 `review_intent` 的 toolRegistry scan 任何写工具；恢复会编辑/保存的 SubAgent 委派层、计划/分派/提交。

### 6. Tests Required

- 单元测试：`saveCurrentDocument` dirty 即落盘（无质检门控）；取消拒绝保存；重新打开后未保存改动不存在；save 不暴露为工具。
- dirty 检测测试：只读前缀（含 `review_`）不置 dirty；写工具置 dirty；未知工具视为写（安全默认）。
- 结果瘦身测试：写工具结果瘦身为一行确认（不回显 data）；复审结论摘要截断 + note 存档提示；`parseVerdict`/`summarizeReview` 三态解析。
- **只读复审 SubAgent 不变量测试**：断言 `review_intent` 的 ToolRegistry 仅含 `view_*`（不含任何写/保存工具）——这是 spec 允许例外的物理保证。
- `edit_outcome` status 派生测试：noop/saved/rolled_back/cancelled 矩阵；取消优先；复审未达成不进 rolled_back。
- 集成测试：真实 VLLM 单 Agent 直接调写工具 + `review_intent`（不再有 `invoke_subagent`），临时 docx 第一段为"项目周汇报"且居中。

### 7. Wrong vs Correct

```java
// Wrong：给 Agent 保存工具；给复审 SubAgent 写工具；恢复会编辑/保存的 SubAgent 委派层。
ToolRegistry tools = toolkit.scanAll(new ToolRegistry());  // 含 save_docx
registry.registerSubAgent("review_intent", "...")
    .toolRegistry(new ToolRegistry().scan(toolkit.body))   // ✗ 复审 SubAgent 含写工具！真相鸿沟回归
    .build();
primaryTools.registerSubAgent("invoke_subagent", ...).toolRegistry(childTools).build();

// Correct：单 Agent 持有写工具但不持有保存；保存由 Complete 时应用层强制。
ToolRegistry tools = new ToolRegistry().scan(documentTools).scan(toolkit.view)
    .scan(toolkit.body).scan(toolkit.table)...;
// 复审 SubAgent：toolRegistry 仅 scan(toolkit.view)，绝不 scan 写工具。
tools.registerSubAgent("review_intent", "...")
    .toolRegistry(new ToolRegistry().scan(toolkit.view))  // ✓ 只读
    .systemPrompt(REVIEW_PROMPT).llm(llm).build();
// documentTools.saveCurrentDocument() 是普通方法（无 @ToolDef），由 AgentEvent.Complete 触发，dirty 即落盘。
```

## Convention: 保存不是 LLM 职责

`save_current_document` **不是** `@ToolDef`。它是 `DocumentTools` 的应用层方法，由 `AgentBridge` 在 `AgentEvent.Complete` 时调用。这从源头消灭"LLM 漏调 save / 谎报成功"，无需 SubAgent + 真相纠正编排层。一旦把 save 暴露为工具，"自述 ≠ 真相"的鸿沟立刻回归。

## Convention: 记忆污染治理（α 瘦身 + β note）

单 Agent 持久记忆窗口（`MessageWindowChatMemory`，按条数淘汰不按 token）会被写工具结果/复审结论撑爆。SubAgent 的"无记忆"曾是天然 GC 边界，回归后必须手动重建等价机制：

- **α 写工具瘦身**：写工具（insert/replace/delete body、table、修订等）的 `AfterToolCall` 把结果改写为一行确认（"✓ insert_paragraph：已插入"）。LLM 刚写完，不需回显全文。
- **β note 隔离**：`review_intent` 复审结论原文挂 `Message.note("review_report", 全文)`（`llmVisible=false`：不占窗口、不喂 LLM），紧凑摘要（`summarizeReview`：三态 + 截断 diff）作真工具结果喂 LLM。
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
