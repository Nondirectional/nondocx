# toolkit RouterAgent 多子代理体系设计

## 1. 目标

把当前“单 Agent + 全量工具直连”的 toolkit 使用方式，重构为一套显式编排系统：

- 高层入口：`DocxOrchestrator`
- 调度核心：`RouterAgent`
- 只读补充：`ReadCoordinator`
- 唯一写入口：`CommitCoordinator`
- 领域专家：按 toolkit 工具组拆分的子代理

设计目标不是追求最大并发，而是先建立**可解释、可追踪、可验证、可扩展**的安全编排边界。

## 2. 第一性原理

### 2.1 问题本质

当前痛点不是“工具不够多”，而是：

- 工具太多，单 Agent prompt 负担过重
- 不同领域工具的使用教程和失败恢复规则混在一起
- 多轮编辑时，缺少稳定的计划层、审查层和提交层
- 如果直接引入多子代理并发写 live `Document`，风险过高

### 2.2 不可违背的真相

- `.docx` / `XWPFDocument` 是活对象模型，不是事务型文档数据库
- 当前 toolkit 会话状态共享 `HashMap<String, Document>`，线程安全边界很弱
- 多子代理并发写同一 `docId` 高风险，不应作为第一版目标
- 读、计划、审查、提交是不同职责，不应混成一个 Agent prompt

### 2.3 结论

必须引入协议层与编排层，把“子代理擅长的事”和“系统必须强管的事”拆开：

- 子代理擅长：解释领域工具、读取局部上下文、产出计划
- 系统必须强管：会话生命周期、快照基线、冲突检测、review、唯一提交

## 3. 总体架构

```text
调用方
  ↓
DocxOrchestrator
  ↓
RouterAgent
  ├─ SnapshotBuilder
  ├─ ReadCoordinator
  ├─ Expert SubAgents
  │   ├─ BodyAgent
  │   ├─ TableAgent
  │   ├─ RevisionAgent
  │   ├─ HeaderTocAgent
  │   └─ QualityAgent
  ├─ Review Engine
  └─ CommitCoordinator
        ↓
     DocxToolkit
        ↓
     nondocx-core / Apache POI
```

## 4. 分层职责

### 4.1 `DocxOrchestrator`

对外高层 facade。

职责：

- 暴露 `run(...)` / `chat(...)`
- 暴露 `analyze(...)` / `plan(...)` / `commit(...)`
- 显式管理 `conversationId/sessionId`
- 维护单会话单文档约束
- 为调用方返回高层摘要或 debug 产物

不负责：

- 直接操作 toolkit 工具
- 承担子代理 prompt 细节
- 决定具体写入顺序

### 4.2 `RouterAgent`

编排中枢。

职责：

- 理解用户意图
- 基于 `DocumentSnapshot` 做粗分流
- 分阶段派发必要专家
- 接收 `ExpertPlan`
- 合并为 `MergedPlan`
- 触发 `REVIEW`
- 在无 `BLOCKED` 时调用 `CommitCoordinator`

状态机：

- `ANALYZE`
- `PLAN`
- `REVIEW`
- `COMMIT`
- `DONE`
- `FAILED`

### 4.3 `ReadCoordinator`

只读补充通道。

职责：

- 为子代理提供按需补读
- 对 live `Document` 的只读访问进行限流
- 校验快照基线是否仍然有效

第一版并发规则：

- `per-doc = 1`
- `global = 4`

### 4.4 `CommitCoordinator`

唯一写入口。

职责：

- 接收强类型 `MergedPlan`
- 按固定优先级顺序执行
- 收集执行结果
- 遇错即停
- 把失败点与已执行步骤返回给 RouterAgent

第一版明确不负责：

- 自动回滚
- 部分失败后的在线修补

### 4.5 领域专家子代理

第一版固定按工具组拆：

- `BodyAgent`
- `TableAgent`
- `RevisionAgent`
- `HeaderTocAgent`
- `QualityAgent`

职责：

- 读取基础快照
- 必要时经 `ReadCoordinator` 补读
- 输出本工具组的 JSON `ExpertPlan`
- 给出 explanation 与风险提示

限制：

- 不直接写 live `Document`
- 不决定保存时机
- 不产出跨工具组复合操作

## 5. 关键数据模型

### 5.1 `DocumentSnapshot`

