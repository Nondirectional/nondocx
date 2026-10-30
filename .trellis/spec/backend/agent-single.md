# 单 Agent 文档编辑

> `nondocx-toolkit` 只提供工具与共享文档会话；Agent 拓扑属于应用层。demo 使用**单 Agent**：一个 agent 持有全部只读 + 写工具，但**不持有保存工具**——保存由应用层在 agent 循环结束时强制执行。**禁止恢复会执行编辑/保存的 SubAgent 委派层**、RouterAgent、Dispatcher、操作计划或提交协调器。唯一允许的 SubAgent 是**只读复审 SubAgent**（见 §只读复审 SubAgent 例外）。

## 历史：为何从 SubAgent 回归单 Agent

2026-07-13 曾引入"主 Agent + 无状态 SubAgent"双层拓扑（commit `dbae09f`），用一等 SubAgent 工具 `invoke_subagent` 替换旧编排层。但它随即引入了它本想消灭的复杂度：SubAgent 的 LLM 自述（漏调 `save_current_document` / 谎报成功）与磁盘真相之间存在鸿沟，逼出 `attemptFallbackSave` + `correctSubAgentResult` + `DocumentExecutionState` 三件套"真相弥合"逻辑（commit `0e2bca7`）。

2026-07-15 回归单 Agent（本 spec）。核心洞察：**鸿沟的根因不是"有两个 agent"，而是"保存是 LLM 显式触发的动作"**。把保存从 LLM 职责移到应用层代码强制（`AgentEvent.Complete` 时 flush），"漏调 / 谎报"在源头消失，整层真相弥合逻辑删除。

2026-07-17 引入**只读复审 SubAgent** `review_intent`：质检目标从「客观版式规则自检」改为「复审用户本轮期望的修改是否达成」。该 SubAgent 仅持有 `view_*` 只读工具，不持有写工具、不接触保存，因此**物理上无法触发**历史 SubAgent 的真相鸿沟——这是本 spec 允许的唯一 SubAgent 例外。复审为软警告（不拦截保存）。

## Convention: 只读复审 SubAgent 例外

上述 SubAgent 禁令针对的是**会执行编辑/保存的委派层**——它们的根因问题是"SubAgent 自述保存成功 ≠ 磁盘真相"。判断一个 SubAgent 是否被允许，只看一条：

> **该 SubAgent 的 ToolRegistry 是否包含任何写工具或保存能力？**

- 含写/保存工具 → **禁止**。它会在 SubAgent 内触发"漏调 save / 谎报成功"，真相鸿沟回归。
- 仅含 `view_*` 等只读工具 → **允许**。它无法改文档、无法保存，自述与真相之间没有可错配的写入动作。

`review_intent` 属于后者：仅 `scan(toolkit.view)`，做意图达成度的只读复审，输出三态结论（达成/部分达成/未达成）+ 差异说明，作为软警告诊断信号。新增其它 SubAgent 必须满足"ToolRegistry 仅只读"且通过测试断言（见 §Tests Required）。

### Gotcha: SubAgent 的 contextSelector 必须注入 doc_id

只读复审 SubAgent 用 `view_*` 工具读文档，而 `view_*` 需要 `doc_id` 参数。主 Agent 通过 `current_document` 拿到的句柄**不在 SubAgent 上下文里**——框架默认从父消息链裁剪的上下文只含 user/assistant/tool 消息，不含主 Agent 私有的 `docId` 字段。

若 `contextSelector` 只注入「用户本轮请求」而漏掉 doc_id，复审 SubAgent 调 `view_*` 会因缺句柄读不到文档，把**正常完成的修改误判为「未达成」**（实测回归点：2026-07-17）。修复：`selectReviewContext` 必须在注入的 prompt 里显式带上当前 `docId`（它在 `openCurrentDocument()` 构造期已设置，复审发生时必然非空）。

`ReviewSubAgentTest#reviewContextInjectsDocIdAndRequest` 锁定此契约：断言注入的 prompt 同时含 doc_id 与本轮请求。任何重写 contextSelector 的改动都不得丢掉 doc_id。

## Scenario: 当前文档的单 Agent 编辑

### 1. Scope / Trigger

