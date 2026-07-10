# 正文与表格子代理落地

## Goal

落地 `BodyAgent` 与 `TableAgent`，把 `BodyTools` / `TableTools` 的读、补读、计划生成、review 和提交路径真正接到新 orchestrator 中。

## Requirements

- 为 `BodyTools` 定义专家 prompt、工具教程、失败恢复规则。
- 为 `TableTools` 定义专家 prompt、工具教程、失败恢复规则。
- 建立正文/表格常见操作到 `Operation` 的映射。
- 建立正文/表格补读策略与 `ConflictKey` 规则。
- 接入 review、`WARNED/BLOCKED/SKIPPED`、`operationId` 与来源映射。

## Acceptance Criteria

- [ ] `BodyAgent` 能对正文修改任务输出有效 `ExpertPlan`。
- [ ] `TableAgent` 能对表格修改任务输出有效 `ExpertPlan`。
- [ ] 正文与表格混合请求能生成 `MergedPlan` 并走统一 review。
- [ ] 至少有正文修改、表格修改、混合修改三类验证路径。
- [ ] 冲突、去重、跳过、warning 至少各覆盖一个可验证场景。

## Out of Scope

- 不覆盖修订、目录、质量领域专家。
