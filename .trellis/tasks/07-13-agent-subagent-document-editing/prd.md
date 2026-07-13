# 主 Agent 通过 SubAgent 编辑文档

## Goal

移除 RouterAgent/Dispatcher 编排。demo 的主 Agent 通过工具调用无状态 SubAgent，直接完成当前 docx 的编辑、质量检查与保存，并将实施结果回传主 Agent。

## Requirements

- 主 Agent 区分咨询与编辑：咨询仅使用只读视图工具；明确编辑请求立即调用 `invoke_subagent`，不再要求按钮授权。
- 使用 nonchain 一等 SubAgent 工具注册。SubAgent 是实际实施者，直接调用受限文档工具，不使用 Dispatcher、RouterAgent、专家分派、操作计划或提交协调器。
- SubAgent 每次任务无跨轮记忆。工具只暴露当前文档读取、编辑、质量检查与“保存当前文档”；不得打开、关闭或切换任意路径文档。
- SubAgent 最终结果契约包含 `success`、`summary`、`changed`、`qualityReport`、`error`；不暴露保存路径。主 Agent 只能依据该结果答复。
- 质量检查错误阻止保存，警告允许保存并回传报告。取消或保存前失败时，重开磁盘文档丢弃内存中的部分修改。
- 删除 demo 的授权 UI、`/api/execute`、旧 Dispatch/专家/计划/提交 trace 和 `LlmDocxExpert`。SSE trace 展示 `invoke_subagent` 调用及返回结果；保留取消接口。
- 删除 toolkit 的 RouterAgent 及整套旧编排 API、相关示例、测试和文档。语义视图需要的快照能力迁入 `view` 域，不再属于 `orchestration`。
- 更新 demo README 与 toolkit 文档，描述 Agent+SubAgent 方式；生产 demo 继续使用现有 DashScope 配置。
- `nondocx-demo` 添加默认运行的 VLLM 真实集成测试，使用 `http://10.100.10.21:40002/v1` 的 `qwen3-14b`，关闭思考模式。测试在 `@TempDir` 文档上验证“在文档开头添加居中标题：项目周汇报”。VLLM 不可达时测试失败，连接超时 30 秒。

## Acceptance Criteria

- [ ] 主 Agent 的工具 schema 有 `invoke_subagent`，编辑请求在 `/api/chat` 内直接触发该工具。
- [ ] SubAgent 只能操作当前文档；不含 `open_docx`、`close_docx` 或任意输出路径保存工具。
- [ ] 成功实施后 SubAgent 保存文档，SSE 发送结果和 `doc_changed`；OnlyOffice 刷新。
- [ ] 质量错误、取消、保存失败均不保留未保存的内存修改；质量警告可保存。
- [ ] demo 不再包含 `/api/execute`、授权按钮、Dispatch、`LlmDocxExpert` 或 `OperationDescriptor`。
- [ ] toolkit 和 examples 不再导出或引用 RouterAgent 及旧编排 API；语义视图仍可编译和运行。
- [ ] VLLM 集成测试通过后，第一段文本为“项目周汇报”且段落居中。
- [ ] `mvn -q verify` 通过；默认测试中 VLLM 服务不可达时明确失败。

## Notes

- 当前工作区已有未提交的 demo 链路修改，后续实施需在本任务中审查并完成。