demo 或应用需要根据自然语言直接编辑当前 docx 时，单个 Agent 同时承担咨询（只读）与编辑（写 + 质检）。Agent 没有保存工具；保存由应用层在 agent 主循环结束（`AgentEvent.Complete`）时强制执行。

### 2. Signatures

```java
ToolRegistry tools =
    new ToolRegistry()
        .scan(documentTools)      // current_document（只读句柄）；不含 save
        .scan(toolkit.view)
        .scan(toolkit.body)
        .scan(toolkit.table)
        .scan(toolkit.headerFooterToc)
        .scan(toolkit.trackedChangeQuery)
        .scan(toolkit.trackedChangeAuthoring)
        .registerSubAgent("review_intent", "...")  // 只读复审 SubAgent
            .systemPrompt(REVIEW_PROMPT)
            .toolRegistry(new ToolRegistry().scan(toolkit.view))  // 仅只读
            .llm(llm).maxIterations(6).contextSelector(...).build();

Agent agent =
    Agent.builder(llm, tools)
        .memory(memory)
        .maxIterations(24)
        .addBeforeToolCall(this::beforeToolCall)   // 取消拦截
        .addAfterToolCall(this::afterToolCall)     // dirty 检测 + 结果瘦身 + note
        .systemPrompt("...")
        .build();
```

- `POST /api/chat`：`{"message":"..."}`；咨询与编辑均在此请求内完成。
- `POST /api/cancel`：协作式取消当前编辑（block 后续写工具）。
- `GET /api/trace`：返回 JSONL trace。
- 不存在 `/api/execute`、授权 token、SubAgent 委派、计划或提交端点。

### 3. Contracts

- Agent registry 含 `view_*`/`read_*`/写工具/`review_intent`（只读复审 SubAgent）/`current_document`。**不含** `save_current_document`/`save_docx`/`open_docx`/`close_docx`——保存是应用层方法，不是 LLM 工具。**不含** toolkit 的规则 `check_quality`（demo 不再 scan 它；toolkit 的 10 项规则作为通用能力保留，供 `view_issues` 等复用）。
- Agent 先调用 `current_document` 取得当前 `doc_id`，不能猜测路径。
- **保存代码强制（软警告）**：`AgentEvent.Complete` 时，若本轮 `dirty`，应用层调 `DocumentTools.saveCurrentDocument(cancelled)`——**dirty 即落盘，无质检门控**。意图复审（`review_intent`）为软警告：复审判「未达成」**不拦截保存**，复审结论随 `edit_outcome.qualityReport` 回传供用户判断。
- **dirty 检测**：写工具的 `AfterToolCall` 把 `dirty` 置 true。只读判定（`isReadonly`）按工具名前缀：`view_`/`read_`/`get_`/`list_`/`search_`/`check_`/`review_` 及精确集合 `current_document`/`describe_capabilities`/`get_subagent_result`/`steer_subagent` 为只读；**未知工具视为写**（安全默认：漏标 dirty 会丢编辑，多标只是浪费一次 flush）。`review_intent` 归只读——复审 SubAgent 不改文档。
- **只读复审 SubAgent 构造**：`registerSubAgent("review_intent", ...)` 的 `toolRegistry` 必须**仅 `scan(toolkit.view)`**——不 scan 任何写工具、不 scan 任何含 save 的类、不注册嵌套 SubAgent。这是 spec 允许例外的物理保证，须有测试断言。
- 无"漏调 check 兜底"：复审为软警告，不拦截保存，故 `dirty` 时无论是否调过 `review_intent` 都直接落盘；复审结论缺失只是 `qualityReport` 为空。
- SSE 使用 `edit_outcome` 帧返回权威成败状态（`status`: `noop`/`saved`/`rolled_back`/`cancelled`）；`doc_changed` 仅在服务端保存成功后发送。
- `trace` 保留 Agent 的工具 `tool_start`/`tool_end`（单 Agent 下写工具事件直接可见，可观测性优于 SubAgent 模式）。

### 4. Validation & Error Matrix

