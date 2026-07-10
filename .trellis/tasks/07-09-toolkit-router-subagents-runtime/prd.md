# RouterAgent 与子代理注册骨架

## Goal

在协议层已确定的前提下，落地 `DocxOrchestrator`、`RouterAgent`、`ReadCoordinator`、`CommitCoordinator` 和子代理注册骨架，形成最小可运行编排宿主。

## Requirements

- 新增高层 facade，如 `DocxOrchestrator`。
- 落地高层 `run/chat` 与低层 `analyze/plan/commit` API。
- 落地显式会话标识、单会话单文档约束与切文档新会话规则。
- 落地 Router 显式状态机、分阶段派发骨架。
- 落地 `ReadCoordinator(per-doc=1, global=4)` 与 `CommitCoordinator` 非事务提交骨架。
- 落地子代理注册与 prompt 装配入口，但不要求一次做全领域专家细节。

## Acceptance Criteria

- [ ] `DocxOrchestrator` API 可编译、可被调用。
- [ ] 会话标识对外显式可见，`docId` 不外露。
- [ ] Router 状态机、阶段推进、debug 产物基本打通。
- [ ] `ReadCoordinator` 与 `CommitCoordinator` 具备最小可运行骨架。
- [ ] 支持接入至少一个占位专家，跑通最小 analyze/plan/commit 流程。

## Out of Scope

- 不要求完成所有 toolkit 工具组专家化。
- 不要求接通 demo UI。
