# 修订页眉目录质量子代理落地

## Goal

落地 `RevisionAgent`、`HeaderTocAgent`、`QualityAgent`，补齐剩余 toolkit 领域专家，并把质量结果直接映射到统一 review 模型。

## Requirements

- 为 `TrackedChangeQueryTools` / `TrackedChangeAuthoringTools` 定义专家 prompt 与 plan 映射。
- 为 `HeaderFooterTocTools` 定义读取与解释路径。
- 为 `QualityCheckTools` 定义质量审查与 `WARNED/BLOCKED` 直连映射。
- 对修订、质量和跨专家 review 场景补充规则。

## Acceptance Criteria

- [ ] `RevisionAgent` 能生成修订相关 `ExpertPlan` 并接入统一 review。
- [ ] `HeaderTocAgent` 能处理页眉页脚/目录读取与说明任务。
- [ ] `QualityAgent` 能把质量检查结果映射为 `WARNED(QUALITY_RISK)` 或 `BLOCKED(QUALITY_GATE_FAILED)`。
- [ ] 至少有一个质量阻断场景、一个 warning 可提交场景、一个修订相关 review 场景。

## Out of Scope

- 不负责 demo/UI 最终接线。