| 条件 | 服务端行为 |
|---|---|
| Agent 只调只读工具（咨询） | `dirty=false`，`Complete` 发 `edit_outcome.status=noop`，零写入 |
| 写工具成功 + 复审达成 | `Complete` flush 落盘，`status=saved`，发 `doc_changed`，复审结论随 `qualityReport` 回传 |
| 写工具成功 + 复审判未达成 | **仍落盘**（软警告），`status=saved`，复审结论（未达成 + 差异）随 `qualityReport` 回传供用户判断 |
| 写工具成功 + 漏调 `review_intent` | 仍落盘（无门控），`status=saved`，`qualityReport` 为空 |
| 收到取消 | `BeforeToolCall` block 后续写工具，`Complete` 时 `reopen` 回滚，`status=cancelled` |
| 保存失败（IO 等） | `reopen` 回滚，`status=rolled_back` |

> 复审不产生 `rolled_back`：复审为软警告，无论结论如何都落盘。`rolled_back` 仅由取消或保存失败触发。

### 5. Good / Base / Bad Cases

- Good：用户请求"在文档开头添加居中标题"，Agent 调 `current_document` → `insert_paragraph(body_index=0, heading_level=H1, alignment=CENTER)` → `review_intent`（复审 SubAgent 读文档对比请求）；循环结束，应用层 flush 落盘，复审结论随 `edit_outcome` 回传。
- Base：用户询问文档有几段，Agent 只调用 `view_stats`，`dirty=false`，不触发 flush、不触发复审。
- Bad：给 Agent 注册 `save_docx`/`save_current_document` 工具；给 `review_intent` 的 toolRegistry scan 任何写工具；恢复会编辑/保存的 SubAgent 委派层、计划/分派/提交。

### 6. Tests Required

- 单元测试：`saveCurrentDocument` dirty 即落盘（无质检门控）；取消拒绝保存；重新打开后未保存改动不存在；save 不暴露为工具。
- dirty 检测测试：只读前缀（含 `review_`）不置 dirty；写工具置 dirty；未知工具视为写（安全默认）。
- 结果瘦身测试：写工具结果瘦身为一行确认（不回显 data）；复审结论摘要截断 + note 存档提示；`parseVerdict`/`summarizeReview` 三态解析。
- **只读复审 SubAgent 不变量测试**：断言 `review_intent` 的 ToolRegistry 仅含 `view_*`（不含任何写/保存工具）——这是 spec 允许例外的物理保证。
- `edit_outcome` status 派生测试：noop/saved/rolled_back/cancelled 矩阵；取消优先；复审未达成不进 rolled_back。
- 集成测试：真实 VLLM 单 Agent 直接调写工具 + `review_intent`（不再有 `invoke_subagent`），临时 docx 第一段为"项目周汇报"且居中。

### 7. Wrong vs Correct

```java
// Wrong：给 Agent 保存工具；给复审 SubAgent 写工具；恢复会编辑/保存的 SubAgent 委派层。
ToolRegistry tools = toolkit.scanAll(new ToolRegistry());  // 含 save_docx
registry.registerSubAgent("review_intent", "...")
    .toolRegistry(new ToolRegistry().scan(toolkit.body))   // ✗ 复审 SubAgent 含写工具！真相鸿沟回归
    .build();
primaryTools.registerSubAgent("invoke_subagent", ...).toolRegistry(childTools).build();

// Correct：单 Agent 持有写工具但不持有保存；保存由 Complete 时应用层强制。
ToolRegistry tools = new ToolRegistry().scan(documentTools).scan(toolkit.view)
    .scan(toolkit.body).scan(toolkit.table)...;
// 复审 SubAgent：toolRegistry 仅 scan(toolkit.view)，绝不 scan 写工具。
tools.registerSubAgent("review_intent", "...")
    .toolRegistry(new ToolRegistry().scan(toolkit.view))  // ✓ 只读
    .systemPrompt(REVIEW_PROMPT).llm(llm).build();
// documentTools.saveCurrentDocument() 是普通方法（无 @ToolDef），由 AgentEvent.Complete 触发，dirty 即落盘。
```

## Convention: 保存不是 LLM 职责

`save_current_document` **不是** `@ToolDef`。它是 `DocumentTools` 的应用层方法，由 `AgentBridge` 在 `AgentEvent.Complete` 时调用。这从源头消灭"LLM 漏调 save / 谎报成功"，无需 SubAgent + 真相纠正编排层。一旦把 save 暴露为工具，"自述 ≠ 真相"的鸿沟立刻回归。

