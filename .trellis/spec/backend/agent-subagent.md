# Agent + SubAgent 文档实施

> `nondocx-toolkit` 只提供工具与共享文档会话；Agent 拓扑属于应用层。禁止恢复 RouterAgent、Dispatcher、操作计划或提交协调器。

## Scenario: 当前文档的 SubAgent 实施

### 1. Scope / Trigger

demo 或应用需要根据自然语言直接编辑当前 docx 时，主 Agent 必须经 nonchain 一等 SubAgent 工具委派实施。主 Agent 只负责咨询、读取和委派；SubAgent 是唯一实施者。

### 2. Signatures

```java
ToolRegistry childTools = new ToolRegistry()
    .scan(currentDocumentTools)
    .scan(toolkit.view)
    .scan(toolkit.body)
    .scan(toolkit.table)
    .scan(toolkit.headerFooterToc)
    .scan(toolkit.trackedChangeQuery)
    .scan(toolkit.trackedChangeAuthoring)
    .scan(toolkit.qualityCheck);

primaryTools.registerSubAgent("invoke_subagent", "实施当前文档编辑任务")
    .systemPrompt("...")
    .toolRegistry(childTools)
    .maxIterations(12)
    .build();
```

- `POST /api/chat`：`{"message":"..."}`；明确编辑请求在此请求中完成。
- `POST /api/cancel`：协作式取消当前实施。
- `GET /api/trace`：返回 JSONL trace。
- 不存在 `/api/execute`；没有授权 token 或独立提交端点。

### 3. Contracts

- 主 Agent registry 仅有 `view_*` 和 `invoke_subagent`。它不能注册写工具。
- SubAgent 无跨轮 memory。先调用 `current_document` 取得当前 `docId`，不能猜测路径。
- SubAgent registry 不得含 `open_docx`、`close_docx`、`save_docx` 或能力枚举工具。
- `save_current_document()` 是唯一保存入口，输出路径由服务端提供，不出现在模型工具参数中。
- SubAgent 最终文本是 JSON：`success`、`summary`、`changed`、`qualityReport`、`error`。
- SSE 使用 `subagent_result` 返回同名状态；`doc_changed` 仅在服务端保存成功后发送。
- `trace` 至少保留 `invoke_subagent` 的 `tool_start` 和 `tool_end`，JSONL 落盘前剔除密钥字段。

### 4. Validation & Error Matrix

| 条件 | 服务端行为 |
|---|---|
| 主 Agent 未调用 SubAgent | 只返回咨询文本，零写入 |
| SubAgent 工具失败或任务结束但未保存 | close + reopen 当前磁盘文档，丢弃内存改动 |
| 收到取消 | before-interceptor 阻止后续工具与保存，随后 reopen |
| 质量检查有 `error` | `save_current_document` 失败，零保存 |
| 质量检查只有 `warning` | 保存，报告回传 `qualityReport` |
| 保存成功 | 设置 `changed=true`，发送 `doc_changed` |

### 5. Good / Base / Bad Cases

- Good：用户请求“在文档开头添加居中标题”，主 Agent 调 `invoke_subagent`；子代理以 `insert_paragraph(body_index=0, heading_level=H1, alignment=CENTER)` 写入并保存。
- Base：用户询问文档有几段，主 Agent 只调用 `view_stats`。
- Bad：主 Agent 直接扫描 `BodyTools` 或通过第二个 HTTP 端点执行写入。

### 6. Tests Required

- 单元测试：`save_current_document` 保存带警告的文档；取消拒绝保存；重新打开后未保存改动不存在。
- 集成测试：真实 VLLM 主 Agent 触发 `invoke_subagent`，临时 docx 的第一段为“项目周汇报”且居中。
- SSE 测试：保存成功才出现 `subagent_result.success=true` 和 `doc_changed`。
- 工具 schema 测试：主 Agent 无写工具，SubAgent 无 `open_docx`/`close_docx`/`save_docx`。

### 7. Wrong vs Correct

```java
// Wrong：主 Agent 直接写文档，或者用计划/分派层间接写。
ToolRegistry primaryTools = toolkit.scanAll(new ToolRegistry());

// Correct：写工具仅归属短生命周期 SubAgent。
ToolRegistry primaryTools = new ToolRegistry().scan(toolkit.view);
primaryTools.registerSubAgent("invoke_subagent", "实施当前文档编辑任务")
    .toolRegistry(childTools)
    .build();
```

## Convention: 插入标题使用 `insert_paragraph`

`BodyTools.insertParagraph` 的每项允许可选 `heading_level`（H1-H6）和 `alignment`（LEFT/CENTER/RIGHT/JUSTIFY）。需要在开头创建居中标题时，使用单个 payload，避免先插入再二次按索引修改：

```json
{
  "body_index": 0,
  "text": "项目周汇报",
  "heading_level": "H1",
  "alignment": "CENTER"
}
```

`body_index` 是正文和表格交错的 body 顺序索引，0 表示首个 body 元素之前。工具结果保持 `ToolResult` 双段格式；消费者用 `ToolResultParser` 读取 `success`，不得嗅探中文错误前缀。

## Gotcha: VLLM 工具调用解析

`VLLM` provider 通过 `chat_template_kwargs.enable_thinking` 传递思考开关，并从 `reasoning` 字段读取思考文本。真实集成测试必须禁用思考并验证 `ToolStart("invoke_subagent")`。

若响应是 `finish_reason=tool_calls` 但 `tool_calls=[]`，问题在 VLLM 服务的 tool-call parser 配置，客户端不得伪造工具调用或把集成测试改为跳过；测试应失败并显示此诊断。
