# 规划 toolkit RouterAgent 多子代理落地

## Goal

把现有 `nondocx-toolkit` 从“一个 Agent 直接持有全部工具”升级为更可控的 RouterAgent + 多专业子代理协作方案。

目标不是立刻让所有子代理并发写文档，而是先建立安全架构：RouterAgent 负责理解用户意图、拆分任务、调度专业子代理；子代理负责各工具域的深入理解与计划产出；最终写入由单一串行通道完成，避免 POI 活文档并发修改冲突。

## Confirmed Facts

- `DocxToolkit` 当前聚合七组工具：`SessionTools`、`BodyTools`、`TableTools`、`HeaderFooterTocTools`、`TrackedChangeQueryTools`、`TrackedChangeAuthoringTools`、`QualityCheckTools`。
- 七组工具通过同一份 `sessions` / `seq` 共享文档会话；`sessions` 当前是 `HashMap<String, Document>`。
- `ToolkitToolContext` 明确写明当前线程模型是“单 Agent 实例设计，内部状态未做并发保护，不要跨 Agent 共享”。
- nonchain 已升级到 `0.10.0`，支持后台子代理、自动 join、主动查询子代理结果、运行中 steer、graceful max turns 等能力。
- `.docx`/POI 的 `XWPFDocument` 是活 DOM 对象模型，直接并发写同一份文档有冲突风险。

## Requirements