## Convention: 记忆污染治理（α 瘦身 + β note）

单 Agent 持久记忆窗口（`MessageWindowChatMemory`，按条数淘汰不按 token）会被写工具结果/复审结论撑爆。SubAgent 的"无记忆"曾是天然 GC 边界，回归后必须手动重建等价机制：

- **α 写工具瘦身**：写工具（insert/replace/delete body、table、修订等）的 `AfterToolCall` 把结果改写为一行确认（"✓ insert_paragraph：已插入"）。LLM 刚写完，不需回显全文。
- **β note 隔离**：`review_intent` 复审结论原文挂 `Message.note("review_report", 全文)`（`llmVisible=false`：不占窗口、不喂 LLM），紧凑摘要（`summarizeReview`：三态 + 截断 diff）作真工具结果喂 LLM。
- **读工具保持全量**：`view_*`/`current_document` 的输出是 LLM 推理的必要输入，不瘦身。

`Message.note` 的约束：`llmVisible=false` 意味着 LLM 看不到它，只能用于"给 UI/应用层重放但不喂 LLM"的数据。LLM 要据之推理的内容**必须**是真 tool result。

## Convention: 取消真相（prompt 诚实 + SSE 系统帧）

单 Agent 文本流式、不可事后改写。取消竞态下 Agent 可能流式输出"已完成"。应对分两层：

- **① prompt 诚实**：`BeforeToolCall` block 消息含强指令"禁止声称已完成/已保存"（概率性改善）。
- **② SSE 系统帧**：`edit_outcome` 帧由服务端状态（`dirty/saved/cancelled`）钉死 `status`，前端**以帧为准**渲染成败口径，Agent 文本仅作辅助（确定性）。

这是原 `correctSubAgentResult`（JSON 矩阵改写）的合理下沉：从"事后改写工具结果"→"前端按系统帧渲染口径"。净简化。

## Gotcha: VLLM 工具调用解析

`VLLM` provider 通过 `chat_template_kwargs.enable_thinking` 传递思考开关，并从 `reasoning` 字段读取思考文本。真实集成测试必须禁用思考。若响应是 `finish_reason=tool_calls` 但 `tool_calls=[]`，问题在 VLLM 服务的 tool-call parser 配置，客户端不得伪造工具调用或把集成测试改为跳过；测试应失败并显示此诊断。

## Gotcha: AgentEvent.Complete 是唯一的循环结束钩子

nonchain `Agent.Builder` 无 `onComplete`/`afterLoop`/`finally` 方法；`ChainCallback` 只有单次 LLM/工具调用粒度回调，无循环结束事件。唯一能拿到"agent 循环结束"信号的是 `run(msg, Consumer<AgentEvent>)` 流式重载里的 `AgentEvent.Complete`。`maxIterations` 耗尽（默认 `graceTurns=3`）也会发 `Complete`，因此 flush 天然覆盖超时路径。**非流式 `run(msg)` 重载不发 Complete**——demo 必须用流式重载。

## Convention: Agent 评测基线（headless）

`nondocx-examples/.../examples/eval/` 是当前工具面的 headless 评测基线，不是生产代码：

- 10 条任务，seed 由 `Docx.create()` 构造，check 只读最终 docx；
- 工具面与 demo 对齐（`current_document` + view/body/table/header/tracker + 只读 `review_intent`），
  但**不加载 6 条 Skill**，以隔离工具面本身；
- 保存仍由应用层强制（`EvalRunner` 在 `Agent.run` 返回后调用 `save_docx`），不暴露给模型；
- `EvalTasksTest#everyTaskFailsAtLeastOneCheckOnUnmodifiedSeed` 是防恒真 check 的硬约束：
  每条任务在未修改 seed 上必须至少失败一次；
- 运行：`mvn -q -pl nondocx-examples -Dexec.mainClass=...EvalMain exec:java`；
  产物在 `target/eval/<runId>/`。

**已知限制**：当前 `chain 0.11.0` 的 VLLM streaming provider 未返回 usage，报告 token 记为 0；
这是 provider 限制，不是评测回归。升级 provider 后自动恢复。

