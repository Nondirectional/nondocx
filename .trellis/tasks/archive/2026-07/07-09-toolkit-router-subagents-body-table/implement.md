# body-table 执行计划

## 顺序

1. 为 `BodyTools` 建专家工具清单与 prompt
2. 为 `TableTools` 建专家工具清单与 prompt
3. 实现正文 operation 映射
4. 实现表格 operation 映射
5. 接补读路径
6. 接 review / merge / commit
7. 补混合场景测试

## 验证

```bash
rtk mvn -q -pl nondocx-toolkit -am test
rtk mvn -q -pl nondocx-examples -am test
```

## 验收前检查

- 正文单独修改闭环
- 表格单独修改闭环
- 正文+表格混合请求闭环
- 至少一例 warning、一例 skipped、一例 conflict 候选
