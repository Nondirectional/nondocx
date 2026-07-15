# 实施计划

> 原则：先补对等测试骨架（保证行为不丢），再重构主流程，最后删旧逻辑。每步可单独编译验证。

## 0. 起点

- 工作树干净（`git status` clean）。当前 `main` 已含 SubAgent + 兜底（`0e2bca7`）。
- 任务：`.trellis/tasks/07-15-demo-single-agent-revert/`。

## 1. 重构会话工具层（低风险，先做）

- [ ] 新建 `DocumentTools.java`：合并 `CurrentDocumentTools.currentDocument()` + `DocumentSessionTools` 的 save 质检门控逻辑。`saveCurrentDocument()` 改为**普通方法**（去 `@ToolDef`），去 `DocumentExecutionState`/`cancelRequested` 耦合，只接受必要的输入（docId/outputPath supplier），返回 save 结果 + 质检报告。
- [ ] 删除 `CurrentDocumentTools.java`。
- [ ] 删除 `DocumentSessionTools.java`（逻辑已迁入 `DocumentTools`）。
- [ ] 删除 `DocumentExecutionState.java`。
- [ ] `AgentBridge` 暂保留字段引用编译通过（此步可先留 TODO 占位，步骤 2 再彻底改）。
- [ ] 验证：`mvn -q -pl nondocx-demo -am -DskipTests compile`

## 2. 重写 AgentBridge 核心

### 2.1 Agent 构建（删 SubAgent，建单 Agent）
- [ ] 删除 `SUB_AGENT_TOOL` 常量、SubAgent 构建块（`registerSubAgent` + systemPrompt + childTools + before-interceptor）、`primaryTools` vs `childTools` 分离。
- [ ] 单 Agent 注册扁平全量工具表（见 design 的 `ToolRegistry`）。
- [ ] 单 Agent system prompt：从现状主 Agent prompt 平移，删 Dispatcher/SubAgent/invoke_subagent 措辞，改为"明确编辑请求才调写工具，完成后调 check_quality；没有保存工具，保存由系统自动完成"。
- [ ] 单 Agent `addBeforeToolCall`：`cancelRequested` 时 block 写工具（白名单识别），block 消息含"禁止声称已完成"强指令。
- [ ] 单 Agent `addAfterToolCall`：写工具（白名单）→ 置 `dirty=true` + 结果瘦身（α）；`check_quality` → 紧凑摘要 + 原文挂 `Message.note`（β）。
- [ ] `maxIterations`：单 Agent 承担原主+Sub 双层工作，从 8 适度上调（如 20-30，需实测）。

### 2.2 flush 流程（替换 afterInvokeSubAgent）
- [ ] 删除 `afterInvokeSubAgent`/`attemptFallbackSave`/`correctSubAgentResult`/`extractJsonField`/`jsonQuote`。
- [ ] `tracePrimaryEvent` 的 `AgentEvent.Complete` 分支：触发 `flushIfDirty(turnId, session)`。
- [ ] 新增 `flushIfDirty`：按 design 流程（dirty 检测 → 漏 check 兜底 → 质检 error 回滚 → 落盘 → 设 saved/failed）。
- [ ] flush 结果生成 `edit_outcome` SSE 帧（替换 `subagent_result`），`status` 派生见 design。
- [ ] `doc_changed` 仅在 `saved` 时发（语义不变）。

### 2.3 状态字段
- [ ] 删除 `activeExecution`（`DocumentExecutionState`）。
- [ ] 新增本轮局部状态：`dirty`(AtomicBoolean)、`hasQualityReport`(AtomicBoolean)、`saved`/`failed`/`cancelled`——每轮 `runStream` 开头重置。
- [ ] 验证：`mvn -q -pl nondocx-demo -am -DskipTests compile`

## 3. 前端适配

