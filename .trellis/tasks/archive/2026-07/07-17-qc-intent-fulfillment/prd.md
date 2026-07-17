# 质检目标改为复审用户期望修改是否达成

## Goal

把 Demo 中"质量检查"的目标从**客观版式/兼容性合规**（当前 10 项规则式检查）改为**复审文件是否已经完成用户本轮期望的修改**（意图达成度审查）。复审由一个**只读复审 SubAgent** 承担，返回三态结论 + 差异说明，作为诊断信号随 `edit_outcome` 回传（不拦截保存）。

## 现状（已通过代码确认）

- `QualityCheckTools.checkQuality`（位于 `nondocx-toolkit` 通用包）：10 项确定性规则检查（行距/标题/字体/空白页/表格分页/图片越界/底纹/TOC/整洁度）。
- **关键约束**：这 10 项是 **toolkit 通用能力**，被多处复用——`DocumentViewService.issues()`、`ViewTools`（`view_issues` 工具）、`IssuesView`/`IssueEntry`/`QualitySummary` DTO、toolkit 测试。**不能删**。
- 质检的两个消费方（均在 demo）：
  1. **Agent 自查工具** `check_quality`：`AgentBridge.afterToolCall` 原文挂 `Message.note`，紧凑摘要喂 LLM。
  2. **保存门控**：`DocumentTools.saveCurrentDocument`（应用层、非工具）在 `AgentEvent.Complete` flush 时跑全量质检，有 error 拒绝落盘。
- 前端 `app.js`：`QUALITY_CHECK_LABELS`（10 项标签）、`qualitySummary`、`renderQualityCheck`、`parseQualityReport`。
- Skill：`audit-quality.md`（场景：检查文档质量/解释问题/授权修复）。
- 测试：`QualityCheckToolsTest`（toolkit，10 项规则）、`DocumentToolsTest`（门控）、`OutcomeFrameTest`、`DirtyDetectionTest`、`VllmSingleAgentIntegrationTest`、`SkillAgentLinkTest`。

## 核心张力与化解

| 张力 | 化解 |
|---|---|
| 意图达成度是语义判断，无法用规则确定 | 用 LLM（只读复审 SubAgent）判定 |
| 10 项规则是 toolkit 通用能力，不能删 | 保留 toolkit 不动；在 demo 应用层新建复审能力 |
| spec `agent-single.md` 禁止 SubAgent | 修订 spec：禁的是"SubAgent 执行编辑+保存"导致真相鸿沟；**只读复审 SubAgent 是允许的例外**（不持有写/保存工具，无法触发鸿沟） |
| 复审结论不该硬拦截合法编辑 | 复审判"未达成"不拦截保存，改为诊断信号回传 |

## 已决（产品决策）

1. **判定主体：LLM 复审**（只读复审 SubAgent）。
2. **现有 10 项规则检查：demo 侧完全替换**——demo 不再 scan `toolkit.qualityCheck`（不向 Agent 暴露规则 `check_quality`），改为只读复审 SubAgent。toolkit 的规则代码原样保留（通用能力，供 view_issues 等复用）。
3. **复审形态：只读 SubAgent**。主 Agent 写完后调用复审 SubAgent；该 SubAgent 仅持有 `view_*` 只读工具 + 复审 systemPrompt，不持有写工具、不接触保存，因此不触发 spec 所禁的真相鸿沟。
4. **未达进门控：软警告（不拦截）**。复审判"未达成"仍落盘，复审结论随 `edit_outcome.qualityReport` 回传。**删除**现有"质检 error 拒绝落盘"门控——`saveCurrentDocument` 简化为：dirty 即落盘，不再跑质检门控。
5. **结论形态：三态 + 差异说明**——达成 / 部分达成 / 未达成 + 文字说明差异（哪些要求做了、哪些没做、偏差在哪）。

## 技术约束（已通过代码确认）

- `ToolRegistry.registerSubAgent(name, description)` → `SubAgentRegistration` 构建只读复审 SubAgent。
- `SubAgentDefinition(name, description, systemPrompt, toolRegistry, llmOverride, maxIterations, contextSelector, beforeInterceptors, afterInterceptors)`：给复审 SubAgent 一个**仅含 `view_*`** 的 ToolRegistry + 复审 systemPrompt。
- `ContextSelector.select(history, newMsg, args) → List<Message>`：用于把「本轮用户请求」注入复审 SubAgent 上下文。
- `AgentBridge` 已持有 `llm`、`memory`、本轮 `message`（`runStream` 入参），可透传给复审 SubAgent。
- `SubAgentResult.content()/status()` 返回复审文本。
- `AgentEvent.SubAgentStarted/Completed/Failed/Spawned` 等事件可用于 SSE trace。

