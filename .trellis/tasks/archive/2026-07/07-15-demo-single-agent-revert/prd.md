# demo 单 Agent 回归：撤销 SubAgent，保存代码强制

## Goal

撤销 demo 的主 Agent + SubAgent 双层拓扑，回归单 Agent。动因不是"少一层 agent"，而是**消灭 SubAgent 模式引入的编排兜底复杂度**——`attemptFallbackSave` + `correctSubAgentResult` + `DocumentExecutionState` 这套"真相弥合"逻辑，本质是在补 SubAgent 自述（漏 save / 谎报成功）与磁盘真相之间的缝隙。

回归后，保存不再由 LLM 显式触发（消除"漏调 save / 谎报"的源头），改为 agent 主循环结束时由代码强制 flush。单 Agent 自己写自己存，服务端状态与 agent 行为天然对齐，真相弥合层整层删除。

## 背景事实（已从代码确认）

- `AgentBridge`（`nondocx-demo`）构建主 Agent + 一等 SubAgent 工具 `invoke_subagent`；SubAgent 无记忆、持有受限写工具 + 唯一保存入口 `save_current_document`。
- 编排兜底位于 `AgentBridge.afterInvokeSubAgent`（`AfterToolCall` 钩子）：`attemptFallbackSave`（SubAgent 漏 save 时替它存）+ `correctSubAgentResult`（用 `DocumentExecutionState` 覆盖 SubAgent 自述 JSON）。
- nonchain `Agent` 无专门的"循环结束" builder 钩子；唯一循环结束信号是 `run(msg, Consumer<AgentEvent>)` 流式重载里的 `AgentEvent.Complete`（`AgentBridge.tracePrimaryEvent` 已处理此分支）。`maxIterations` 耗尽（默认 `graceTurns=3`）也会发 `Complete`。
- `MessageWindowChatMemory` 按条数淘汰（当前 24 条），不按 token，大体积工具结果不裁剪。`Message.note(llmVisible=false)` 不占窗口、不喂 LLM，可用于给 UI 隔离大 payload。
- `DocumentSessionTools.saveCurrentDocument()` 的"质检 error 拦截 + 落盘 + 设状态"链路是健全的，回归后作为应用层方法复用，去掉与 `DocumentExecutionState`/`cancelRequested` 的耦合。
- SubAgent 模式是 2026-07-13（commit `dbae09f`）刚引入，07-14（`0e2bca7`）立刻补兜底——这是第三次拓扑翻转。

## Requirements

### R1 保存代码强制（核心）
- `save_current_document` **不再是 LLM 工具**，从工具注册表移除。单 Agent 不持有任何保存能力。
- 新增"循环结束 flush"：`AgentEvent.Complete` 时，若本轮发生过文档写入（`dirty=true`），由应用层强制运行质检 + 落盘，复用 `DocumentSessionTools` 的质检门控逻辑。
- 质检有 `error` → 代码 `reopenCurrentDocument()` 回滚，不落盘。
- 质检只有 `warning` → 允许落盘，报告随 SSE 帧回传。
- 漏调 `check_quality` 兜底：flush 前若 `dirty && 无本轮质检报告`，代码替 LLM 跑一次 `check_quality`。

### R2 质检仍是 LLM 反馈回路
- `check_quality` 仍是 LLM 显式调用的工具：LLM 据报告（warning/error）自行修复。
- 代码兜底仅在 LLM 漏调时触发，不替代 LLM 的质量自修。

### R3 取消：prompt 诚实 + SSE 系统帧
- 取消仍协作式：`BeforeToolCall` 拦截器在 `cancelRequested` 时 block 后续所有写工具。
- block 消息含强指令：禁止声称已完成/已保存。
- 删除 `correctSubAgentResult`（JSON 矩阵改写），真相纠正下沉为 SSE 独立系统帧。
- 新增 `edit_outcome` SSE 帧：`Complete` 时按 `dirty/saved/cancelled/failed` 计算，前端**以系统帧为准**渲染成败/取消口径，agent 文本仅作辅助。