- [ ] `app.js` `reduceTimelineEvent`：`subagent_result` 分支 → `edit_outcome`（字段 status/changed/qualityReport/error）。
- [ ] `renderTimelineRun`：状态标签映射更新（saved/rolled_back/cancelled/failed/noop）；trace 渲染移除对 `invoke_subagent` 的特殊判断，单 Agent 写工具直接显示。
- [ ] 以 `edit_outcome` 系统帧为成败口径渲染权威区，`assistant` 文本作辅助。
- [ ] 验证：手查 app.js 无 `subagent_result`/`invoke_subagent` 残留。

## 4. 测试（先补后删）

### 4.1 重写保留测试
- [ ] `DocumentSessionToolsTest` → `DocumentToolsTest`：save 方法单测（质检通过落盘、质检 error 拒绝、warning 允许），去 SubAgent/state 语义，直接断言方法返回 + 磁盘内容。

### 4.2 补单 Agent 对等测试（新增）
- [ ] `CompleteFlushTest`：模拟 dirty → flush 落盘（对应原 `FallbackSaveTest.fallbackSavesWhenSubAgentSkippedSave` 的对等：本轮改了文档未显式 save，`Complete` 时代码强制落盘成功）。
- [ ] `FlushRollbackTest`：质检 error → flush 回滚不落盘；取消 → flush 回滚。
- [ ] `DirtyDetectionTest`：写工具 AfterToolCall 置 dirty；只读工具不置 dirty；漏调 check 时 flush 兜底跑 check。
- [ ] `OutcomeFrameTest`：`edit_outcome` 帧 status 派生矩阵（noop/saved/rolled_back/cancelled/failed）。

### 4.3 删除过时测试
- [ ] 删除 `AgentBridgeCorrectionTest`（真相纠正已不存在）。
- [ ] 删除 `FallbackSaveTest`（被 `CompleteFlushTest` 替代）。

### 4.4 集成测试
- [ ] `VllmSubAgentIntegrationTest` → `VllmSingleAgentIntegrationTest`：断言改为单 Agent 直接调写工具（不再断言 `invoke_subagent`），仍验证"在文档开头添加居中标题：项目周汇报"。VLLM 配置不变。

## 5. 文档与 spec

- [ ] 重写 `.trellis/spec/backend/agent-subagent.md`：整篇改为单 Agent 规格（Signatures/Contracts/Error Matrix/Wrong vs Correct 全部按单 Agent 重写）。文件改名或标题更新为 `agent-single.md`（视项目惯例）。
- [ ] 更新 `nondocx-demo/README.md` 架构图与说明。
- [ ] 更新相关 Javadoc（`AgentBridge`/`DocumentTools`）。

## 6. 验证（质量闸门）

- [ ] `mvn -q -pl nondocx-demo -am -Dtest='!VllmSingleAgentIntegrationTest' test`
- [ ] `mvn -q -pl nondocx-demo -am -Dtest=VllmSingleAgentIntegrationTest test`（内网 VLLM 可达时）
- [ ] `rg -n "correctSubAgentResult|attemptFallbackSave|subagent_result|invoke_subagent|DocumentExecutionState|CurrentDocumentTools" nondocx-demo/src` → 期望零业务命中（真相弥合层清零闸）。
- [ ] 净代码量：`git diff --stat` 确认删除行 > 新增行（净负闸）。
- [ ] 手动：启动 demo，输入标题编辑任务，确认 SSE `edit_outcome`/`doc_changed`/OnlyOffice 刷新/取消行为。
- [ ] 连续 2 轮编辑后观察 trace，确认窗口未膨胀拒收（后悔线巡检）。

## 回滚点

- 步骤 1-2 完成编译通过后，保留一次可单独验证的工作树状态（`git stash` 或分支检查点）。
- `saveCurrentDocument` 的质检门控是已验证逻辑——**只改调用方式不改门控语义**，降低回归风险。
- 若后悔线触发（记忆膨胀/取消竞态频发），`git revert` 本任务恢复 SubAgent。
