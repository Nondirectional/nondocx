# 优化 demo Agent 对话消息渲染体验

## Goal

把当前「暴露内部技术标识、信息层次混乱」的 Agent 对话渲染，优化为「面向用户、信息层次清晰、人话描述」的体验。

## 用户痛点（当前实际效果）

添加标题场景，用户看到的是：

1. `✅ 编排` —— 裸文字，无上下文
2. `📋 计划 1 项操作：✓ body/insert_heading@heading:1` —— 内部技术标识（toolGroup/kind/targetRef），用户看不懂
3. `完成 1 项操作。\n(执行 1)` —— 机械模板串，且统计与上文重复

根本问题：
- 后端把 Operation 的内部标识（shortLabel = `body/insert_heading@heading:1`）直接暴露，而非 Operation.intent 里已有的人话（「插入大标题」）
- 帧的信息层次混乱——plan 帧推技术清单，summary 帧推机械统计，没有统一叙事
- summaryText 是模板串（「完成 N 项操作」），不是面向用户的自然语言总结

## 已确认事实

### 后端帧结构（AgentBridge 推送）

当前一轮对话推送的帧序列（DONE 场景）：
1. `plan` 帧：{state, reviewTriggered, operations:[{operationId, shortLabel, status}]}
2. `tool_end` 帧：{name:"save_docx", result:"已保存到..."}
3. `doc_changed` 帧：{key}
4. `summary` 帧：{summaryText, executedCount, warnedCount, skippedCount, blockedCount}
5. `done` 帧

FAILED 场景额外推 `error` 帧而非 save/tool_end/doc_changed。

### 可用但未暴露的数据

Operation 对象上有这些字段当前没传给前端：
- `intent()` —— LLM 产出的人话意图（如「插入大标题」）
- `reason()` —— 操作理由
- `payload()` —— 含 text/heading_level/alignment 等具体内容
- `riskNote()` —— 风险说明

RunSummary 上有：
- `operations()` —— 精简操作清单（含 operationId/status/shortLabel/reason）

### 前端渲染结构（app.js）

- `.msg.assistant` —— 灰底气泡，承载文字
- `.tool-call` —— 黄底灰条，承载操作清单（当前用这个渲染 plan）
- `.tool-result` —— tool-call 内的子项
- `.msg.error` —— 红底错误条

### CSS 约束

当前没有可折叠/卡片/进度等组件样式，只有气泡 + 灰条两种容器。

## Requirements

## Decisions

- 2026-07-10：渲染基调选择「分步进度 + 人话描述」。把编排过程拆成可读的分步卡片（分析→计划→提交），每步用人话描述而非 `body/insert_heading@heading:1` 这种技术标识。保留过程可见性（展示 RouterAgent 编排能力），但用人话替代技术术语。
- 2026-07-10：分步时机选择「实时流式」。后端在 ANALYZE/PLAN/COMMIT 每个阶段完成时立即推帧，前端逐步渲染出「正在分析... → 正在生成计划... → 正在执行... → 完成」。需要改 AgentBridge 的推送逻辑，从整轮推 2 帧改为分阶段推多个帧。
- 2026-07-10：operation 描述来源选择「后端规则映射生成」。在 AgentBridge（或独立描述生成器）里建 kind → 人话映射表，从 payload 提取具体内容拼成描述（如 `insert_heading` + payload.text/heading_level/alignment → 「插入 H1 标题『项目周报』，居中」）。描述质量可控、不依赖 LLM 二次调用，但需维护映射表。
- 2026-07-10：卡片形态选择「嵌入式进度卡」。一轮对话 = 一张卡片（带圆角容器），内部分步用「图标 + 文字 + 状态」竖向排列，已完成步骤打勾，当前步骤转圈，计划步骤展开操作清单。视觉上「一轮对话 = 一张进度卡」。

## Requirements

- 分步展示编排过程：分析→计划→提交，每步有可读的人话描述
- 分步是实时流式的——每个阶段完成时立即推帧，前端逐步渲染
- operation 描述用后端规则映射从 kind + payload 生成人话，不暴露 `body/insert_heading@heading:1`
- 成功/失败状态清晰可辨
- 一轮对话渲染为单张嵌入式进度卡（不是多个气泡）
- FAILED 时卡片显示失败步骤 + 原因，不 save

## Acceptance Criteria

- [ ] 用户发消息后，前端逐步出现进度卡（分析→计划→提交），而非瞬间弹出全部
- [ ] operation 描述是人话（如「插入 H1 标题『项目周报』，居中」），不含 `body/insert_heading@heading:1` 这种技术标识
- [ ] 进度卡内已完成步骤打勾，当前步骤有进行中指示
- [ ] DONE 后卡片显示完成 + 文档刷新（OO reload）
- [ ] FAILED 后卡片显示失败步骤 + 人话原因，不 save
- [ ] 不再出现裸统计串（如「(执行 1)」）和「✅ 编排」这种无上下文的单行

## Out of Scope

- 不改变后端编排逻辑（RouterAgent/CommitCoordinator 等不变）
- 不引入前端框架（保持 vanilla JS）
- 不增加新的编排阶段产物（不改协议层）