- 当前任务作为父任务，承载完整 RouterAgent 多子代理体系的总体需求、分批地图与跨批次验收标准。
- 引入 RouterAgent 作为顶层主控，负责用户请求理解、任务拆分、子代理调度、结果合并和最终提交。
- RouterAgent 不直接调用 toolkit 写工具；所有写入必须经过 `CommitCoordinator` 串行提交。
- 按 toolkit 工具域设计专业子代理，让每个子代理拥有该工具组的详细使用教程、参数规则、常见失败恢复策略。
- 支持子代理异步执行，但第一阶段只允许异步执行读操作、分析操作、计划生成和质量检查。
- 同一份 `docId` 的写操作必须串行化，不能让多个后台子代理直接并发修改同一个 `Document` 活对象。
- 输出结构化 edit plan，RouterAgent 或单独 CommitAgent 统一合并、冲突检测、排序和提交。
- 子代理面向 LLM 输出 JSON edit plan；RouterAgent 必须先解析、校验并转换为强类型 Java edit plan，`CommitCoordinator` 只接受强类型 plan。
- 冲突检测采用分层粒度：先用粗粒度 `ConflictKey` 找出同一目标，再按 operation 类型和字段级意图判断是否可安全合并。
- 子代理读取上下文采用混合模式：RouterAgent 先提供基础快照；子代理需要更细信息时，经 `ReadCoordinator` 串行或限流调用只读 toolkit 工具补充。
- 基础快照采用分层 `DocumentSnapshot`：默认包含 overview、索引地图、短文本预览和结构摘要；完整段落/run/单元格细节由 `ReadCoordinator` 按需补读。
- `DocumentSnapshot` 必须建成强类型 Java 对象，作为 RouterAgent、ReadCoordinator、子代理 prompt 渲染与校验的共享事实层，而不是临时拼接字符串。
- `DocumentSnapshot` 第一版基础粒度停在 paragraph / cell 级；run 级内容不进入默认快照，由 `ReadCoordinator` 按需补读。
- 第一版子代理固定按 toolkit 工具组专家拆分，而不是按任务类型横切拆分。
- `SessionTools` 不做独立 `SessionAgent`；`open/save/close` 等文档生命周期控制由 RouterAgent 所在 orchestration 层与 coordinator 层直接持有。
- 第一版 `QualityAgent` 作为软闸门：输出带等级的质量审查结果（如 `BLOCKING` / `WARNING`），是否继续提交由 RouterAgent 综合判断。
- 每个子代理只允许产出本工具组的原子操作 plan；跨工具组组合、排序、冲突处理与保存时机由 RouterAgent/coordinator 统一负责。
- 第一版 `CommitCoordinator` 明确采用非事务语义：按顺序执行，遇错即停，不承诺自动回滚已完成修改；失败状态与已执行步骤必须显式返回给 RouterAgent。
- 第一版提交失败后不在当前 live session 上继续补救；RouterAgent 必须关闭当前会话并从磁盘重新打开文档，再发起新一轮分析与提交。
- 旧的“单 Agent 直接持有全部工具”路径不保留；examples/demo/runtime 入口统一迁移到 RouterAgent 多子代理新实现，且对外入口形态也一并重写，不保留兼容壳层。
- RouterAgent 多子代理体系通过新的高层 facade 暴露（如 `DocxOrchestrator`），`DocxToolkit` 继续保留为底层工具聚合器，不承担编排层职责。
- 新高层 facade 第一版同时暴露高层一键入口和低层分步入口：例如 `run(...)` / `chat(...)` 风格入口，以及 `analyze(...)` / `plan(...)` / `commit(...)` 风格入口。
- 第一版 `DocxOrchestrator` 支持多轮 conversation memory，不只支持单轮任务执行。
- 第一版 memory 绑定单活跃文档会话：一个 conversation memory 只服务一份活跃文档，不支持在同一 memory 中混用多个 `docId`。
- 第一版若用户切换到另一份文档，系统必须开启新会话，不复用旧 memory。
- 第一版对外显式暴露 `conversationId` / `sessionId` 等会话标识，由调用方决定续聊同一会话还是新建会话。
- 底层 `docId` 只在 orchestrator/coordinator/toolkit 内部流转，不对外暴露给调用方。
- `ReadCoordinator` 采用有限并发模型，不允许对子代理补读请求完全无界并发放行。
- `ReadCoordinator` 第一版默认并发参数固定为：同文档 `per-doc = 1` 读槽，全局 `global = 4` 读槽。
- RouterAgent 第一版采用分阶段派发：先基于用户意图与 `DocumentSnapshot` 粗分流，再并发唤起必要专家；如有需要，再进行第二轮增量派发。
- RouterAgent 第一版使用显式任务状态机推进流程，至少覆盖 `ANALYZE`、`PLAN`、`REVIEW`、`COMMIT`、`DONE`、`FAILED` 等阶段。
- `REVIEW` 阶段采用条件触发，而不是每次强制执行；至少在跨专家 plan 合并、冲突候选、质量告警、修订相关操作、失败后重试等场景进入。
- `PLAN` 阶段采用双层结构：既保留每个专家的 `ExpertPlan`，也由 RouterAgent 生成跨专家合并后的 `MergedPlan` 作为统一执行视图。
- `MergedPlan` 第一版采用固定优先级排序：结构变更 -> 文本/样式变更 -> 修订相关操作 -> 质量复查 -> 保存前检查；提交/保存/关闭属于 coordinator 生命周期动作，不进入专家 plan 排序。
- `ExpertPlan` / `MergedPlan` 第一版保留 explanation 信息，至少覆盖用户意图映射、操作理由与风险说明，供 review、trace、失败复盘与 UI/CLI 展示使用。
- `ExpertPlan` / `MergedPlan` 第一版为每条 operation 分配稳定 `operationId`，并维护从专家提案到合并执行项的来源映射。
- `REVIEW` 阶段对每条 operation 产出强类型 review 状态，至少覆盖 `APPROVED`、`WARNED`、`BLOCKED`、`SKIPPED`，而不是只写入自由文本说明。
- 第一版只要 `MergedPlan` 中存在任一 `BLOCKED` operation，则整批停止，不进入 `CommitCoordinator` 执行。
- `WARNED` operation 第一版允许继续提交，但必须显式保留在 review 结果、最终返回、日志与 trace 中，不能静默吞掉。
- `SKIPPED` operation 表示“该操作被系统显式识别、评审并跳过”，必须保留记录与原因，而不是在合并过程中无痕消失。
- 第一版重复 operation 被去重吸收时，也必须生成显式 `SKIPPED` 记录，并指向最终保留的 `operationId`。
- 第一版为 `SKIPPED` 预定义小而稳的原因枚举，至少包含 `DUPLICATE_MERGED`、`SUPERSEDED`、`OUT_OF_SCOPE`、`LOW_CONFIDENCE`、`CONFLICT_DROPPED`。
- 第一版也为 `WARNED` / `BLOCKED` 预定义小型原因枚举，避免 review 原因退回自由文本。
- 第一版 `QualityAgent` 结论直接映射到统一 review 状态：质量门禁失败直接产出 `BLOCKED(QUALITY_GATE_FAILED)`，质量风险直接产出 `WARNED(QUALITY_RISK)`。
- 第一版 review 结果保留强类型 `ruleCode` 字段，用于标识命中的具体质量规则、冲突规则、上下文规则或安全规则。
- `ExpertPlan` / `MergedPlan` 第一版顶层显式携带 `schemaVersion`，并在 Java 强类型与 JSON 表示中同时保留。
- `DocumentSnapshot` 第一版也显式携带 `snapshotVersion`，并在 Java 强类型与 JSON 表示中同时保留。
- `DocumentSnapshot` 第一版显式保留基线元数据，至少覆盖 `sourcePath`、`conversationId`、`createdAt`、`sourceLastModified`，用于判断快照是否过期或跨会话失效。
- `DocumentSnapshot` 第一版额外保留强一致性校验字段，至少包含 `sessionGeneration`；每次 close/reopen/reset 后代次递增，用于识别旧快照。
- 第一版不引入 `docFingerprint`；快照一致性先由 `conversationId`、基线元数据与 `sessionGeneration` 共同保证。
- `DocxOrchestrator` 高层 `run(...)` / `chat(...)` 默认只返回高层摘要；完整阶段产物（snapshot / expert plans / merged plan / review / commit result）仅在 debug 模式或低层 API 中暴露。
- 高层摘要结果除自然语言总结外，还显式包含精简操作清单与统计字段，至少覆盖 `summaryText`、执行/警告/跳过/阻断计数，以及按 operation 汇总的精简状态视图。

