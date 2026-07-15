# demo 单 Agent 回归设计

## 目标

撤销主+SubAgent 双层拓扑，回归单 Agent。保存从 LLM 职责转为代码强制（`AgentEvent.Complete` flush），从源头消灭"自述 ≠ 真相"的鸿沟，删除整层真相弥合逻辑。

## 架构对比

```text
【现状】主 Agent + SubAgent
浏览器 POST /api/chat
  └─ PrimaryDocumentAgent (有记忆, 只读+委派)
       └─ invoke_subagent(task): AfterToolCall 钩子
            ├─ attemptFallbackSave   ← 编排兜底: SubAgent 漏 save 时替它存
            └─ correctSubAgentResult ← 编排兜底: 用 DocumentExecutionState 覆盖自述 JSON
                 └─ DocumentSubAgent (无记忆, 写工具+save_current_document)
                      └─ LLM 显式调 save_current_document
  └─ Complete → subagent_result 帧 (按 execution.saved 计算)

【回归后】单 Agent
浏览器 POST /api/chat
  └─ SingleDocumentAgent (有记忆, 读+写+check 全量, 无 save 工具)
       ├─ 写工具 AfterToolCall: 瘦身结果 + 置 dirty=true      ← α
       ├─ check_quality AfterToolCall: 紧凑摘要给 LLM, 原文挂 note ← α+β
       └─ BeforeToolCall (cancelRequested): block 写工具       ← ①
  └─ Complete (代码强制 flush):
       ├─ dirty? → 漏调 check 兜底 → 质检门控 → 落盘 OR reopen回滚
       └─ edit_outcome 帧 (按 dirty/saved/cancelled/failed 计算)  ← ②
```

## 边界与工具

### 单 Agent
- 保留会话记忆（`MessageWindowChatMemory`, 24 条）。
- 注册全部工具：`current_document` + `view_*` + 写工具 + `check_quality`。**不含** save。
- system prompt：咨询只调只读 `view_*`；明确编辑请求才调写工具（从现状 `invoke_subagent` prompt 平移，删除 Dispatcher/SubAgent 相关措辞）。

### 工具注册表
```java
ToolRegistry tools = new ToolRegistry()
    .scan(documentTools)      // current_document（原 CurrentDocumentTools 合并回）
    .scan(toolkit.view)
    .scan(toolkit.body)
    .scan(toolkit.table)
    .scan(toolkit.headerFooterToc)
    .scan(toolkit.trackedChangeQuery)
    .scan(toolkit.trackedChangeAuthoring)
    .scan(toolkit.qualityCheck);
// 不再 registerSubAgent；不再 scan DocumentSessionTools.saveCurrentDocument
```

### `dirty` 检测（替换 `execution.delegated`）
现状靠 `ToolStart("invoke_subagent")` 标记 `delegated`。回归后无 SubAgent，改为**写工具 AfterToolCall 置 `dirty=true`**。识别写工具的方式：按工具名集合判断（body/table/headerFooterToc/trackedChangeAuthoring 域的工具名白名单），或按"非 view/check/current_document 即写"的补集判断。推荐白名单（显式、抗新增只读工具误判）。

## 保存、质量和回滚（代码强制）

`Complete` flush 流程（`AgentBridge` 内新方法，如 `flushIfDirty()`）：

```text
on AgentEvent.Complete:
  if !dirty: return                          // 纯咨询，零落盘
  if cancelled: reopen; return               // 取消，丢弃内存改动
  if dirty && 无本轮质检报告:                  // 漏调 check 兜底
      run check_quality, 存 report
  if report 含 error:
      reopen; failed=true; return            // 质检 error，回滚不落盘
  saveCurrentDocument()                      // 复用 DocumentSessionTools 门控逻辑
  if save 成功: saved=true; bumpKey
  else: failed=true
```

- `saveCurrentDocument()` 的"质检 error 拦截 + 落盘"链路复用现状 `DocumentSessionTools` 实现，但**改为应用层直接调用**（不再是 `@ToolDef`），去掉与 `DocumentExecutionState`/`cancelRequested` 的耦合（状态改由 `AgentBridge` 的 flush 流程持有）。
- 质检 error 的"拒绝保存"语义不变；warning 的"允许保存 + 报告回传"不变。
- 回滚（`reopenCurrentDocument()`）覆盖：取消、质检 error、保存失败、`Complete` 时未落盘的 dirty 改动。

## 记忆污染治理（α + β）

**问题**：单 Agent 持久 24 条窗口，写工具结果/质检报告会撑爆（每轮编辑约 5 次工具调用 = ~10 条消息；token 不裁剪）。SubAgent 的"无记忆"是天然 GC 边界，回归后必须手动重建。

**α 结果瘦身（AfterToolCall）**：
- 写工具（insert/replace/delete body、table、修订等）：结果改写为一行确认。LLM 刚写完，不需要回显全文。例：原 `{"success":true,"data":{...大段...}}` → `"已插入 1 段（body_index=0）"`。
- `check_quality`：结果改写为紧凑摘要给 LLM（计数 + top N 问题），原文挂 note（见 β）。

