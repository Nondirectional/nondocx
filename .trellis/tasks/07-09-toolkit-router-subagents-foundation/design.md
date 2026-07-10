# foundation 设计

## 目标

把父任务已经确认的协议与状态语义，收敛成可实现的强类型模型与稳定契约。

## 边界

本子任务只负责“定义”，不负责“运行”：

- 定义 `DocumentSnapshot`
- 定义 `ExpertPlan` / `MergedPlan`
- 定义 `Operation`
- 定义 review / reason / ruleCode / `ConflictKey`
- 定义 Router 状态机和值对象

不负责：

- nonchain 子代理注册
- 具体 toolkit 工具调用
- examples/demo 接线

## 关键设计

### 1. Snapshot 协议

`DocumentSnapshot` 是系统共享事实层。

第一版必须包含：

- `snapshotVersion`
- `conversationId`
- `sourcePath`
- `createdAt`
- `sourceLastModified`
- `sessionGeneration`
- overview / paragraph preview / table preview / revision summary / quality summary

第一版不包含：

- run 全量明细
- `docFingerprint`

### 2. Plan 协议

`ExpertPlan` 和 `MergedPlan` 都必须带：

- `schemaVersion`
- 顶层 id
- conversation 绑定
- operation 列表

`MergedPlan` 额外带：

- 来源 `ExpertPlan` 引用
- 合并与排序结果

### 3. Operation 协议

每条 operation 必须有：

- `operationId`
- `toolGroup`
- `kind`
- `targetRef`
- `payload`
- `conflictKey`
- `intent`
- `reason`
- `riskNote`
- review 结果

### 4. Review 协议

状态：

- `APPROVED`
- `WARNED`
- `BLOCKED`
- `SKIPPED`

原因枚举：

- `SKIPPED`: `DUPLICATE_MERGED` / `SUPERSEDED` / `OUT_OF_SCOPE` / `LOW_CONFIDENCE` / `CONFLICT_DROPPED`
- `WARNED`: `QUALITY_RISK` / `LOW_CONFIDENCE` / `POTENTIAL_CONFLICT` / `PARTIAL_CONTEXT`
- `BLOCKED`: `UNSAFE_CONFLICT` / `MISSING_REQUIRED_CONTEXT` / `OUT_OF_SCOPE` / `QUALITY_GATE_FAILED`

review 结果统一带：

- `reviewStatus`
- `reviewReason`
- `ruleCode`
- `explanation`

### 5. 状态机协议

Router 状态机第一版：

- `ANALYZE`
- `PLAN`
- `REVIEW`
- `COMMIT`
- `DONE`
- `FAILED`

`REVIEW` 是条件触发，不是强制总走。

## 风险

- 协议字段命名如果不稳，后续 runtime 和专家子任务会返工
- review 状态与原因枚举过大，会拖慢第一版
- `ConflictKey` 若定义太弱，后续冲突处理会失真

## 交付物

- 协议设计文档
- 强类型类骨架设计
- 枚举设计
- 测试断言点清单
