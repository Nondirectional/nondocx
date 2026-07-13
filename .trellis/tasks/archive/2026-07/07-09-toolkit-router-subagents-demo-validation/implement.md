# demo-validation 执行计划

## 顺序

1. 替换 examples 入口
2. 替换 demo 入口
3. 补高层摘要展示
4. 补 debug/低层示例
5. 删除旧单 Agent 路径
6. 更新文档
7. 跑端到端验证

## 验证

```bash
rtk mvn -q -pl nondocx-examples -am test
rtk mvn -q -pl nondocx-demo -am test
rtk mvn -q test
```

## 验收前检查

- examples 和 demo 都走新入口
- 旧单 Agent 主入口已删
- 文档说明会话模型、review 模型、失败后 reopen 语义
- 至少一条正文/表格/质量混合端到端路径通过
