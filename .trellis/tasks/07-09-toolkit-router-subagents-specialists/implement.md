# specialists 执行计划

## 顺序

1. 落 `RevisionAgent`
2. 落 `HeaderTocAgent`
3. 落 `QualityAgent`
4. 接 review 状态与原因枚举
5. 补修订/质量阻断与 warning 测试

## 验证

```bash
rtk mvn -q -pl nondocx-toolkit -am test
rtk mvn -q -pl nondocx-examples -am test
```

## 验收前检查

- 修订能进入统一 `ExpertPlan`
- 质量能直接生成 `WARNED/BLOCKED`
- 至少一条质量阻断闭环
- 至少一条修订 review 闭环
