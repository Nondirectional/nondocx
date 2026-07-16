# Demo 引入 nonchain 0.11.0 Skills

## Goal

在现有 `nondocx-demo` 单 Agent 文档工作流中接入 nonchain 0.11.0 Skill，展示
“LLM 按用户意图自主点选过程性知识，知识注入后继续使用 docx 工具”的完整链路。
用户应能在 Demo 时间线看到 Skill 激活，但不被运行时 UI 强制引导或限制选择。

## Confirmed Facts

- Demo 当前使用单个顶层 Agent；不恢复 SubAgent、RouterAgent 或操作计划层。
- Agent 持有 toolkit 的只读、正文、表格、修订和质量检查工具；保存由应用层在
  `AgentEvent.Complete` 时强制 flush。
- 根 POM 当前固定 `chain.version=0.10.0`；Demo 通过 `nondocx-toolkit` 间接取得 nonchain。
- 后端通过 SSE 推送 trace，前端在对话时间线中渲染 trace，并由 `TraceJournal` 做 JSONL 回放。
- nonchain 0.11.0 提供 `SkillRegistry`、`SkillDefinition`、`SkillInjectionMode` 和
  `AgentEvent.SkillActivated`；Skill 是无参数 function，独立于普通 tool 拦截器执行。

## Requirements

### R1. 依赖升级

- 根 POM 的 `chain.version` 升至 `0.11.0`。
- 所有 Maven 模块统一使用该版本；不为 Demo 单独覆盖版本。
- 升级后全 reactor 保持可编译、可测试。

### R2. 顶层 Skill 集合

Demo 注册以下 6 条顶层 Skill：

- `inspect-document`：文档内容、结构和目标定位的只读分析。
- `edit-body`：正文段落、run、样式和超链接编辑。
- `edit-table`：表格创建、单元格编辑、合并和相关布局处理。
- `tracked-changes`：修订查询、接受/拒绝和修订创作。
- `audit-quality`：质量检查、问题解释，以及用户明确要求时的修复与复检。
- `inspect-special-parts`：页眉、页脚和目录的只读检查。

Skill 元数据由 Java 注册，正文存放在 `nondocx-demo/src/main/resources/skills/*.md`。
每条正文约 300–600 个中文字符、8–12 条可执行规则，不复制完整工具 schema。

### R3. Agent 行为

- Agent 挂载同一个 `SkillRegistry`，使用 `SkillInjectionMode.SYSTEM`。
- LLM 自主决定是否点选 Skill；基础 prompt 不强制相关请求必须激活 Skill。
- 同一轮允许激活多条 Skill；基础 prompt 只约束同一 Skill 每轮最多激活一次，避免重复注入。
- Skill 可以在同一轮与普通工具组合，不改变普通工具的 dirty、质检和保存语义。
- `current_document`、禁止暴露保存/打开/关闭工具、稳定引用和应用层 flush 仍属于基础 system prompt/代码不变量，不能依赖 Skill 是否被选中。
- `inspect-special-parts` 对写入请求如实说明 Demo 暂不支持，不伪造修改成功。
- `audit-quality` 只有在用户明确要求修复时才修改；检查后应复检，无法由现有工具修复的问题必须保留并说明。

### R4. SSE 与前端可观测性

- `AgentEvent.SkillActivated` 转成现有 `trace` SSE 帧中的 `skill_activated` 事件。
- 事件至少包含 `turnId`、Agent 名称、Skill 名称、Skill description 和注入字符数；不传 Skill 正文。
- Skill 事件写入 `TraceJournal`，刷新页面后可正常回放。
- 前端时间线显示独立 Skill 激活记录，不把它渲染为普通 tool，不触发“进入实施”或 dirty 状态。
- 不增加常驻 Skill 清单/选择面板；Skill 只在实际激活时出现。

### R5. 文档与验证

- README 说明 6 条 Skill 的用途、`SYSTEM` 注入和时间线观测方式，并提供 6 个示例问题。
- 示例问题只标注“可能激活”的 Skill，不承诺真实 LLM 必然命中。
- 增加不依赖网络的确定性测试，覆盖注册、无参数 schema、SYSTEM 注入和
  `SkillActivated` 事件；真实模型仅作为手工 smoke，不作为 CI 的路由断言。

## Acceptance Criteria

- [ ] 根 POM 与全模块使用 nonchain `0.11.0`，全 reactor 编译/测试通过。
- [ ] 6 条 Skill 均从 classpath Markdown 加载并注册；缺失或空正文启动时 fail-fast。
- [ ] Agent function 列表出现 6 个带 `[Skill]` description 的无参数 function。
- [ ] 确定性测试证明 Skill 被点选后，下一轮请求包含 tool result 和 system 注入，且触发 `SkillActivated`。
- [ ] Skill 激活 SSE 帧可实时到达前端并在 trace JSONL 中回放；不含 Skill 正文、不改变 dirty/save 结果。
- [ ] 前端时间线能区分 Skill 激活与普通工具事件；没有 Skill 面板或强制选择控件。
- [ ] README 的示例问题、能力边界和“可能激活”措辞与实现一致。
- [ ] 未引入 SubAgent、toolkit 层 Skill API、动态 Skill 管理或页眉页脚/目录写入能力。

## Out of Scope

- SubAgent Skill 预加载、SubAgentProgress 或后台子代理演示。
- 将 Skill 抽象/正文放入 `nondocx-toolkit`，或为 toolkit 增加 Skill API。
- 浏览器端 Skill 常驻清单、手动选择器、Skill 编辑器或热加载。
- 强制模型激活某条 Skill、基于模型输出对 Skill 路由做 CI 断言。
- 页眉、页脚、目录的写入工具。
- 对质量检查结果进行未经用户授权的自动修复。

## Open Questions

无。实现前由用户审阅本 PRD、`design.md` 和 `implement.md`；审阅通过后再启动任务。
