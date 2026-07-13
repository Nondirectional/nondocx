# 多子代理端到端 demo 与验证

## Goal

把新的 RouterAgent 多子代理体系接入 examples/demo，对外替换旧入口，并补齐端到端验证、文档与迁移说明。

## Requirements

- 移除旧单 Agent 直连全部工具路径。
- examples/demo 统一迁移到 `DocxOrchestrator` 新入口。
- 高层摘要、debug 模式、低层 API 至少各有一条示例路径。
- 补充端到端测试、文档说明、失败恢复说明。

## Acceptance Criteria

- [ ] examples 使用新入口可运行。
- [ ] demo 使用新入口可运行。
- [ ] 文档明确说明新架构、会话模型、失败后 reopen 语义。
- [ ] 至少有一条正文/表格/质量告警混合的端到端验证路径。
- [ ] 旧单 Agent 路径已删除，不再作为主入口存在。

## Out of Scope

- 不再为旧入口提供 runtime fallback。