### R4 记忆污染治理
- 写工具（body/table/header-footer-toc/tracked-change-authoring）的 `AfterToolCall` 把结果瘦身为一行确认（如"已插入 3 段"）。
- `check_quality` 结果改写为紧凑摘要（计数 + top 问题）给 LLM；质检报告原文挂 `Message.note(llmVisible=false)` 给 UI 重放。
- 读工具（`view_*`/`current_document`）保持全量——LLM 据之推理，是必要成本。

### R5 意图识别纯 prompt 驱动
- 单 Agent 的 system prompt 承担"咨询只读 / 明确编辑才调写工具"的判定，从现状 `invoke_subagent` 的 prompt 平移。
- 不引入编辑模式开关或确认步骤（符合 demo"无授权 UI"产品定位）。

### R6 工具注册表扁平全量
- 单 Agent 注册全部工具：`current_document` + `view_*` + 写工具 + `check_quality`（**不含** save）。
- `CurrentDocumentTools` 为隔离 `save_current_document` 而独立成类的理由消失，合并回 `DocumentSessionTools`（后者改名为职责更准的类，如 `DocumentTools`）。删除 `CurrentDocumentTools.java`。

### R7 清理与文档
- 删除 `DocumentExecutionState.java`（整文件，或退化为 ≤3 字段的脏标记结构）。
- 删除 `correctSubAgentResult` / `attemptFallbackSave` / SubAgent 委派链路 / SubAgent 取消 before-interceptor。
- 删除 `subagent_result` SSE 帧，新增 `edit_outcome` 帧。
- 更新 `.trellis/spec/backend/agent-subagent.md`（整篇重写为单 Agent 规格）。
- 更新 `nondocx-demo/README.md`、前端 `app.js`、相关 Javadoc。

## Acceptance Criteria

### 质量闸门（合并前全部满足）
- [ ] **净代码量下降**：删除 SubAgent 委派 + 真相弥合 + `DocumentExecutionState` 的行数 > 新增 flush/瘦身/note/edit_outcome 的行数。
- [ ] **真相弥合层清零**：`rg -n "correctSubAgentResult|attemptFallbackSave|subagent_result|invoke_subagent|DocumentExecutionState" nondocx-demo/src` 无业务命中（仅注释/历史可接受，理想为零）。
- [ ] **删旧测试前先补对等测试**：删除 `AgentBridgeCorrectionTest`/`FallbackSaveTest` 前，先补单 Agent 下的对等测试——`Complete` flush、漏调 check 兜底、质检 error 回滚、取消 SSE 系统帧。

### 行为等价
- [ ] 明确编辑请求：单 Agent 直接调写工具 → `Complete` flush 落盘 → `doc_changed` → OnlyOffice 刷新。
- [ ] 质检 `error`：`Complete` 时回滚不落盘，`edit_outcome.success=false`。
- [ ] 质检仅 `warning`：落盘，`edit_outcome` 带 `qualityReport`。
- [ ] 取消：`BeforeToolCall` block 写工具，`Complete` 时回滚，`edit_outcome` 标 cancelled。
- [ ] 咨询请求：单 Agent 只调只读 `view_*`，不触发 flush（`dirty=false`）。
- [ ] 连续 2 轮编辑后，24 条窗口不因写结果膨胀到 LLM 拒收（α+β 生效）。
- [ ] `mvn -q -Dtest='!VllmSubAgentIntegrationTest' test`（单 Agent 集成测试改名后同样排除）通过。

## 后悔线（出现即判定回归失败，考虑再撤回 SubAgent）

- 单 Agent 连续 2 轮编辑后上下文膨胀到 LLM 拒收 / 质量显著下降 → α+β 没扛住，SubAgent 无记忆边界不可替代。
- 取消时用户频繁看到 agent 文本与 SSE 系统帧矛盾 → prompt 诚实 + 系统帧没兜住文本不可改写竞态。

## Out of Scope

- toolkit 层（`nondocx-toolkit`）工具本身不变——它们是无状态文档操作，与 Agent 拓扑无关。
- nonchain 框架（`/Users/non/Projects/nonchain`）不改——复用现有 `AgentEvent.Complete`/`AfterToolCall`/`Message.note`。
- 不引入 `TokenWindowChatMemory`（Qwen token 估算偏差 + 不治条数淘汰问题）。
- 生产 DashScope 配置不变。
- 不恢复旧编排层（RouterAgent/Dispatcher 等，已于 `dbae09f` 删除）。
