# demo-validation 设计

## 目标

把新 orchestrator 体系替换进 examples/demo，并完成端到端验证与文档说明。

## 边界

负责：

- examples/demo 新入口
- 文档更新
- 端到端验证
- 旧路径删除

不负责：

- 再回头保留旧实现 fallback

## 核心设计

### 1. 入口迁移

- examples 统一从旧单 Agent 切到 `DocxOrchestrator`
- demo 统一从旧桥接层切到新 orchestrator

### 2. 输出分层

高层：

- 摘要 + 精简操作清单

debug/低层：

- snapshot / plans / review / commit 详情

### 3. 验证场景

至少覆盖：

- 正文修改
- 表格修改
- 修订或质量触发
- warning 可提交
- blocked 整批停止
- 失败后 close + reopen 重新分析

## 风险

- 旧入口删除后，示例和文档必须同步到位
- demo 流式展示可能需要适配新的阶段产物

## 交付物

- 新 examples
- 新 demo
- 更新 README / docs
- 端到端验证脚本或测试
