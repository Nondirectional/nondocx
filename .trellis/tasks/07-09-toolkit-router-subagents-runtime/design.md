# runtime 设计

## 目标

基于 foundation 协议，落地最小可运行编排宿主。

## 边界

负责：

- `DocxOrchestrator`
- `RouterAgent`
- `ReadCoordinator`
- `CommitCoordinator`
- 子代理注册骨架

不负责：

- 全量专家 prompt 细化
- demo UI 接线

## 核心设计

### 1. `DocxOrchestrator`

对外 facade，暴露：

- 高层：`run(...)` / `chat(...)`
- 低层：`analyze(...)` / `plan(...)` / `commit(...)`

对外显式暴露：

- `conversationId`
- `sessionId`

不暴露：

- `docId`

### 2. 会话模型

第一版强约束：

- 单会话单文档
- 切文档必须新会话
- close/reopen 后 `sessionGeneration++`

### 3. `ReadCoordinator`

职责：

- 子代理补读统一入口
- 快照基线校验
- 并发限流

参数：

- `per-doc = 1`
- `global = 4`

### 4. `CommitCoordinator`

职责：

- 接收 `MergedPlan`
- 按固定优先级提交
- 非事务、遇错即停

失败策略：

- 返回已执行步骤和失败点
- Router 后续必须 close + reopen

### 5. 子代理骨架

先只要求：

- registry 装配
- prompt 模板入口
- 与 Router 的请求/响应契约

## 风险

- facade 和 toolkit 职责切割不清
- 会话 id / memory / doc session 三层关系容易混
- debug 产物返回面太大

## 交付物

- 可编译的 facade
- 状态机推进骨架
- 读/写 coordinator 骨架
- 一个占位专家的最小闭环