**2026-10-29 v1 结果**：8/10 通过，平均 6.8 次工具调用；2 条失败均为已知能力缺口
（正文行距无工具、页脚只读）。同日修复正文行距工具 + header/footer 只读 ref 解析后，
`body-font-line-spacing` 复跑通过；页脚 PAGE 域写入仍为缺口。
详见任务 `10-29-agent-eval-baseline/baseline-summary.md`。

## Convention: ref 的容器边界（读/写分离）

`ElementResolver` 有两个段落解析入口，**不得混用**：

- `resolve(ParagraphRef)`：只扫正文 `document.paragraphs()`，是写工具的唯一边界。
- `resolveAny(ParagraphRef)`：扫正文 + 表格单元格 + 页眉 + 页脚，**只允许只读路径**
  （`view_element` / `read_paragraph`）使用。

`read_run` 的 `ref` 路径可解析页眉/页脚/表格 run（只读）；`paragraph_index + run_index`
仍只解释为正文。写工具（`replace_run_text` / `update_run_style` /
`update_paragraph_alignment` / `update_paragraph_spacing`）遇到页眉/页脚/表格 ref 时
必须返回明确「仅支持正文」错误，不能静默按正文索引写入。

**为什么**：页脚 ref 若被写工具当成正文，会出现「工具返回成功、实际改了正文」的静默
错目标。2026-10-29 的页脚评测复跑观察到：页脚 ref 被拒绝后，模型退回
`replace_run_text(paragraph_index=0)`，把正文改成「第 页」；评测的「正文不被误改」
check 捕获了它。仅靠 ref 校验无法阻止模型主动选正文索引，后续需要显式 `scope`/容器
校验或 IR patch。

`view_element` 的 paragraph 详情输出 `location`（如 `页脚 section=0 variant=default 段落 0`）、
`alignment`（**恒出现**，未设置=left；命名与 `{align=…}` 写侧记号一致 left/center/right/both）
和 `lineSpacing`（>0 时），供复审确认目标容器、对齐与行距。run 详情（`view_element` 与
`view_annotated` expandRuns）输出 `font`（ascii/西文槽）**和** `fontEastAsia`（中文槽，core
`Run.fontEastAsia()`）——编译器写的是「西文 TNR/Arial + 中文宋体/黑体」双槽，只暴露 ascii
槽会把已正确的中文字体误判成「未设置」（heading-font 曾 24 次空转）。

**复审盲区已补齐**（2026-10-30 第二批，同日评测三任务复审全部「达成」且给出具体证据）：
表格详情含 `cellTexts` 矩阵预览（≤6 行、每格截断）+ CELL/REVISION 详情实现；
`view_outline` 修订概览带 `countByAuthor` 与 `recent` 明细（ref/type/author/片段，ref 可直通
`view_element`）；`view_stats` 带 `footerText` 预览（截断 120，无页脚为 null）。
**只富化既有工具输出，不增工具数**——full 58 / compiler 6 基线不动。
第三批（同日，随 `comment.add`）补批注观测：段落详情 `comments` 数组（恒出现，空=无）、
`view_stats` `commentCount`、`view_outline` 批注概览
`CommentOverview{total,countByAuthor,recent[{paragraph,author,snippet}]}`（与修订概览同型）。

> **属性可见性是复审的地基**（2026-10-30 教训）：复审 SubAgent 只能依据工具**可见**的属性下结论，
> 「详情里没有 alignment」会被它解读成「未设置对齐」——`insert-centered-title` 曾因此空转 24 次
> （主模型第 2 次调用就已正确，8 次 review 全部误报「未居中」）。给复审需要核对的属性
> （对齐/行距/标题级别/中文字体/单元格内容/修订作者/页脚文本）加观测面时，**恒出现**优于
> 「设置才出现」：不给「属性缺失=？」留推断空间。

## Convention: 编译器工具面（compiler surface，实验性）

「文档 IR + 编译器」路线（`docs/11` §5 P1）落成了第 9 组工具 `CompilerTools`
（`create_document` / `read_document` / `validate_document`，能力清单 EXPERIMENTAL 级）。
工具面约定：

- **会话换血不换柄**：`create_document` 经 `ToolkitToolContext.replaceSessionDocument`
  替换 docId 背后的 Document（与 `SessionTools.reopen` 同一路径）：句柄不变、generation+1、
  旧元素引用失效。失败路径（解析/编译/profile 错误）**不触碰会话**。