## Child Task Map

- 批次 1：架构与协议基建。定义 RouterAgent / 子代理 / Commit 通道边界、edit plan schema、冲突 key、工具安全分级。
- 批次 2：RouterAgent 与子代理注册骨架。落地工具组 registry、子代理 prompt 管理、nonchain 0.10.0 后台子代理配置。
- 批次 3：正文与表格子代理。覆盖 `BodyTools` / `TableTools` 的教程、plan 生成、读写边界与试点提交。
- 批次 4：修订、页眉页脚目录、质量子代理。覆盖 `TrackedChange*Tools` / `HeaderFooterTocTools` / `QualityCheckTools`。
- 批次 5：端到端 demo、验证与文档。把多子代理体系接到 examples/demo，补测试、文档、回退路径。

## Acceptance Criteria

- [ ] 形成 `RouterAgent`、专业子代理、提交通道三层职责说明。
- [ ] `CommitCoordinator` 是唯一写入口；RouterAgent 与子代理不得直接并发写同一个 `docId`。
- [ ] 明确哪些工具可后台异步调用，哪些工具必须串行写入。
- [ ] 明确 JSON edit plan schema 与 Java 强类型 plan 结构，至少能表达正文、表格、修订、保存等操作。
- [ ] RouterAgent 负责把 JSON plan 解析校验为强类型 plan；非法字段、缺失字段、未知操作必须在提交前失败。
- [ ] 明确分层冲突检测规则：粗粒度目标相同先标记候选冲突，再按文本、样式、结构、修订等 operation 类型判断可合并或必须人工/RouterAgent 决策。
- [ ] 明确 `ReadCoordinator` 只读边界：基础快照由 RouterAgent 提供，子代理补读必须通过串行/限流只读通道，不直接并发访问 live `Document`。
- [ ] 明确 `DocumentSnapshot` 基础内容：overview、段落索引与短预览、表格尺寸与关键单元格预览、修订数量/类型摘要、页眉页脚/目录存在性、质量风险摘要。
- [ ] `DocumentSnapshot`、子结构 snapshot、JSON 序列化/文本渲染边界明确，避免把 prompt 文本当事实源。
- [ ] `DocumentSnapshot` 第一版不默认携带 run 级明细；正文和单元格 run 细节必须走补读通道。
- [ ] 第一版子代理清单与 toolkit 工具组对齐，至少包括 `BodyAgent`、`TableAgent`、`RevisionAgent`、`HeaderTocAgent`、`QualityAgent`。
- [ ] `SessionTools` 生命周期边界明确：`open/save/close` 不暴露给子代理，session 资源管理不进入 LLM 决策面。
- [ ] `QualityAgent` 第一版输出分级审查结果而非硬阻断；RouterAgent 能基于 `BLOCKING` / `WARNING` 结果决定是否调用 `CommitCoordinator`。
- [ ] 子代理 plan 边界明确：单个子代理只输出本工具组原子操作，不输出跨组组合操作，也不决定保存时机。
- [ ] `CommitCoordinator` 失败语义明确：第一版不做自动回滚，按顺序执行、遇错即停，返回已执行步骤、失败点与是否允许后续保存的建议。
- [ ] 提交失败后的恢复路径明确：不在失败后的 live session 上继续补救，必须 close 并 reopen 后再进入下一轮。
- [ ] `DocxOrchestrator` 第一版支持多轮 memory，且 memory 边界要和 session/document lifecycle 协同清楚。
- [ ] memory 与文档会话绑定规则明确：第一版单 conversation 只对应一份活跃文档；切换文档需要显式开启新会话或重置当前会话。
- [ ] 文档切换策略明确：切换到新文档必须新开 conversation，不复用旧文档 memory。
- [ ] 对外 API 的会话标识策略明确：调用方可显式传入或获取 `conversationId/sessionId`，用于续聊、关闭、重建会话。
- [ ] 底层 `docId` 不泄露到对外 API；对外只暴露会话标识和高层结果。
- [ ] `ReadCoordinator` 并发策略明确：不是完全串行，也不是无界并发；需要定义按文档与全局两级限流规则。
- [ ] `ReadCoordinator` 第一版默认限流参数明确：`per-doc = 1`、`global = 4`，并说明后续如需可配置化的位置。
- [ ] RouterAgent 调度策略明确：第一版按阶段派发，不做全专家广播；需定义粗分流条件、二次派发触发条件与停止条件。
- [ ] RouterAgent 状态机明确：状态集合、转换条件、失败出口、重试/重开会话路径都可被测试与追踪。
- [ ] `REVIEW` 触发条件明确：不是每次都执行，至少覆盖跨专家合并、冲突候选、质量告警、修订操作、失败后重试等风险场景。
- [ ] `PLAN` 数据模型明确：保留 `ExpertPlan` 来源信息，并生成 `MergedPlan` 执行视图，供 review / commit / trace 使用。
- [ ] `MergedPlan` 排序规则明确且固定：至少定义结构、文本/样式、修订、质量复查、保存前检查的优先级，避免运行时随意重排。
- [ ] `ExpertPlan` / `MergedPlan` explanation 字段明确：至少包含 `intent`、`reason`、`riskNote`，并可用于 review、trace、失败复盘与 UI/CLI 展示。
- [ ] 每条 operation 具备稳定 `operationId`，并能关联其来源 `ExpertPlan`、最终 `MergedPlan`、review 结果与 commit 失败定位。
- [ ] operation 级 review 结果具备强类型状态，至少包含 `APPROVED`、`WARNED`、`BLOCKED`、`SKIPPED`，并能驱动 commit、UI/CLI 展示、测试断言与 trace。
- [ ] 只要出现 `BLOCKED` operation，第一版整批停止，不允许部分提交其余 operation。
- [ ] `WARNED` operation 可提交，但必须在最终结果、日志和 trace 中显式暴露 warning 信息，不能被静默忽略。
- [ ] `SKIPPED` operation 必须保留记录与跳过原因，便于说明“为什么未执行”。
- [ ] 重复 operation 去重后也生成显式 `SKIPPED` 记录，至少能表示跳过原因与 `mergedIntoOperationId`。
- [ ] `SKIPPED` 原因枚举明确，第一版至少覆盖 `DUPLICATE_MERGED`、`SUPERSEDED`、`OUT_OF_SCOPE`、`LOW_CONFIDENCE`、`CONFLICT_DROPPED`。
- [ ] `WARNED` / `BLOCKED` 原因枚举明确。第一版至少为 `WARNED` 覆盖 `QUALITY_RISK`、`LOW_CONFIDENCE`、`POTENTIAL_CONFLICT`、`PARTIAL_CONTEXT`；为 `BLOCKED` 覆盖 `UNSAFE_CONFLICT`、`MISSING_REQUIRED_CONTEXT`、`OUT_OF_SCOPE`、`QUALITY_GATE_FAILED`。
- [ ] `QualityAgent` 输出与统一 review 模型直连：质量失败映射为 `BLOCKED(QUALITY_GATE_FAILED)`，质量风险映射为 `WARNED(QUALITY_RISK)`，并保留 rule code / explanation。
- [ ] review 结果字段明确：至少包含 `reviewStatus`、`reviewReason`、`ruleCode`、`explanation`，并可用于测试断言、UI 展示、统计与 trace。
- [ ] `ExpertPlan` / `MergedPlan` 顶层带 `schemaVersion`，第一版固定为 `1`，并为后续 schema 演进预留兼容路径。
- [ ] `DocumentSnapshot` 顶层带 `snapshotVersion`，第一版固定为 `1`，并与 plan schema 版本独立演进。
- [ ] `DocumentSnapshot` 基线元数据明确：至少包括 `sourcePath`、`conversationId`、`createdAt`、`sourceLastModified`，并能用于快照过期判断。
- [ ] `DocumentSnapshot` 强一致性校验字段明确：第一版至少包含 `sessionGeneration`，并定义其在 close/reopen/reset 下的递增规则。
- [ ] 第一版不依赖 `docFingerprint`；快照过期判断基于 `conversationId`、基线元数据与 `sessionGeneration` 完成。
- [ ] 高层结果对象默认只返回摘要；完整阶段产物仅在 debug 模式或低层 API 暴露，避免高层接口过重。
- [ ] 高层摘要结构明确：除 `summaryText` 外，至少提供执行/警告/跳过/阻断计数与精简操作清单（如 `operationId`、`status`、`shortLabel`、可选 `reason`）。
- [ ] 当前父任务拆出可独立验收的子任务，且每个子任务写清自己的范围与依赖。
- [ ] 规划不要求多个子代理直接并发写同一个 `docId`。

