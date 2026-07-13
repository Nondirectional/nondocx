# Agent + SubAgent 文档实施设计

## 目标

以 nonchain 的一等 SubAgent 工具替换 RouterAgent、Dispatch、专家计划和显式提交链路。编辑意图在一次 `/api/chat` SSE 请求内完成，主 Agent 只负责判断与委派，SubAgent 负责实施。

## 架构

```text
浏览器
  POST /api/chat
      |
PrimaryDocumentAgent
  |- view_*：咨询、读取
  `- invoke_subagent(task)：明确编辑
          |
          v
    DocumentSubAgent（每次无状态）
      |- current_document
      |- view / body / table / header-footer / tracked-change / quality 工具
      `- save_current_document
          |
          v
     DocxToolkit 当前 docId -> current.docx -> SSE subagent_result/doc_changed
```

`invoke_subagent` 由 `ToolRegistry.registerSubAgent` 注册，前台同步执行。主 Agent 的工具事件就是 SubAgent 调用的可观察边界；SubAgent 内部工具事件不泄漏给主 Agent trace。

## 边界与工具

### 主 Agent

- 保留会话记忆和 `view_*` 只读工具。
- 普通咨询不委派；明确编辑立即调用 `invoke_subagent(task)`。
- 不能直接写文档，也不能承诺未由子代理确认的实施结果。

### SubAgent

- 无 memory，每次委派独立运行。
- 首先调用 `current_document` 获取服务端注入的当前 `docId`。
- 可调用文档读取、写入、修订和质量检查工具；工具注册表不含 `open_docx`、`close_docx`、`save_docx` 或能力枚举工具。
- `save_current_document` 是唯一保存入口，输出路径由服务端持有，不传给模型。
- 最终回复约定为 JSON：`success`、`summary`、`changed`、`qualityReport`、`error`。

## 保存、质量和回滚

`DocumentSessionTools` 保存前运行全量质量检查。

- 有 `error` 级问题：拒绝保存，状态标记失败。
- 仅 `warning`：允许保存，报告写入结果。
- 取消、子代理工具失败、未保存结束或主 Agent 异常：关闭当前 `docId` 并从 `current.docx` 重新打开，清除内存中的部分修改。
- 保存成功：保留修改，调用 `DocSession.bumpKey()`，发送 `doc_changed` 供 OnlyOffice 重建预览。

取消是协作式的：下一次工具调用被 before-interceptor 拦截；正在执行的单次工具调用可以结束，但之后不能保存。

## SSE 契约

保留 `trace`、`assistant`、`error`、`done`、`doc_changed`。删除 `authorization_required`、`dispatch`、`review`、`commit`、`quality`、`blocked` 和 `rolled_back` 旧编排事件。

新增 `subagent_result`：

```json
{
  "type": "subagent_result",
  "turnId": "turn-1",
  "success": true,
  "changed": true,
  "qualityReport": "...",
  "error": ""
}
```

前端不再渲染授权按钮或 DispatchPlan；trace 显示 `invoke_subagent` 的工具开始与结果。

## 旧编排层移除

删除 `nondocx-toolkit` 的 RouterAgent、DocxOrchestrator、专家、执行器、计划、审查和提交协调器，以及对应测试和 `DocxOrchestratorExample`。语义视图目前复用的快照 DTO 与构建器迁到 `com.non.docx.toolkit.view`（或其 `snapshot` 子包），保持只读视图行为，移除所有 `orchestration` 包引用。

`ToolTestSupport` 改为直接使用 `ToolResultParser`，不再依赖旧 `ToolResultChecks`。

## 真实模型测试

在 `nondocx-demo` 添加默认 JUnit 集成测试：

- `VLLM("http://10.100.10.21:40002/v1", "qwen3-14b")`
- 禁用 thinking；连接超时 30 秒；服务不可达即测试失败。
- 在 `@TempDir` 新建 docx，主 Agent 委派“在文档开头添加居中标题，内容为项目周汇报”。
- 保存并用 nondocx/POI 重新读取，断言第一段文本和 `CENTER` 对齐。

生产 demo 继续使用现有 DashScope 初始化方式；VLLM 仅存在于测试代码。

## 风险与回滚

- 真实 LLM 工具调用存在非确定性：固定提示词、温度和断言任务；失败必须保留 trace。
- 默认测试依赖内网 VLLM，离线开发会失败；这是用户明确要求的健康门槛。
- 删除公开编排 API 是破坏性变更。回滚只可恢复本任务删除的旧类与文档；已保存的 demo 文档不受代码回滚影响。
