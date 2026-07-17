# Design: 只读复审 SubAgent 实现意图达成度质检

> 对应 `prd.md`。技术设计：架构边界、数据契约、契约变更、前端适配、spec 修订、回滚/取舍。

## 1. 架构与边界

```
┌──────────────────────── AgentBridge (应用层) ─────────────────────────┐
│                                                                        │
│   主 Agent (Agent.builder)                                             │
│   工具集: current_document + view_* + 写工具(body/table/...)          │
│   ❌ 不再 scan(toolkit.qualityCheck)                                   │
│   ❌ 不含任何 save 工具                                                │
│      │                                                                 │
│      │ 写完文档后,主 Agent 调用 SubAgent 工具                          │
│      ▼                                                                 │
│   ┌─ review_intent (只读复审 SubAgent) ──────────────────────┐         │
│   │  registerSubAgent("review_intent", description)          │         │
│   │   .systemPrompt(复审指令)                                │         │
│   │   .toolRegistry(仅 view_*)          ← 关键:只读          │         │
│   │   .llm(主 Agent 同一 VLLM)           ← 复用,不引第二模型 │         │
│   │   .maxIterations(小,如 6)           ← 复审任务轻量      │         │
│   │   .contextSelector(本轮请求注入器)                       │         │
│   │                                                          │         │
│   │  → SubAgentResult.content() = 三态结论 + 差异            │         │
│   └──────────────────────────────────────────────────────────┘         │
│      │                                                                 │
│      ▼                                                                 │
│   afterToolCall: 复审结论原文挂 Message.note(不喂 LLM)                 │
│                  紧凑摘要作真 tool result 喂主 Agent                   │
│                                                                        │
│   Complete flush (应用层强制保存):                                      │
│     saveCurrentDocument(cancelled):                                    │
│       dirty → 直接落盘 (删除质检 error 门控)                           │
│       → edit_outcome.qualityReport = 复审结论三态+差异                  │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

**边界原则**：
- 复审 SubAgent **仅持有 `view_*`**（`toolkit.view`）。无写工具、无 save、无 current_document 写路径 → 物理上无法触发"漏调 save / 谎报成功"鸿沟。
- toolkit 的 `QualityCheckTools`（10 项规则）**代码原样保留**，demo 只是不再 scan 它。`view_issues` 视图链路不受影响。

## 2. 数据契约

### 2.1 复审 SubAgent 输入（contextSelector 注入）

`ContextSelector.select(history, newMsg, args)` 返回注入复审 SubAgent 的上下文消息。策略（MVP）：只注入本轮用户请求 + 复审指令，不带主 Agent 写作过程。

```java
// 伪代码:AgentBridge 构造时把本轮 message 存入一个 Supplier
private Supplier<String> currentTurnRequest;  // runStream 开头置位
ContextSelector selector = (history, newMsg, args) -> {
  String userRequest = currentTurnRequest.get();
  // 复审 SubAgent 自己通过 view_* 读文档现状;这里只给"要审什么"
  return List.of(Message.user("用户本轮修改请求:" + userRequest));
};
```

复审 SubAgent 自行调 `view_*`（如 `view_full`/`view_stats`）读文档现状——这是它判定"是否达成"的事实来源。

### 2.2 复审 SubAgent 输出（systemPrompt 约束）

systemPrompt 要求复审 SubAgent 输出固定前缀文本 + 自由差异说明:

```
<verdict>达成|部分达成|未达成</verdict>
<diff>
- 已完成: ...(列出用户请求中已落地的改动)
- 缺失/偏差: ...(列出未做或与请求不符的部分)
</diff>
```

verdict 三态供 afterToolCall 解析;diff 文本供前端展示。

### 2.3 edit_outcome 帧（向后兼容 + 新语义）

```js
// edit_outcome.status: noop | saved | rolled_back | cancelled (不变)
// edit_outcome.qualityReport: 复审结论串(新语义,不再是规则报告)
```

`status` 语义变化点:**复审未达成不再回滚**。复审判"未达成"仍 `status=saved`(dirty 即落盘)。
`rolled_back` 仅在保存失败(IO)或用户取消时出现——复审不触发回滚。

### 2.4 复审结论的结构化

为让前端复用现有 `parseStructuredToolOutput`(解析 ```json 块),复审结论在 afterToolCall 里可包成兼容结构。但三态(passed/warnings/errors)语义不匹配旧规则。

**MVP 决策**:不复用旧 `qualityReport` JSON 结构。前端新增复审结论解析/渲染,旧 `parseQualityReport`/`QUALITY_CHECK_LABELS` 替换。原因:三态+差异是全新语义,强行塞进旧 passed/warnings/errors 会误导。

## 3. 契约变更清单