- **demo/eval 的 full 面不 scan `compiler`**（保持 58 入口基线可对比）；compiler 面由
  应用层显式组装：`current_document` + `compiler` 四工具（create/read/validate/edit）
  + 只读 `review_intent` = 6 入口。
- **demo 默认 compiler 面**（2026-10-30 起）：`AgentBridge.buildToolRegistry(surface,…)`
  按面装配（compiler = documentTools + `toolkit.compiler`；full = 原六组 scan），
  `NONDOCX_AGENT_SURFACE=full` 回退。**配套必须随面切换**：system prompt
  （`systemPromptFor`）与 Skill 策略（`skillRegistryFor`——full 面 Skill 教的是
  compiler 面不存在的工具，compiler 面必须空 `SkillRegistry`）。复审 SubAgent 两面共用
  （仅 `toolkit.view`，物理保证不变）。`validate_document` 归只读前缀（质量检查不置
  dirty，纯咨询轮不触发落盘）。测试 `SurfaceSwitchTest` 锁定双面装配。
- **eastAsia 字体槽分离是编译器独有兜底**：core 公开 API `font()` 走 POI 四槽同写
  （ascii/hAnsi/cs/eastAsia 同名）；规范的中文排版需要「西文 TNR + 中文宋体」分离。
  编译器经 `raw()` 只覆写 eastAsia 槽（`DocumentCompiler.applyRunFonts` 是唯一用点，
  core 提供公开分离 API 前不要在其它工具类复制此逻辑）。
- **评测 A/B**：`EvalMain --surface full|compiler --group baseline|create`；create 组
  （5 条从零建文档任务）两面通用，是「调用数 10+ → 2~3」假设的判定口径。
  `EvalRunnerTest#compilerSurfaceExposesFiveEntriesOnly` 锁死 5 入口数。
- **read/write 同构**：`read_document` 输出的 Markdown（短 ID 锚点是 HTML 注释）可原样
  再作为 `create_document` 输入——解析器把 `<!-- … -->` 当元数据剥掉，round-trip 不产生
  脏块。改输出格式时保持这一性质（`CompilerPipelineTest#markdownViewRoundTripsCompiledDocument`）。
- **edit_document 原子性**（P2）：`PatchEngine` 先 preflight 全部 op（锚点存在/参数合法/
  find 在文中/行列在界/表格块仅 end/前序删除的锚点不得复用），任一失败抛
  `PatchException` 且**零写入**；执行期不应有可预期失败。锚点编号与 `read_document`
  同源（`pN`/`tN`），插入块复用 Markdown 解析器。执行期意外异常按「可能部分应用」上报，
  不得伪装成功——`PatchEngineTest#atomicPreflightFailureLeavesDocumentUntouched` 锁死零写入。
- **同锚点改文本独占**（P2 续）：`tracked.*` 与 `replace_text` 每批 ops 里对同一段落锚点
  至多一条（第二个认领者整体拒绝，提示拆两次 `edit_document`）。原因：tracked del 会把
  被删文本从 `text()` 移走、ins 文本对 POI 读侧不可见（`w:ins` 内 run 不被
  `XWPFParagraph.getRuns()` 暴露），修订偏移必须按**执行时活文本**从右到左计算——
  同锚点多条会互相污染。
- **tracked.replace 是最小 diff**（P2 续）：core `Paragraph.replaceTrackedRange` 逐 run 切片
  del「find」范围 + ins 紧随其后（core 唯一的「定位 ins」下沉点在
  `TrackedChangeNodes.addInsertionAfter`——`addInsertion` 只能段末追加，直接组合会把 ins
  放到句号后）。XmlBeans 陷阱：`moveXml` 移动过的节点句柄会断连，靠 id 重查活句柄
  （`delById`/`insertedRunByRevisionId`）；accept/reject 在 POI 缓存之下改动，测试断言
  文本必须 save+reopen 再读（同批 tracked op 断言修订本身不受影响）。