## Requirements

### 功能需求

- F1: demo 不再向主 Agent 注册 `toolkit.qualityCheck`（规则 `check_quality` 工具从 Agent 工具集移除）。
- F2: 新建只读复审 SubAgent（如 `review_intent`），主 Agent 写完文档后调用它。
- F3: 复审 SubAgent 持有：仅 `view_*` 只读工具 + 复审 systemPrompt（对比本轮用户请求与文档现状，输出三态结论 + 差异）。
- F4: 复审 SubAgent 不持有任何写工具、不持有任何保存能力。
- F5: 复审结论（三态 + 差异）作为本轮质量信号，通过 `edit_outcome.qualityReport` 回传前端。
- F6: 复审判"未达成"**不**拦截保存——`saveCurrentDocument` 不再跑质检门控，dirty 即落盘。
- F7: 主 Agent systemPrompt 更新：把"完成修改后调用 check_quality 检查质量"改为"完成修改后调用 review_intent 复审意图达成度"。

### 契约变更

- C1: `AgentBridge` 的 ToolRegistry 从 `.scan(toolkit.qualityCheck)` 改为注册只读复审 SubAgent。
- C2: `afterToolCall` 中 `check_quality` 分支改为复审 SubAgent 调用分支；记忆污染治理（note 隔离 + 摘要）保留，适配复审结论。
- C3: `saveCurrentDocument` 删除"质检 error 拒绝落盘"逻辑，简化为 dirty 即落盘。`SaveOutcome` 的质检相关字段语义随之调整。
- C4: 漏调兜底机制：既然不再硬门控，"dirty 但漏调 check → 自动补跑质检"逻辑删除（无 error 可拦）。
- C5: 修订 `.trellis/spec/backend/agent-single.md`：明确"只读复审 SubAgent 是允许的例外"，记录禁令的真实边界（禁的是执行编辑+保存的 SubAgent 委派层）。

### 前端

- F8: `app.js` 的 `QUALITY_CHECK_LABELS`（10 项规则标签）移除/替换为复审结论展示（三态 + 差异文本）；`qualitySummary`/`renderQualityCheck` 适配复审结论结构。

### Skill

- F9: `audit-quality.md` 更新：场景从"解释版式问题清单"改为"复审用户期望修改的达成度"。

## Acceptance Criteria

- [x] demo 主 Agent 工具集不再含规则 `check_quality`；toolkit 的规则代码与 `view_issues` 视图/DTO/测试不受影响（toolkit 187 测试全绿）。
- [x] 主 Agent 写完文档后，只读复审 SubAgent 被调用，返回三态结论（达成/部分达成/未达成）+ 差异说明。
- [x] 复审 SubAgent 工具集仅含只读工具（`ReviewSubAgentTest` 断言不含写/保存工具）。
- [x] 复审判"未达成"时文档**仍落盘**，复审结论随 `edit_outcome.qualityReport` 回传，`status` 仍为 `saved`（不回滚）。
- [x] 纯咨询轮次（dirty=false）不触发复审 SubAgent，`edit_outcome.status=noop`。
- [x] 前端展示复审三态结论 + 差异文本，不再展示 10 项规则标签。
- [x] spec `agent-single.md` 更新，明确只读复审 SubAgent 的允许边界。
- [x] 受影响测试更新通过：`DocumentToolsTest`（门控简化）、`OutcomeFrameTest`、`DirtyDetectionTest`、`VllmSingleAgentIntegrationTest`、`SkillAgentLinkTest`。

## Out of Scope

- 修改 nondocx-toolkit 的 10 项规则检查实现或其 view_issues 复用链。
- 引入用户确认交互（复审由 LLM 完成，不打断流式）。
- 恢复 SubAgent 执行编辑/保存的双层架构（spec 仍禁）。
- 复审结论的结构化 JSON 输出（本轮先用自由文本三态 + 差异；结构化留待后续）。

## Open Questions

无（全部已决）。

### 已决补充

- **复审 SubAgent 的 LLM：复用主 Agent 的 `VLLM`**（`http://10.100.10.21:40002/v1`, `qwen3-14b`），MVP 不引入第二模型。
- **`contextSelector` 取「本轮请求」策略：只取本轮 user message + 文档现状**。复审 SubAgent 通过 `contextSelector` 注入本轮用户请求，并通过 `view_*` 只读工具读取当前文档内容；不带主 Agent 的写作过程摘要。
