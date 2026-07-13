# 主 Agent 协商边界修正

## 目标

防止主 Agent 在协商阶段判断写入工具是否可用或自行提出降级执行；工具级可行性只由授权后的工具组专家负责。

## 需求

- 主 Agent 的工具 registry 只暴露 `ViewTools`，不暴露全量能力清单。
- 系统 Prompt 明确：主 Agent 仅澄清/概括用户目标；需求清晰时必须请求实施授权，不得枚举、验证或否定写操作。
- 本文档开头插入居中 H1 标题等已明确编辑需求应返回 `requestAuthorization=true`。

## 验收标准

- [ ] 主 Agent 的 tools schema 不包含 `describe_capabilities`。
- [ ] 主 Agent 不再声称 `insert_heading` 或正文 H1 不可用。
- [ ] Maven 验证通过。
