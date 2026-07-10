# toolkit RouterAgent 多子代理落地执行计划

## 目标

把父任务拆成 5 个可独立验收的子任务，按“协议先于运行时、运行时先于专家、专家先于 demo”的顺序推进。

## 执行顺序

### 1. 子任务 1：foundation

目标：

- 定义协议层与核心类型

必须产出：

- `DocumentSnapshot`
- `ExpertPlan`
- `MergedPlan`
- `Operation`
- review 状态/原因枚举
- `ConflictKey`
- `sessionGeneration` 规则
- Router 状态机模型

完成标准：

- 数据模型、枚举、状态机边界稳定
- 关键模型有单元测试

### 2. 子任务 2：runtime

目标：

- 落地 orchestrator 与 coordinator 骨架

必须产出：

- `DocxOrchestrator`
- `RouterAgent`
- `ReadCoordinator`
- `CommitCoordinator`
- 子代理注册与 prompt 装配骨架
- 高层/低层 API 入口

完成标准：

- 能完成空跑或最小闭环
- 会话模型、状态机推进、debug 输出基本打通

### 3. 子任务 3：body-table

目标：

- 落地正文与表格专家

必须产出：

- `BodyAgent`
- `TableAgent`
- 对应工具映射和补读策略
- `BodyTools` / `TableTools` 操作到 plan 的映射

完成标准：

- 支持正文/表格典型修改闭环
- 支持冲突候选、review、commit

### 4. 子任务 4：specialists

目标：

- 落地修订、页眉页脚目录、质量专家

必须产出：

- `RevisionAgent`
- `HeaderTocAgent`
- `QualityAgent`
- 质量结果到 review 的直接映射

完成标准：

- 修订与质量类路径进入完整 review/commit 流

### 5. 子任务 5：demo-validation

目标：

- 接通 examples/demo/docs/tests

必须产出：

- 新入口 demo/example
- 文档更新
- 端到端验证

完成标准：

- 旧路径删除
- 新路径可运行、可测试、可说明

## 每个子任务的验证要求

- 编译通过
- 相关测试通过
- 新增模型有最小测试覆盖
- review / trace / summary 至少有一条黄金路径验证

## 建议验证命令

```bash
rtk mvn -q -DskipTests compile
rtk mvn -q test
rtk mvn -q -pl nondocx-toolkit -am test
rtk mvn -q -pl nondocx-examples -am test
rtk mvn -q -pl nondocx-demo -am test
```

## 风险点

- `DocxToolkit` 现有门面与新 orchestrator 的职责切割
- `ReadCoordinator` 对 live `Document` 只读限流的正确性
- `CommitCoordinator` 的非事务语义是否被调用方误解
- examples/demo 全量切新入口后的迁移成本

## 回退点

此任务已明确不保留旧实现运行时 fallback，因此“回退”只存在于开发阶段：

- 先停在父任务设计与子任务 PRD 完成态
- 先完成 foundation，再决定 runtime 落地细节
- 任一子任务发现协议层不稳，先回到父任务修设计，不直接硬写后续批次

## 启动建议

下一步先启动：

1. `07-09-toolkit-router-subagents-foundation`
2. `07-09-toolkit-router-subagents-runtime`

原因：

- 如果协议层没钉死，后面的专家 prompt 和 commit 语义都会反复返工
- 如果 runtime 骨架没立起来，领域专家子任务会缺少稳定宿主