**β note 隔离**：
- 质检报告原文挂 `Message.note("quality_report", 全文)`（`llmVisible=false`）：不占窗口、不喂 LLM，仅供 UI 重放/`edit_outcome` 帧引用。
- **约束**：LLM 要据之推理的内容（`view_body` 输出、要 acted-on 的质检摘要）**不能**用 note，必须是真 tool result。

**读工具**（`view_*`/`current_document`）：保持全量，是 LLM 推理的必要输入。若多轮后仍膨胀，记入后悔线。

## SSE 契约

保留：`trace`、`assistant`、`error`、`done`、`doc_changed`。

删除：`subagent_result`。

新增 `edit_outcome`（替代 `subagent_result`，由 `Complete` 时服务端状态计算）：
```json
{
  "type": "edit_outcome",
  "turnId": "turn-1",
  "status": "saved|rolled_back|cancelled|failed|noop",
  "changed": true,
  "qualityReport": "...摘要或note引用...",
  "error": ""
}
```
- `status` 由 `dirty/saved/cancelled/failed` 派生：
  - `!dirty` → `noop`
  - `cancelled` → `cancelled`
  - `failed`（质检 error / 保存失败）→ `failed`/`rolled_back`
  - `saved` → `saved`
- 前端 `app.js` reducer：`edit_outcome` 替代 `subagent_result` 分支；**以系统帧为准**渲染成败口径，agent 文本（`assistant`）作辅助。
- trace 不再有 `invoke_subagent` 的 `tool_start`；单 Agent 的写工具 `tool_start`/`tool_end` 直接进 trace（可观察性反而提升——SubAgent 内部事件原本被隐藏）。

## 取消真相（①+②）

单 Agent 文本流式、不可事后改写。取消竞态下 agent 可能流式输出"已完成"。应对：
- **①**：`BeforeToolCall` block 消息强指令"用户已取消，禁止声称已完成/已保存"（概率性改善）。
- **②**：`edit_outcome` 系统帧由服务端状态钉死 `cancelled`，前端以帧为准（确定性）。
- 这是 `correctSubAgentResult` 的合理下沉：从"事后改写工具结果 JSON"→"前端按系统帧渲染口径"。净简化（矩阵改写 → 一行发帧）。

## 文件改动清单

| 文件 | 动作 |
|---|---|
| `AgentBridge.java` | 重写：删 SubAgent 构建块、`afterInvokeSubAgent`/`attemptFallbackSave`/`correctSubAgentResult`/`extractJsonField`/`jsonQuote`；新增 `flushIfDirty`、写工具 AfterToolCall 瘦身+置 dirty、`Complete` 处理、`edit_outcome` 帧。 |
| `DocumentExecutionState.java` | 删除（或退化为 ≤3 字段 dirty/saved/failed 的简单状态，但首选删除，状态内联到 `AgentBridge` flush 局部）。 |
| `DocumentSessionTools.java` | `saveCurrentDocument` 改为应用层方法（去 `@ToolDef`/状态耦合），合并 `CurrentDocumentTools.currentDocument`；类改名 `DocumentTools`。 |
| `CurrentDocumentTools.java` | 删除（合并入 `DocumentTools`）。 |
| `AgentBridgeCorrectionTest.java` | 删除，补单 Agent 对等测试（见 implement）。 |
| `FallbackSaveTest.java` | 删除，补 `Complete` flush 对等测试。 |
| `DocumentSessionToolsTest.java` | 重写为 `DocumentToolsTest`（save 方法仍可单测，去 SubAgent 语义）。 |
| `VllmSubAgentIntegrationTest.java` | 改名 `VllmSingleAgentIntegrationTest`，断言改为单 Agent 直接调写工具。 |
| `app.js` | `reduceTimelineEvent`/`renderTimelineRun`：`subagent_result`→`edit_outcome`，trace 不依赖 `invoke_subagent`。 |
| `nondocx-demo/README.md` | 更新架构说明。 |
| `.trellis/spec/backend/agent-subagent.md` | 整篇重写为单 Agent 规格（改名/标题更新）。 |

## 风险与回滚

- **复杂度搬家风险**：α 瘦身 + flush 是新的应用层逻辑。验收靠"净代码量下降 + 真相弥合层清零"两道闸客观判定，不靠主观感觉。
- **记忆膨胀风险**：α+β 若扛不住连续编辑，触发后悔线。回滚方案：恢复 SubAgent（git revert 本任务），SubAgent 的无记忆边界是已验证的 GC。
- **取消竞态风险**：agent 文本偶尔与系统帧矛盾，前端以帧为准兜底。若频繁矛盾触发后悔线。
- **删除已验证逻辑**：`saveCurrentDocument` 的质检门控是经过测试的，回归时**只改调用方式（工具→方法），不改门控语义**，降低引入新 bug 的风险。