作用：系统共享事实层。

顶层字段：

- `snapshotVersion = 1`
- `conversationId`
- `sourcePath`
- `createdAt`
- `sourceLastModified`
- `sessionGeneration`

内容字段第一版至少包括：

- overview
- paragraph 索引与短预览
- table 尺寸与单元格预览
- tracked changes 摘要
- header/footer/toc 存在性
- quality 风险摘要

第一版不默认包含：

- run 级明细
- `docFingerprint`

### 5.2 `ExpertPlan`

子代理输出，先为 JSON，再转为 Java 强类型。

顶层字段：

- `schemaVersion = 1`
- `agentName`
- `planId`
- `conversationId`
- `snapshotVersion`
- `sessionGeneration`
- `operations[]`

### 5.3 `MergedPlan`

RouterAgent 合并后的统一执行视图。

顶层字段：

- `schemaVersion = 1`
- `conversationId`
- `mergedPlanId`
- `sourceExpertPlans[]`
- `operations[]`

### 5.4 `Operation`

字段第一版至少包括：

- `operationId`
- `toolGroup`
- `kind`
- `targetRef`
- `payload`
- `conflictKey`
- `intent`
- `reason`
- `riskNote`
- `reviewStatus`
- `reviewReason`
- `ruleCode`
- `mergedIntoOperationId`（仅跳过场景）

## 6. 冲突与 review

### 6.1 冲突策略

采用分层冲突检测：

1. 先按粗粒度 `ConflictKey` 找候选冲突
2. 再按操作类型/字段级意图判断是否可合并

### 6.2 Review 状态

- `APPROVED`
- `WARNED`
- `BLOCKED`
- `SKIPPED`

规则：

- 存在任一 `BLOCKED`：整批不进 commit
- `WARNED`：允许提交，但必须显式暴露
- `SKIPPED`：必须保留原因和来源链

### 6.3 原因枚举

`SKIPPED` 至少包括：

- `DUPLICATE_MERGED`
- `SUPERSEDED`
- `OUT_OF_SCOPE`
- `LOW_CONFIDENCE`
- `CONFLICT_DROPPED`

`WARNED` 至少包括：

- `QUALITY_RISK`
- `LOW_CONFIDENCE`
- `POTENTIAL_CONFLICT`
- `PARTIAL_CONTEXT`

`BLOCKED` 至少包括：

- `UNSAFE_CONFLICT`
- `MISSING_REQUIRED_CONTEXT`
- `OUT_OF_SCOPE`
- `QUALITY_GATE_FAILED`

## 7. 提交语义

第一版为显式非事务模型：

- 固定优先级排序执行
- 遇错即停
- 不自动回滚
- 失败后必须 close + reopen
- 不在半修改内存态上继续补丁式修复

执行优先级：

1. 结构变更
2. 文本/样式变更
3. 修订相关操作
4. 质量复查
5. 保存前检查

保存/关闭属于 coordinator 生命周期动作，不进入专家 plan 排序。

## 8. 会话模型

### 8.1 外部会话

对外显式暴露：

- `conversationId`
- `sessionId`

不暴露：

- `docId`

### 8.2 单会话单文档

第一版约束：

- 一份 memory 只服务一份活跃文档
- 切换到另一份文档必须开启新会话

## 9. API 设计方向

高层：

- `run(...)`
- `chat(...)`

低层：

- `analyze(...)`
- `plan(...)`
- `commit(...)`

高层返回：

- 默认只回高层摘要
- 摘要带自然语言总结 + 精简操作清单 + 统计

低层/debug 返回：

- `DocumentSnapshot`
- `ExpertPlans`
- `MergedPlan`
- review 结果
- commit 结果

## 10. 风险与边界

### 10.1 明知接受的风险

- 第一版无事务回滚
- 第一版无多文档会话
- 第一版不做 live 文档并发写
- 第一版不做 `docFingerprint`

### 10.2 必须坚守的边界

- 子代理不直写
- 写只有一个入口
- `BLOCKED` 整批停
- 失败后必须重开会话/文档基线

## 11. 演进方向

后续可演进但第一版不做：

- `docFingerprint`
- 多文档会话
- 更细粒度 run 级基础快照
- 更智能的动态排序
- 硬质量闸门
- 外部持久化/回放协议
