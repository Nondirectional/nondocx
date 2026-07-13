# 协商与专家实施分离：实施计划

## 实施顺序

1. 读取 `nondocx-toolkit` 与 `nondocx-demo` 的目标层规范，确认任务 context manifest。
2. 为新编排协议建立模型与测试：会话状态、一次性授权、`DispatchPlan`、专家分配/依赖、执行结果和 trace 事件。
3. 重构 toolkit 编排层：删除旧自动 `RouterAgent` 管线；保留 `Operation`/merge/review/执行器；实现只由显式 `DispatchPlan` 驱动的 `ExecutionCoordinator`。
4. 为 `CommitCoordinator` 加入恢复点回滚协作、取消检查、提交前基线校验；补齐失败/取消/文档代次变化测试。
5. 在 Demo 实现主 Agent：多轮 memory、只读工具白名单、流式 AgentEvent→trace 转换、实施请求与结构化 `DispatchPlan` 生成。
6. 将单一 `LlmDocxExpert` 替换为五个工具组受限 LLM 专家；实现组内 prompt、kind 白名单、结构化输出校验和并发规划调度。
7. 实现 `TraceJournal` JSONL 持久化、SSE 广播和回放 API；对所有持久化/SSE 载荷执行密钥脱敏。
8. 改造 Javalin 路由与前端：聊天仅协商；新增“开始实施”和取消按钮、授权 token 传递、完整 trace 时间线、历史回放和新终态展示。
9. 删除旧自动执行入口、旧单专家、旧阶段帧/UI 分支与不再成立的测试；更新 README/demo 架构说明。
10. 执行格式化、单测、模块验证与手动 Demo 验收；若发现新通用约束，更新 Trellis spec。

## 风险文件

- `nondocx-toolkit/.../orchestration/DocxOrchestrator.java`：公共编排入口移除与会话恢复。
- `nondocx-toolkit/.../orchestration/commit/CommitCoordinator.java`：事务语义从“遇错即停不回滚”转为恢复点回滚。
- `nondocx-demo/.../AgentBridge.java`：从直接自动执行改为主 Agent + 授权 + trace journal。
- `nondocx-demo/.../DemoServer.java`、`src/main/resources/static/app.js`、`style.css`：新增授权/取消/回放协议，需与 SSE 字段同步。
- `nondocx-demo/.../LlmDocxExpert.java`：删除，替换为按工具组分离的实现。

## 验证

1. 单元测试：授权状态机、token 失效、只读白名单、`DispatchPlan` 校验、依赖拓扑、专家组输出越界、trace 脱敏/JSONL round-trip。
2. toolkit 集成测试：无授权零写入；冲突阻断；操作失败/取消/文档切换后 reopen 恢复；质量失败保留已提交编辑。
3. demo 路由测试：`/api/chat` 不写入，授权端点才执行，取消端点可中断，trace 端点可回放。
4. 前端手测：协商时无“提交”阶段；授权按钮只在可执行状态显示；完整主/专家 trace 流式展示；刷新后历史仍可见；OnlyOffice 仅保存成功后刷新。
5. 命令：`mvn -q -pl nondocx-toolkit test`、`mvn -q -pl nondocx-demo test`（新增测试后）、`mvn -q verify`。

## 回滚点

- 代码层：本任务在独立提交中实施，若需要撤回则整体回退该提交；不保留旧自动执行运行时路径。
- 文档层：一次执行未保存前的恢复点为当前磁盘 `.docx`；提交失败、取消或基线失效均 reopen 该文件。

## 开始实施前检查

- [ ] PRD、设计与实施计划已由用户确认。
- [ ] `implement.jsonl` 包含 toolkit orchestration、demo SSE/UI 与 nonchain Agent 相关上下文。
- [ ] `check.jsonl` 包含质量规则、编排层规范和测试目标。
- [ ] 验证 nonchain 版本支持 `AgentEvent`、memory 与工具拦截器；若版本不足，先升级或改用等价 API。
