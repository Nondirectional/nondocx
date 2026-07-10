# runtime 执行计划

## 顺序

1. 新建 orchestration 入口包
2. 落 `DocxOrchestrator` API
3. 落会话对象与 `conversationId/sessionId` 规则
4. 落 Router 状态机骨架
5. 落 `ReadCoordinator`
6. 落 `CommitCoordinator`
7. 接一个占位专家跑通最小 analyze/plan/commit

## 验证

```bash
rtk mvn -q -pl nondocx-toolkit -am test
rtk mvn -q -DskipTests compile
```

## 验收前检查

- 高层 API 默认只回摘要
- 低层或 debug 能拿全量阶段产物
- `docId` 未泄露到对外 API
- 单会话单文档约束已落地
