# 专家流式操作解析修复

## 目标

确保 LLM 专家在流式输出已产生有效 JSON、但最终 `ChatResult.content()` 为空或不完整时，仍可解析并执行操作。

## 验收标准

- [ ] 累积 `content_delta` 并作为操作 JSON 的优先解析来源。
- [ ] 最终文本仅作回退；保留完整性日志。
- [ ] `insert_heading` 等流式 JSON 不再被误判为空计划。
- [ ] Maven 验证通过。
