# 专家思考流操作回退

## 目标

当 provider 将合法 operation JSON 放在 `thinking_delta` 而非 content 时，专家仍能安全解析操作。

## 验收

- [ ] 优先 response；仅 thinking 含完整合法 operations JSON 时回退采用。
- [ ] Maven 验证通过。
