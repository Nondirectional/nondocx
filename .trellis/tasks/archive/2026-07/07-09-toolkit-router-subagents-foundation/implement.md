# foundation 执行计划

## 顺序

1. 定义协议包与文件布局
2. 实现 `DocumentSnapshot` 及其子结构
3. 实现 `ExpertPlan` / `MergedPlan` / `Operation`
4. 实现 review 状态、原因枚举、`ruleCode` 容器
5. 实现 `ConflictKey` 与 Router 状态机模型
6. 为关键模型补单测

## 重点文件

- 新 orchestration 协议包
- 可能新增的 model / enum / state 包
- 与 toolkit 交界的类型转换点

## 验证

```bash
rtk mvn -q -pl nondocx-toolkit -am test
rtk mvn -q -DskipTests compile
```

## 验收前检查

- 模型字段和父任务 PRD 一致
- 无阻塞性开放问题
- `schemaVersion=1` 与 `snapshotVersion=1` 固定
- `sessionGeneration` 规则写清楚
