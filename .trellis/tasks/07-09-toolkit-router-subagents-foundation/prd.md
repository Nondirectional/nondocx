# RouterAgent 多子代理架构与协议基建

## Goal

为 RouterAgent 多子代理体系定义稳定的协议层、状态机和 review/plan 核心模型，作为后续 runtime、专家子代理和 demo 的共同地基。

## Requirements

- 定义 `DocumentSnapshot`、`ExpertPlan`、`MergedPlan`、`Operation` 等强类型模型。
- 定义 `schemaVersion` / `snapshotVersion` / `sessionGeneration` 等协议演进与一致性字段。
- 定义 review 状态、原因枚举、`ruleCode` 字段和 `ConflictKey` 语义。
- 定义 Router 状态机与提交优先级规则。
- 定义非事务提交语义、失败后 reopen 语义、单会话单文档语义。

## Acceptance Criteria

- [ ] 核心协议模型有明确字段定义与边界说明。
- [ ] review 状态、原因枚举、`ruleCode`、`operationId`、`mergedIntoOperationId` 规则确定。
- [ ] `DocumentSnapshot` 的内容层、基线元数据、`sessionGeneration` 规则确定。
- [ ] `MergedPlan` 固定优先级与 `BLOCKED/WARNED/SKIPPED` 处理语义确定。
- [ ] 这些模型足以支撑 runtime 和专家子代理实现，不再留阻塞性开放问题。

## Out of Scope

- 不实现具体 sub-agent 注册与 prompt 运行时。
- 不接通 examples/demo。