| 文件 | 变更 |
|---|---|
| `AgentBridge` 构造 | `.scan(toolkit.qualityCheck)` → 改为 `tools.registerSubAgent("review_intent", ...)` 构建只读复审 SubAgent |
| `AgentBridge.runStream` | 捕获本轮 `message` 到 `currentTurnRequest` Supplier(供 contextSelector) |
| `AgentBridge.afterToolCall` | `"check_quality"` 分支 → `"review_intent"` 分支;原文挂 note + 摘要喂 LLM 逻辑保留,适配复审结论 |
| `AgentBridge.summarizeQuality` | 改为 `summarizeReview`:解析 verdict 三态,产紧凑摘要喂主 Agent |
| `AgentBridge` systemPrompt | "完成修改后调用 check_quality" → "完成修改后调用 review_intent 复审意图达成度" |
| `DocumentTools.saveCurrentDocument` | **删除**质检 error 门控 + runAllChecks;dirty 即落盘 |
| `DocumentTools.SaveOutcome` | 质检相关字段语义调整(qualityReport 改由 AgentBridge 的复审结论填充,非 save 内部产出) |
| `DocumentTools` import | 删除 `QualityCheckTools.CheckResult` 依赖 |
| `app.js` | `parseQualityReport`/`QUALITY_CHECK_LABELS`/`qualitySummary`/`renderQualityCheck` → 复审结论解析/渲染(verdict+diff) |

## 4. 前端适配

- `parseReviewReport(report)`: 解析 `<verdict>` + `<diff>` 文本块 → `{verdict, diff}`。
- `renderReviewReport`: 三态 badge(达成=绿/部分达成=黄/未达成=红)+ diff 文本区。
- `QUALITY_CHECK_LABELS`(10 项规则标签)删除。
- 执行卡的 quality 步骤文案:"质量检查" → "意图复审"。
- `MessageRenderingContractTest`: 更新对前端结构的断言(替换 `quality-stats`/`quality-checks` 相关断言)。

## 5. spec 修订(`agent-single.md`)

需修订的核心句:
> demo 使用**单 Agent**……**禁止恢复 SubAgent 委派层**、RouterAgent、Dispatcher、操作计划或提交协调器。

修订为区分"禁令边界":
- **仍禁**:SubAgent 执行编辑/保存(导致"漏调 save/谎报成功"真相鸿沟)、RouterAgent、Dispatcher、操作计划、提交协调器。
- **允许例外**:**只读复审 SubAgent**——仅持有 `view_*`,不持有写/保存工具,物理上无法触发真相鸿沟。用于意图达成度复审。

同步更新:
- §历史:补充"2026-07-17 引入只读复审 SubAgent 做意图复审;它与被禁的编辑/保存 SubAgent 本质不同"。
- §Contracts:工具集说明更新(主 Agent 不含规则 check_quality,改含 review_intent SubAgent)。
- §Validation 矩阵:复审未达成行(status=saved 不回滚)。

## 6. 测试策略

| 测试 | 调整 |
|---|---|
| `DocumentToolsTest` | 删除"质检 error 拒绝落盘"用例;新增"dirty 即落盘"用例 |
| `OutcomeFrameTest` | `deriveStatus` 矩阵更新:复审未达成不进 rolled_back |
| `DirtyDetectionTest` | `isReadonly("review_intent")` —— SubAgent 工具名是否算只读?(它不改文档,应只读,不置 dirty) |
| `VllmSingleAgentIntegrationTest` | systemPrompt 改 review_intent;断言改 |
| `SkillAgentLinkTest` | check_quality 相关 mock 改 review_intent |
| 新增 `ReviewSubAgentTest` | 断言复审 SubAgent 工具集仅含 view_*(不含写/保存);contextSelector 注入本轮请求;verdict 解析三态 |
| `MessageRenderingContractTest` | 前端结构断言更新 |

**关键不变量测试**:复审 SubAgent 的 ToolRegistry 不含任何写工具(反射/白名单断言)——这是 spec 允许例外的物理保证。

## 7. 取舍与风险

- **风险:LLM 复审不确定性**。复审判错(误报未达成)不拦截保存(软警告决策),影响可控——用户仍拿到落盘文档 + 复审结论可自行判断。这是选择软警告的根本理由。
- **风险:toolkit.qualityCheck 被 demo 遗弃但仍存在**。可接受:它是通用能力,view_issues 仍用。demo 不 scan 即不暴露。
- **取舍:不复用旧 qualityReport JSON 结构**。三态语义与 passed/warnings/errors 不匹配,强行复用误导。前端新增渲染,代价可接受。
- **取舍:复审结论用文本前缀(`<verdict>`)而非结构化 JSON**。MVP 简单;后续如需更强解析可升级到 JSON schema。systemPrompt 强约束前缀即可靠。

## 8. 回滚考虑

- 全部改动在 demo 应用层 + spec;toolkit 零改动。回滚 = 还原 demo 文件 + spec。
- 无数据迁移(运行期内存态,无持久化结构变更)。
