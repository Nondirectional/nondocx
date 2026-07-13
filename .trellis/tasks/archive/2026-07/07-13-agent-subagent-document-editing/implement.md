# 实施计划

## 1. 固化 demo 子代理边界

- [x] 审查并完善 `DocumentExecutionState`、`DocumentSessionTools`。
- [x] 用 `ToolRegistry.registerSubAgent("invoke_subagent", ...)` 构建无状态 SubAgent。
- [x] 主 Agent 仅注册视图工具和 SubAgent；子代理注册受限文档工具与服务端保存工具。
- [x] 使用 before-interceptor 实现协作式取消；质量错误、失败和未保存结束时 reopen 磁盘基线。
- [x] 确保保存成功后才 `bumpKey()`。

## 2. 清理 demo 旧流程

- [x] 删除 `LlmDocxExpert`、`OperationDescriptor`、授权 token、Dispatch 与计划提交代码。
- [x] 删除 `/api/execute`，保留 `/api/cancel` 和 `/api/trace`。
- [x] 更新 `app.js`：删除授权与 Dispatch UI，展示 `subagent_result` 和 `invoke_subagent` trace，聊天期间提供取消按钮。
- [x] 更新 `nondocx-demo/README.md` 与 Javadoc。

## 3. 删除 toolkit 编排层

- [x] 删除 `orchestration` 中的 Router、专家、执行器、计划、审查、提交和会话类。
- [x] 将语义视图依赖的快照 DTO/构建器迁至 `view` 域，更新 package/import/Javadoc。
- [x] 移除 `DocxOrchestratorExample` 与旧编排测试；修复 `ToolTestSupport`。
- [x] 搜索确认源码、示例和文档不再引用 RouterAgent、Dispatcher、DocxOrchestrator 或旧事件。

## 4. 测试

- [x] 添加无需 LLM 的 `DocumentSessionTools` 测试：保存带标题、警告可保存、取消拒绝保存。
- [x] 添加默认 VLLM 集成测试：父 Agent 调用 `invoke_subagent`，SubAgent 在临时 docx 写入居中“项目周汇报”。
- [x] 用测试记录工具调用 trace，断言确实发生父 -> 子代理委派，而非直接写工具。

## 5. 验证

- [x] `mvn -q -Dtest='!VllmSubAgentIntegrationTest' test`
- [x] `mvn -q -DskipTests verify`
- [x] `rg -n "RouterAgent|DocxOrchestrator|LlmDocxExpert|/api/execute|authorization_required|DispatchPlan" nondocx-* docs`
- [ ] `mvn -q -pl nondocx-demo -am -Dtest=VllmSubAgentIntegrationTest test`：当前失败；服务端返回空/失败的工具调用。
- [ ] 启动 demo，手工输入标题任务，确认 SSE、OnlyOffice 刷新和取消行为。
- [x] 检查 `git diff`，确认只包含本任务范围内改动。

## 回滚点

- demo 修改完成并通过编译后，先保留一次可单独验证的工作树状态。
- 删除 toolkit 编排层前运行该模块现有测试，删除后立即编译全仓，定位所有残余引用。
- VLLM 测试失败先区分服务不可达、工具调用未触发、文档断言失败，再分别处理；不得改为跳过。
