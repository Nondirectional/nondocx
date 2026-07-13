# Demo 执行链路可视化: prompt/response 流式展示

## Goal

让 Demo 内部 Agent 的执行链路对用户可见：用户能实时看到 LLM 专家的 prompt（发给模型的完整输入）、response（模型逐字返回的正文）、thinking（模型的思考过程）。当前 Agent 只输出三个汇总进度帧（分析/计划/执行），中间环节完全是黑盒。

## Background

当前 Demo 一次请求的数据流（关键引用）：

```
浏览器 app.js fetch('POST /api/chat')
  → DemoServer (CHAT_LOCK 串行)
    → AgentBridge.runStream
      → DocxOrchestrator.run(convId, intent, phaseCallback)
        → RouterAgent 状态机 ANALYZE→PLAN→COMMIT
          └─ PLAN 阶段: LlmDocxExpert.plan() → callLlm() 阻塞式 llm.chat()
             返回 JSON operation → RouterAgent 合并 → COMMIT 执行
  ← SSE 帧: analyze / plan / commit / doc_changed / done
```

**可见性盲点**：
- 前端只收到 3 个汇总进度帧（`step`），plan 帧只给 operation 人话清单（`OperationDescriptor.describe` 生成），完全看不到 prompt、LLM 原始 response、thinking。
- `LlmDocxExpert.callLlm`（`LlmDocxExpert.java:242`）是阻塞式 `llm.chat()`，本会话已加的 prompt 日志只打到后端 log，前端不可见。
- nonchain 0.10.0 的 `LLM.streamChat(messages, Consumer<ChatChunk>)` 现成可用（`deltaContent/deltaThinking/deltaToolCalls` 均有），但整个 demo + toolkit 主代码零调用。
- 前端 `app.js:303` 的 `appendAssistantText` 已为流式 text delta 预留，但后端从不发 `type:text` 帧 —— 死代码。

## Requirements

### R1 · 混合呈现形态
- prompt 静态一次性展示（可查看/复制），不发流式。
- LLM response 流式逐字打字机呈现。
- thinking 同样流式逐字呈现，与 response 分开（前端 tab/折叠）。

### R2 · 改 toolkit 接口（非只动 demo）
- `ExpertAgent.plan` 原签名加 `Consumer<LlmTraceEvent>` 参数（可空），所有实现类签名必改。
- `RouterAgent.run` 与 `DocxOrchestrator.run` 增加 traceCallback 参数，透传到 expert。
- `PhaseCallback` 保持不动（仍阶段级，职责分开）。

### R3 · LlmTraceEvent 契约（4 种事件 + agentName）
- `onPrompt(agentName, prompt)` —— prompt 构造完成后立即发，一次性。
- `onContentDelta(agentName, delta)` —— `ChatChunk.deltaContent()` 逐块。
- `onThinkingDelta(agentName, delta)` —— `ChatChunk.deltaThinking()` 逐块。
- `onComplete(agentName, success, error, usage)` —— `ChatResult` 完成或异常。
- 所有事件携带 `agentName` 字段，为未来多专家场景预留分组。

### R4 · trace 帧与现有 plan step 帧共存
- trace 帧穿插在 PLAN 阶段**进行中**推送。
- PLAN 阶段**完成后**仍推现有汇总 `step` 帧（operation 人话清单）。
- 语义分层：trace = LLM 思考过程，step = 编排层翻译产物。

### R5 · 前端进度卡内折叠区
- 不改 `index.html` 顶层布局。
- 在现有 `progress-card` 内，对涉及 trace 的阶段（PLAN）加折叠区：prompt 只读文本块（可复制）+ response/thinking 双 tab。
- response 区域接 `appendAssistantText` 的逐字追加逻辑（复用死代码）。
- thinking 区域同样逐字追加，默认折叠。
- `complete` 帧带 error 时标红。

### R6 · 错误处理
- LLM 调用/流式/解析失败时，发 `onComplete(success=false, error=...)`。
- expert 仍返回空 plan（保持现有 catch 行为 `LlmDocxExpert.java:244-248`），不阻断流程。
- 前端折叠区显示错误标记，进度卡仍保留正常 step 帧。

## Out of Scope

- 不改 `PhaseCallback`（仍阶段级）。
- 不引入 nonchain `Agent.builder` / `ToolRegistry` / `ChainCallback`（demo 故意用的是编排层 + 裸 LLM，本次不改这个架构）。
- 不做独立调试面板/drawer（在进度卡内解决）。
- 不改 `DemoServer.java:56` 的硬编码 API key（既有问题，非本次范围）。
- 不动 thinking 默认开关等产品决策（架构层面先打通，toggle 后续再加）。

## Acceptance Criteria

- [ ] AC1：用户发一条对话，前端能在 PLAN 阶段实时看到 LLM 的 response 逐字出现（打字机效果）。
- [ ] AC2：前端能看到发送给 LLM 的完整 prompt（折叠区只读块，可复制）。
- [ ] AC3：前端能看到 LLM 的 thinking（与 response 分开的 tab，默认折叠，逐字流式）。
- [ ] AC4：PLAN 流式完成后，仍出现现有那个 operation 人话清单汇总 step 帧。
- [ ] AC5：LLM 调用失败时，前端折叠区显示错误标记，且后续流程不阻断（仍返回空 plan，进度卡正常）。
- [ ] AC6：`ExpertAgent.plan` 签名变更后，所有实现类编译通过，toolkit 测试通过。
- [ ] AC7：`PhaseCallback` 接口签名零改动。
- [ ] AC8：不改 `index.html`，只动 `app.js` + `style.css`。

## Notes

- `CHAT_LOCK`（`DemoServer.java:56`）全局 synchronized，trace 流式不增加并发风险；streamChat 回调在持锁线程内，单请求内安全。
- `LlmDocxExpert` 配置 `qwen3.7-plus` / `thinkingBudget(512)`，thinking 可能较长。
- prompt 注入了 body 顺序预览，完整 prompt 可能几 KB，前端折叠区需可滚动。