- **页脚页码域必须带缓存结果**（P2 续）：纯指令域（begin/instr/end 三 run）的
  `footer.text()` 读出「第  页」无数字——所有 POI 系读者都看不到域值。core
  `Paragraph.addSimpleField(instruction, cachedResult)` 写五 run 形态
  （begin/instr/**separate/result**/end），result 是普通 `w:t` run（返回它，可链式设样式），
  Word/WPS 打开时重算。`footer.set_page_number` op 的 format 支持 `{page}`/`{pages}` 占位符，
  缓存值固定 "1"（多页文档里每页真值只有渲染引擎知道）。
- **read_document 的页脚元数据行**（P2 续）：`MarkdownView` 在正文后输出
  `<!-- 页脚: … -->`（存在且非空才输出）——页码写入后模型能读到核对；注释形态保
  round-trip（`CompilerPipelineTest#markdownViewShowsFooterAsMetadataAndKeepsRoundTrip`）。
- **comment.add 与批注观测**（v3，2026-10-30）：`comment.add`（`anchor+text+author` 必传）
  委托 core `Paragraph.addComment` 整段范围批注。批注**不改段落文本**，不参与同锚点
  改文本独占（`claimAnchor` 只管 tracked.\*/`replace_text`）——可与 `replace_text` 同批
  作用于同段。comment→paragraph 映射的唯一公共入口是 core `Paragraph.comments()`
  （活对象语义；POI CTP 子树扫描下沉 `CommentNodes.commentsAnchoredIn`）。已知读侧语义：
  `addComment` 把 `commentRangeStart` 插在段首，同段多条按 XML 出现序读取是**后写先出**
  （LIFO）——断言顺序时注意。新增「作者署名标记」类能力时写读必须同批落地：只有写侧
  op 没有读侧观测，复审会把已正确的文档误报「未达成」（§6.6 同族教训）。
- **read_document 的批注元数据行**（v3）：`MarkdownView` 在被评段落行后输出整行
  `<!-- 批注 pN [作者]: 内容 -->`（每条一行）；round-trip 由「解析器跳过整行注释」保证
  （`CompilerPipelineTest#markdownViewShowsCommentAsMetadataAndKeepsRoundTrip`）。
- **fill_template 的重写策略**（P2 收尾，2026-10-30）：跨 run 占位符按 `paragraph.text()`
  合并命中后，重写用「首 run 承载全文、其余 run 清空」——**不要**删光重建单 run：重建要克隆
  样式，而 `Run.font()` 写侧是四槽同写，会把西文字体名写进 eastAsia 槽污染中文。表格模板行
  复制必须走 core `Table.copyRowTo`。缺 key 的占位符**保留原样 + warning**（模型可补数据
  重试），不做 preflight 拒绝——宽容降级优于静默清空。
- **POI 5.3 表行缓存的脱链陷阱**（P2 收尾，字节码验证）：`XWPFTable.addRow(row,pos)` 把传入
  wrapper 塞进 `tableRows` 缓存，却把内容**复制**进自建的新 tr——传入脱链 copy 时，经返回行的
  写入落进不在文档树里的对象，**内存读正确、落盘 XML 原样**（fill_template 首版 eval 踩中；
  `getRows()` 无缓存重建路径）。`copyRowTo` 用**影子行技法**破局：手动 `insertNewTr` 得到树内
  元素并填充 → 传包树内元素的 wrapper 给 `addRow`（缓存正确、XML 多出一行复制内容）→
  `removeTr` 删掉影子行。测试必须 **save+reopen 断言**——纯内存断言测不出这类陷阱
  （`TableTest#copyRowToReturnedRowWritesIntoDocumentTree` 锁死）。
- **render_preview 只读且防环境坑**（P2 收尾）：渲染不改文档、不置 dirty（demo
  `READONLY_TOOL_PREFIXES` 含 `render_`）。soffice 路径经 `NONDOCX_SOFFICE`（默认
  `soffice`）；每次调用独立 `-env:UserInstallation` profile（LibreOffice 同 profile 单实例
  锁，并发静默失败）；超时销毁进程。集成测试用 `assumeTrue(soffice 存在)`——无
  LibreOffice 的 CI 跳过而不是红。
- **compiler 面是 8 入口**（P2 收尾起）：current_document + create/read/validate/edit +
  fill_template + render_preview + review_intent。eval（`EvalRunnerTest`）与 demo
  （`SurfaceSwitchTest`）双锁死；full 面 58 不变（不 scan compiler，A/B 基线保持）。