## Out of Scope

- 第一阶段不做真正的多线程并发写同一份 `XWPFDocument`。
- 第一阶段不改造 nondocx-core 的 POI 封装为事务模型。
- 第一阶段不要求所有 toolkit 工具都完成专家化 prompt，可先选高价值工具组试点。

## Open Questions

- 当前无阻塞性开放问题。后续若实现中发现新的协议/回退风险，再在对应子任务中增补。

## Decisions

- 2026-07-09：选择 `CommitCoordinator` 作为唯一写入边界。RouterAgent 只负责调度、合并与冲突判断，不直接调用 toolkit 写工具；子代理只产出计划或只读分析，同一 `docId` 的写操作由 `CommitCoordinator` 串行提交。
- 2026-07-09：选择双层 edit plan。子代理输出 JSON plan，便于 LLM 生成和 prompt 约束；RouterAgent 解析、校验并转换成强类型 Java plan；`CommitCoordinator` 只接受强类型 plan，不直接消费 LLM JSON。
- 2026-07-09：选择分层冲突检测。第一层用粗粒度 `ConflictKey` 锁定同一目标（例如段落、run、单元格、修订 id），第二层按 operation 类型与字段级意图判断是否可自动合并；不可证明安全时不自动提交。
- 2026-07-09：选择混合读取模式。RouterAgent 先生成基础快照/overview 给子代理，减少后台子代理直接触碰 live `Document`；子代理确需细节时，通过 `ReadCoordinator` 串行或限流调用只读 toolkit 工具补充上下文。
- 2026-07-09：选择分层基础快照。RouterAgent 默认提供 `DocumentSnapshot`，覆盖 overview、索引地图、短文本预览和结构摘要；不把完整文档内容一次性塞进上下文，细节通过 `ReadCoordinator` 按需补读。
- 2026-07-09：选择强类型 `DocumentSnapshot`。快照先建成 Java 类型，再序列化给 prompt 使用；事实源在 Java 对象，不在 prompt 文本。
- 2026-07-09：`DocumentSnapshot` 第一版默认只到 paragraph / cell 级，不带 run 级明细；run 细节统一经 `ReadCoordinator` 按需补读，以控制 token 与快照体积。
- 2026-07-09：第一版子代理按 toolkit 工具组专家拆分，不做按任务类型的横切子代理。这样工具权限、教程、prompt 边界与现有代码结构一致，便于分批实现和验证。
- 2026-07-09：`SessionTools` 不做独立 `SessionAgent`。文档打开、保存、关闭和 session 生命周期由 orchestration/coordinator 层掌控，不交给 LLM 子代理决策。
- 2026-07-09：`QualityAgent` 第一版作为软闸门。它输出分级质量审查结果（如 `BLOCKING` / `WARNING`），但不直接硬阻止提交；是否继续执行由 RouterAgent 综合用户意图、风险等级与操作类型决定。
- 2026-07-09：子代理只产出本工具组的原子操作 plan，不允许跨工具组复合 plan。跨组组合、排序、冲突解决与保存时机由 RouterAgent / coordinator 统一负责。
- 2026-07-09：`CommitCoordinator` 第一版采用非事务语义。提交按顺序执行，遇错即停，不承诺自动回滚；必须把已执行步骤、失败点和会话后续建议显式返回给 RouterAgent。
- 2026-07-09：提交失败后不在当前 live session 上继续补救。RouterAgent 必须关闭当前会话并从磁盘重新打开文档，再发起新一轮分析与提交，避免在半修改内存态上继续叠补丁。
- 2026-07-09：不保留旧的单 Agent 直连全部工具路径，也不做 runtime fallback。examples、demo 和后续主入口统一迁移到 RouterAgent 多子代理实现。
- 2026-07-09：迁移时不保留兼容壳层。除内部实现切换外，对外入口形态也一并重写，不要求继续兼容当前 examples/demo/API 使用方式。
- 2026-07-09：主入口采用新的高层 facade（如 `DocxOrchestrator`），不直接改造 `DocxToolkit` 为编排层入口。`DocxToolkit` 继续承担底层工具聚合职责，RouterAgent/Coordinator 属于上层 orchestration。
- 2026-07-09：新 facade 同时暴露高层与低层两套 API。高层 `run(...)` / `chat(...)` 用于 demo 与默认使用路径，低层 `analyze(...)` / `plan(...)` / `commit(...)` 用于测试、调试、回放和精细控制。
- 2026-07-09：第一版 `DocxOrchestrator` 支持多轮 conversation memory，不限制为单轮任务执行。交互式入口需要能记住上轮上下文，服务真实编辑会话。
- 2026-07-09：第一版 memory 绑定单活跃文档会话。一个 conversation memory 只服务一份文档，不支持在同一 memory 中并行或混合操作多个 `docId`。
- 2026-07-09：若用户切换到另一份文档，系统必须开启新会话，不复用旧 memory。这样可避免旧文档索引、编辑历史和失败状态污染新文档上下文。
- 2026-07-09：对外显式暴露会话标识。调用方需要能拿到并传回 `conversationId/sessionId`，以决定是续聊同一会话还是创建新会话。
- 2026-07-09：底层 `docId` 不对外暴露。调用方只看到 orchestrator 层的会话标识与高层结果，避免绕过编排层直接依赖 toolkit 活文档句柄。
- 2026-07-09：`ReadCoordinator` 采用有限并发。第一版不走完全串行，也不允许对 live 文档做无界并发只读；需要按文档和全局两级限流来平衡安全与异步收益。
- 2026-07-09：`ReadCoordinator` 第一版默认限流参数为 `per-doc = 1`、`global = 4`。同一 live 文档始终只允许一个补读槽位，全局最多四个补读任务并发。
- 2026-07-09：RouterAgent 第一版采用分阶段派发。先基于用户意图和 `DocumentSnapshot` 做粗分流，再并发唤起必要专家；只有在首轮结果显示需要时，才进行第二轮增量派发。
- 2026-07-09：RouterAgent 第一版采用显式状态机。流程至少包含 `ANALYZE -> PLAN -> REVIEW -> COMMIT -> DONE/FAILED`，便于调试、trace、测试和 UI/CLI 展示。
- 2026-07-09：`REVIEW` 第一版采用条件触发，而不是每次强制执行。至少在跨专家 plan 合并、冲突候选、质量告警、修订相关操作、失败后重试等高风险场景进入。
- 2026-07-09：`PLAN` 第一版采用双层结构。系统保留每个专家的 `ExpertPlan` 作为来源事实，再由 RouterAgent 生成合并后的 `MergedPlan` 作为统一执行视图。
- 2026-07-09：`MergedPlan` 第一版采用固定优先级排序。默认顺序为“结构变更 -> 文本/样式变更 -> 修订相关操作 -> 质量复查 -> 保存前检查”；提交/保存/关闭属于 coordinator 生命周期动作，不混入专家 plan 排序。
- 2026-07-09：`ExpertPlan` / `MergedPlan` 第一版保留 explanation。每条计划至少记录用户意图映射、操作理由和风险说明，便于 review、trace、失败复盘与 UI/CLI 展示。
- 2026-07-09：`ExpertPlan` / `MergedPlan` 第一版为每条 operation 分配稳定 `operationId`。系统需要维护专家提案项到合并执行项的来源映射，便于冲突检测、review 批注、失败定位与日志关联。
- 2026-07-09：`REVIEW` 第一版对每条 operation 产出强类型状态，至少包含 `APPROVED`、`WARNED`、`BLOCKED`、`SKIPPED`。review 结果不能只靠自由文本表达。
- 2026-07-09：第一版只要 `MergedPlan` 中存在任一 `BLOCKED` operation，则整批停止，不进入 `CommitCoordinator` 执行。`BLOCKED` 是真正的提交闸门，而不是提示性标记。
- 2026-07-09：`WARNED` operation 第一版允许继续提交，但 warning 必须显式保留在 review 结果、最终返回、日志和 trace 中，不能静默吞掉。
- 2026-07-09：`SKIPPED` operation 的语义是“被显式识别并跳过，但保留记录与原因”。它不是无痕消失，也不是单纯的合并副作用。
- 2026-07-09：重复 operation 被去重吸收时，也生成显式 `SKIPPED` 记录，并关联 `mergedIntoOperationId`。这样来源链与未执行原因都可追踪。
- 2026-07-09：第一版为 `SKIPPED` 定义小型原因枚举集，至少包含 `DUPLICATE_MERGED`、`SUPERSEDED`、`OUT_OF_SCOPE`、`LOW_CONFIDENCE`、`CONFLICT_DROPPED`。
- 2026-07-09：第一版也为 `WARNED` / `BLOCKED` 定义小型原因枚举。`WARNED` 至少覆盖 `QUALITY_RISK`、`LOW_CONFIDENCE`、`POTENTIAL_CONFLICT`、`PARTIAL_CONTEXT`；`BLOCKED` 至少覆盖 `UNSAFE_CONFLICT`、`MISSING_REQUIRED_CONTEXT`、`OUT_OF_SCOPE`、`QUALITY_GATE_FAILED`。
- 2026-07-09：`QualityAgent` 第一版结果直接映射进统一 review 模型。质量门禁失败直接产出 `BLOCKED(QUALITY_GATE_FAILED)`，质量风险直接产出 `WARNED(QUALITY_RISK)`，不再保留独立平行状态层。
- 2026-07-09：第一版 review 结果保留强类型 `ruleCode` 字段。具体命中的质量规则、冲突规则、上下文规则或安全规则需要可机器读取，不只存在于 explanation 文本中。
- 2026-07-09：`ExpertPlan` / `MergedPlan` 第一版显式携带 `schemaVersion=1`。版本字段同时存在于 Java 强类型与 JSON 表示中，为后续 plan schema 演进预留兼容空间。
- 2026-07-09：`DocumentSnapshot` 第一版显式携带 `snapshotVersion=1`。版本字段同时存在于 Java 强类型与 JSON 表示中，与 plan schema 版本独立演进。
- 2026-07-09：`DocumentSnapshot` 第一版显式保留基线元数据，至少包含 `sourcePath`、`conversationId`、`createdAt`、`sourceLastModified`，用于判断快照是否过期、是否跨会话失效。
- 2026-07-09：`DocumentSnapshot` 第一版增加强一致性校验字段 `sessionGeneration`。每次 close/reopen/reset 后代次递增，用于识别旧快照并阻止其继续驱动 plan/review。
- 2026-07-09：第一版不引入 `docFingerprint`。快照一致性先由 `conversationId`、基线元数据与 `sessionGeneration` 保证，避免提前引入文件指纹语义与计算成本。
- 2026-07-10：`DocxOrchestrator` 高层 `run(...)` / `chat(...)` 默认只返回高层摘要。完整阶段产物只在 debug 模式或低层 API 中暴露，兼顾易用性与可观测性。
- 2026-07-10：高层摘要结果除自然语言总结外，还显式包含精简操作清单与统计字段，至少覆盖执行/警告/跳过/阻断计数，以及按 operation 汇总的精简状态视图。
- 2026-07-10：高层摘要默认展示“本轮新增结果”，不混入整个会话累计视图；累计视图保留给 debug 模式、低层 API 或后续专门的会话汇总能力。
