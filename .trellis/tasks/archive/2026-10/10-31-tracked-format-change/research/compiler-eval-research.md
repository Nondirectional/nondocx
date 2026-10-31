# Research: compiler 面修订格式 op（rPrChange）与配套 eval 任务——落点全图

- Query: compiler 面 op 体系 / bold-existing-phrase 与 eval 体系 / 既有 tracked op 与 core rPrChange 能力 / op 参数惯例 / demo-examples 组织 / docs 03·07·11 主题
- Scope: internal
- Date: 2026-10-31

## 结论速览（对实现者最重要的 5 条）

1. **core 写侧 rPrChange API 已完备，本任务 core 零新增即可起步**：`Run.commitStyleAsTracked(String author, RunStyle previousStyle)`（`nondocx-core/src/main/java/io/github/nondirectional/docx/core/api/text/Run.java:322-339`），两步式用法（javadoc 内有代码示例）：`RunStyle before = run.style()` 快照 → `run.bold()` 链式改 → `run.commitStyleAsTracked(author, before)`。
2. **compiler 面缺口只在 PatchEngine**：已有 `tracked.insert/tracked.delete/tracked.replace`（文本类修订），无任何属性类修订 op。full 面反而已有一步式工具 `mark_style_change_tracked`（`TrackedChangeAuthoringTools.java:568-635`）。
3. **07-22-delete-text-tracked 与本任务不同面**：它是 full 面 `delete_run_tracked` 工具的 target_text 模式扩展；其依赖的 core API（`deleteTrackedRange`/`replaceTrackedRange`）早已落地并被 PatchEngine 使用。本任务可复用的是「模式」不是「代码」。
4. **eval 对照任务 bold-existing-phrase 的完整定义在 `EvalTasks.java:807-857`**，新任务入组需同步改 6 处（见 §5 清单）；baseline 当前 25 条，计数锁 `EvalTasksTest.hasTwentyFiveUniqueTasks`。
5. **读侧观测缺口要警惕**：compiler 面 `read_document`（MarkdownView）当前**完全不呈现修订**（grep tracked/修订/rPrChange 零命中）。spec「写读同批」Convention + §6.6/§6.13 三次复审误报教训直接适用——rPrChange 写 op 落地时需考虑读侧证据（设计决策，留给主会话）。

---

## 1. compiler 面位置与 op 体系

### 模块与包

- 模块：`nondocx-toolkit`，包 `io.github.nondirectional.docx.toolkit.compiler`
- 包内文件：`PatchEngine.java`（op 引擎）、`CompilerTools.java`（工具层 @ToolDef，位于 `toolkit/` 根）、`MarkdownView.java`（read_document 读视图）、`MarkdownParser`/`MarkdownDoc`/`DocumentCompiler`/`RenderPreview`/`StyleProfile`/`TemplateFiller`

### op 定义-注册-分发机制（PatchEngine.java，1103 行）

| 环节 | 位置 | 形态 |
|---|---|---|
| 入口 | `PatchEngine.apply(doc, ops, profile, dryRun)` line 130-161 | 静态方法；先对所有 op 逐条 preflight（`planOp`），任一失败抛 `PatchException` **零写入**；全过后顺序 `execute` |
| 分发 | `planOp()` line 165-216 | `switch (kind)` on `op` 字符串，每 case 调一个 `planXxx()`；default 抛 PatchException 且**消息内列全部可用 op**（line 206-214，新增 op 必须同步这串列表） |
| 计划对象 | 抽象类 `PlannedOp` line 1084-1102 | 每个 `planXxx()` 返回匿名子类：构造参数=describe 字符串，实现 `execute(doc, snap, changed, warnings, profile)` |
| 锚点 | 内部类 `Snapshot` line 1041-1081 | 构造时扫 body 建 `pN`→Paragraph / `tN`→Table 映射；`requireParagraph/requireTable` 校验存在性 |
| 独占锁 | `claimAnchor()` line 219-236 | tracked.*/replace_text 每个**段落锚点每批至多一个**（改文本 op 互斥），违反整体拒绝提示拆批 |

### op 命名惯例（v4，12 种现存）

- 裸动词：`insert` / `replace_text` / `delete` / `set_style`
- `域.动作`：`table.set_cell` / `table.add_row` / `footer.set_page_number` / `header.set_text` / `comment.add`
- 修订族前缀：`tracked.insert` / `tracked.delete` / `tracked.replace` —— **格式修订 op 的自然命名空间是 `tracked.*`**（如 `tracked.set_style`/`tracked.format`，具体名由主会话定）

### header.set_text 完整链路（e98d9f6，新 op 的改动模板）

| 步骤 | 文件:位置 | 内容 |
|---|---|---|
| 1 引擎实现 | `PatchEngine.planHeaderSetText` line 886-910 | 必填校验（text 非空白）→ parseInline 拆 span → PlannedOp（execute 里 `doc.ensureHeader().addParagraph()` + 逐 span `applySpanFormat`） |
| 2 分发注册 | `PatchEngine.planOp` line 202-203 case + line 206-214 错误消息 op 列表 | 两处都要加 |
| 3 工具描述 | `CompilerTools.editDocument` @ToolDef description line 253-272 + ops 参数示例 JSON line 280-289 + `@NestedParamCapability` 清单 line 290-305 | 新参数要加 Nested 声明（如本任务可能加 `ops.tracked_bold` 之类，视 op 参数设计） |
| 4 提示词 ×2 | `EvalRunner.COMPILER_SYSTEM_PROMPT` line 68-94（页眉句 line 86）+ `nondocx-demo/.../AgentBridge.java` 同口径提示词 | 「用户要求 X 时用 Y op」句式 |
| 5 测试 | `PatchEngineTest.headerSetTextAppendsParagraphWithInlineBoldRun` line 882 + `headerSetTextValidationAppendSemanticsAndBatchCoexistence` line 906；另 CompilerToolsTest×1 / CompilerPipelineTest×1 | 断言 changed 含 "header"、save+reopen 后页眉 run isBold |
| 6 eval | `EvalTasks.header-write-text` line 921-971（baseline 第 25 条）+ EvalTasksTest 计数 24→25 + gap 清单尾部 |  |
| 7 文档 | docs/07（op 枚举+JSON 块+描述段）、docs/11 §6.15、spec agent-single（commit 5fd458c 的三文件 diff） |  |

## 2. bold-existing-phrase 与 eval 体系全貌

### bold-existing-phrase（非修订版对照基线）

`nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/eval/EvalTasks.java:807-857`：

- prompt：「把第二段中的「逾期率」一词加粗显示，其他内容一律保持不变。」（不提工具名——spec 硬约束 agent-single ~line 209）
- seed：两段，第二段单 run「本月风险指标：逾期率 3.2%，…」
- check 三条（line 817-855）：
  1. 目标词已加粗 + **严格子集防退化断言**（line 829-838：段内有目标词以外文字时必须存在非粗 run——「整段唯一 run 全加粗不算只加粗该词」，这正是 full 面「无 run 拆分能力」的照妖镜）
  2. 其余文字未被误加粗（line 840-849）
  3. 段落文本未变（line 850-855）
- `capabilityGap=true`（line 856-857，full 面物理不可达：无 run 拆分）
- compiler 面已知通过路径（10-30-format-text-ops summary 实证）：`replace_text find=逾期率 text=**逾期率**` 身份替换（4 次调用一步命中）

### eval 体系结构（全部 Java 代码定义，无 JSON/YAML 任务文件）

`nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/eval/`：EvalTask（模型：id/title/prompt/seed/NamedCheck/capabilityGap，构造器 line 72-97）、EvalTasks（buildTasks=baseline，buildCreateTasks=create 组）、EvalMain（CLI，参数解析+runId 生成+写报告）、EvalRunner（装配+单任务执行）、EvalConfig、EvalResult、EvalAssert、ReportWriter/JsonWriter。

### 计数锁与质量锁（EvalTasksTest.java，143 行）

| 测试 | 行 | 断言 |
|---|---|---|
| `hasTwentyFiveUniqueTasks` | line 24-37 | `EvalTasks.all()` **hasSize(25)**（方法名数字与断言同步改） |
| `marksKnownCapabilityGapTasks` | line 78-98 | capabilityGap id **containsExactly 顺序敏感**，当前 10 条：footer-page-number…`bold-existing-phrase`, `header-write-text` |
| `everyTaskFailsAtLeastOneCheckOnUnmodifiedSeed` | line 112-135 | 防恒真：每任务在未改 seed 上至少一 check 抛 AssertionError |

工具面数量锁（EvalRunnerTest）：full=58、compiler=**8**（current_document + create/read/validate/edit_document + fill_template + render_preview + review_intent；registry 装配见 EvalRunner.baseRegistry line 439-451）。

### baseline 复跑与 runId

```bash
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--surface compiler --group baseline"
```

- 默认 model qwen3.8-27b / temp 0.0 / max-iterations 24 / output `target/eval`（env `NONDOCX_EVAL_*` 可覆盖）
- **runId = `target/eval/<yyyyMMdd-HHmmss-SSS>/`**（EvalMain line 27-28, 81-82），内含 report.md/report.json + 每任务子目录
- report 记 gitCommit → **惯例是先提交代码再复跑**（runId 配 `@<short-hash>` 引用）
- 提交 body 惯例：runId + 面口径（工具数/模型/温度）+ 通过率/平均调用数 + 关键任务行为（先例 e98d9f6/fbf4b17/65ba23a）

## 3. 既有 tracked 能力盘点（core 已有什么、缺什么）

### compiler 面（PatchEngine）——文本修订已有，格式修订没有

- `tracked.insert`（planTrackedInsert line 689-716，core `Paragraph.addInsertion`，text 支持行内标记多 run）
- `tracked.delete` / `tracked.replace`（planTrackedFindOp line 727-782，core `deleteTrackedRange`/`replaceTrackedRange`，find 定位多处命中从右到左应用，replace 的 text 支持行内标记）
- 三者均参与 claimAnchor 同锚点独占
- **无 rPrChange/pPrChange 类 op**——本任务缺口

### core rPrChange 写侧（已完备，直接可用）

| API | 位置 | 说明 |
|---|---|---|
| `Run.commitStyleAsTracked(author, previousStyle)` | Run.java:322-339 | 唯一公开入口；两步式（先 `run.style()` 快照再改再 commit）；author 非空白；返回 this 链式 |
| `RunStyle` | core/api/style/RunStyle.java | 6 槽快照：bold/italic/underline/font/size/color（line 20-28）——bold/italic 起步完全够 |
| 底层 `TrackedChangeNodes.commitRPrChange` | internal/poi/TrackedChangeNodes.java:863-904+ | `rPr.addNewRPrChange()` + 旧值树渲染进 `CTRPrOriginal`（架构层防递归）；date 与 w:id 自动分配 |

### core rPrChange 读侧（已完备）

- `TrackedChangeType.RPR_CHANGE`（core/api/track/TrackedChangeType.java:40，family=PROPERTY）
- `PropertyChangeDetails`（core/api/track/PropertyChangeDetails.java）：`kind()`=RUN_PROPERTIES、`newSummary()/oldSummary()` 紧凑摘要如 `"[b]"`（新旧摘要 equals 即「无实际变化」——eval check 可用来防恒真）
- 枚举入口 `doc.trackedChanges().list()`；accept/reject 走 `acceptProperty/rejectProperty`（Run.java javadoc line 311-313 提及）
- 参考测试：`nondocx-core/src/test/.../track/TrackedAuthoringAdvancedTest.java`；示例：`nondocx-examples/.../TrackedAuthoringAdvancedExample.java`（两文件都用了 commitStyleAsTracked）

### full 面对照（重要：full 面已有一站式工具）

`TrackedChangeAuthoringTools.markStyleChangeTracked`（`nondocx-toolkit/.../TrackedChangeAuthoringTools.java:568-635`）：工具名 `mark_style_change_tracked`，参数 paragraph_index + run_index + author + bold/italic/color（可选），内部「快照→应用→commitStyleAsTracked」一步到位。**但它按 run_index 定位、无 run 拆分**——目标词与邻文同 run 时无法只改该词，这正是 bold-existing-phrase 在 full 面失败的根因，新「修订版加粗」任务在 full 面大概率仍属缺口（按 §6.12 流程复跑归因后再定 flag，勿预设）。

### 07-22-delete-text-tracked（in_progress）判读

- 工件：`.trellis/tasks/07-22-delete-text-tracked/{prd,design,implement}.md`
- 实质：**full 面** `delete_run_tracked` 工具的兼容扩展（edits 增加 paragraph_index+target_text+occurrence 文本定位模式），属 `TrackedChangeAuthoringTools`（@ToolDef 工具体系），与 compiler 面 PatchEngine op 无关
- 其 implement.md 大部分切片已勾选，core 的 `deleteTrackedRange`（Paragraph.java:426）/`replaceTrackedRange`（line 536/552）已提交落地并被 PatchEngine 消费——**本任务不需要等它、也不依赖它任何未完成部分**
- 可借鉴：它 design.md 的「文本定位模型」（indexOf 精确匹配、唯一命中/occurrence、从右到左应用防偏移漂移）与 PatchEngine tracked.* 已实现的行为一致，实现新 op 时直接抄 PatchEngine 侧先例即可

## 4. op 参数形态惯例（新 op 设计的约束集）

- **目标文本定位**：统一 `find` 精确子串（`paragraph.text().contains(find)` preflight + 执行期多处命中全处理），无偏移/range 暴露给模型；锚点统一 `anchor: "pN"`。参照 planTrackedFindOp line 727-782。
- **拆 run 技法**（把既有词单独加粗必需）：`replaceFormattedInRun` line 372-409 用 `paragraph.copyRun(runIndex)` 深拷贝（完整 rPr 继承）+ **倒序插入**（顺序拷贝「上一份拷贝」会格式逐份累积——测试抓过的真实坑）；core `Paragraph.copyRun(int)` 是 10-30-format-text-ops 落地的。
- **必填校验助手**：`str()` line 989-995（非空）、`author()` line 937-943（修订作者必填非空白——tracked op 惯例）、`booleanValue()` line 1024-1036（Boolean 或 "true"/"false"）。
- **错误处理**：preflight 全部走 `PatchException`（消息含 `op[i]` + 原因 + 可用值/正确写法示例，见 set_style line 501-513 的「错误消息就地教全写法」惯例）；执行期无可预期失败分支，意外抛 IllegalStateException。
- **返回值**：`PatchResult{applied, changed(锚点列表), warnings, planned}`；改文本 op `changed.add(anchor)`；header/footer 用哨兵 `"header"`/`"footer"`。
- **同锚点独占**：tracked.*/replace_text 走 claimAnchor。注意：rPrChange 类 op **不改 text()**（只动 rPr + 拆 run 结构），是否入 claimAnchor 是设计决策——若拆 run 会重建 run 结构，与同批 replace_text 组合时 preflight 快照的 run 引用可能失效，实现时需评估（comment.add 的先例是「不改文本就不参与独占」，line 784-786 javadoc）。
- **格式表达**：行内格式统一 Markdown 行内标记（`**粗**`/`*斜*`/`` `代码` ``）经 `MarkdownParser.parseInline` + `applySpanFormat`（line 412-422）；set_style 的 bold/italic 则是显式布尔字段。rPrChange op 的参数形态二选一：`{find, bold:true, author}` 布尔式（贴近 mark_style_change_tracked）或复用行内标记（如 `find`+`text=**同文本**` 的「身份替换修订版」）——由设计阶段定，两者皆有先例。
- **一个必须提前想清的坑**：`commitStyleAsTracked` 是 per-run 两步式（快照→改→commit），而 find 定位的「目标词」可能只是某 run 的一段——新 op 必须**先拆 run 再对目标 run 逐个 commit**，快照要在拆分前取（拆分出的目标 run 的旧样式=源 run 样式）。

## 5. demo / examples 组织与新 eval case 改动清单

### 模块结构

- `nondocx-demo/.../demo/`：AgentBridge（面切换 `SURFACE_COMPILER` 默认/`SURFACE_FULL`，line 67-68；各面 system prompt，compiler 面提示词与 EvalRunner **同口径**——改提示词要两处同步）、DemoServer、DocumentTools、DemoSkills 等
- `nondocx-examples/.../examples/`：`eval/` 包 + 功能 Example 类（新能力配示例的先例：TrackedChangesExample / TrackedAuthoringAdvancedExample / TrackedCellChangesExample；example 非必须，eval 才是本任务硬性配套）

### 新 eval case 完整改动清单（按 header-write-text/bold-existing-phrase 先例）

1. `EvalTasks.java` `buildTasks()` 尾部 append 新 `EvalTask`（baseline 25→26；id kebab-case 对照 `bold-existing-phrase`，tracked 族语义如 `tracked-bold-phrase` 之类由主会话定；prompt 不得提工具名；check 建议：RPR_CHANGE 存在且 author 对 → 目标词已加粗（严格子集防退化，抄 line 829-838）→ 段落文本未变 → **防退化通过：断言无 DEL 修订**（堵「tracked.replace 身份替换+加粗」的 workaround 冒充——§6.14 已实证该 workaround 可过全部非修订 check））
2. `EvalTasksTest.java`：`hasTwentyFiveUniqueTasks` → 26（方法名数字同步）；若 flag 则 `marksKnownCapabilityGapTasks` containsExactly 按定义序插入
3. `EvalRunner.COMPILER_SYSTEM_PROMPT` + demo `AgentBridge` 提示词：加新 op 指引句（「用户要求以修订方式加粗/改格式时用 …op」）
4. `CompilerTools.editDocument` description + ops 示例 JSON + NestedParamCapability
5. `PatchEngine`：planOp case + 未知 op 错误消息列表 + planXxx 实现
6. 文档（对照 5fd458c 三文件）：`docs/07-toolkit.md`（op 枚举 line 222/252 + JSON 块 line 263-268 + 新 op 描述段，参照 line 282-299 tracked.*/header.set_text 段式样）、`docs/11-agent-authoring-architecture.md` 新增 §6.16（**下一个节号，line 929 是 §6.15；不回改旧节**）、`.trellis/spec/backend/agent-single.md`（compiler surface Convention 段 + 评测基线段条数 + runId 结果行链式追加）
7. 测试：PatchEngineTest 至少 2 个（写入+校验/原子性；save+reopen 后 `trackedChanges().list()` 断言 RPR_CHANGE + accept 后 isBold / reject 后还原——参照 trackedReplaceWithInlineFormatCreatesOneInsWithStyledRuns line 557-621 的三层断言式样）；CompilerToolsTest/CompilerPipelineTest 各视情况

## 6. docs/03、07、11 主题与 op 文档格式

| 文档 | 主题 | 本任务相关点 |
|---|---|---|
| `docs/03-api-reference.md` | **core API 速查**（按类型分组方法表；修订/批注方法只列名并指向 05/06） | 若 core 零新增则不用动；commitStyleAsTracked 应已在 tracked 区列出 |
| `docs/07-toolkit.md` | **toolkit=LLM Agent 工具集**文档 | op 文档主战场：line 222 工具表 `edit_document` 行的 op 枚举、line 252「edit_document 的 op 集（v4…）」标题（**版本号要 bump v5**）、line 263-268 op JSON 示例块、line 282-299 各 op 描述段（新 op 加一段，格式参照「**tracked.\*（修订模式）**：…」line 282） |
| `docs/11-agent-authoring-architecture.md` | **Agent 写作架构建议**（编译器路线论证 + §6 评测基线实证日志，line 299 起） | 新批次写 §6.16（line 929 §6.15 之后 append）；高相关旧节：§6.5（footer op 落地+读侧元数据行先例）、§6.6（复审只信可见属性——3 次误报教训）、§6.12（full 面 gap flag 评估流程）、§6.13（tracked 行内格式+live/reopen text() 语义差异）、§6.14（flag 只标物理不可达） |

5fd458c（文档提交）实际改动：docs/07（op 枚举/JSON 块/新描述段/read_document 行补页眉句/HeaderFooterTocTools 口径）、docs/11（新 §6.15）、spec agent-single（compiler surface 两个 Convention 条目 + baseline 25 条 + gap flag 10 条 + 已知边界改写 + runId 链式追加）——本任务文档改动同构。

## 7. 设计阶段必须拍板的问题（调研只列证据，不替决策）

1. **读侧观测**：compiler 面 read_document（MarkdownView）无任何修订呈现。spec「写读同批」Convention（agent-single comment.add 条目 ~line 310：新增署名类能力写读必须同批，否则复审误报——§6.6/§6.13 三次实证）+ header.set_text 的「页眉元数据行」先例（§6.5/§6.15：`<!-- 页眉: … -->` 注释行）指向：rPrChange op 大概率需要配套读侧证据（如 read_document 修订标记行，或至少确认 review_intent 走的 view 工具能看见 RPR_CHANGE——full 面修订观测在 trackedChangeQuery，recent[] 恒带 bold/italic 是 §6.13 的修复）。
2. **live/reopen 语义**：§6.13 教训——ins 文本 live 不可见、reopen 可见；rPrChange 不动文本，`runs()`/`style()` live 应可读新值（rPrChange 修订内 run 是普通 run，非 ins/del 内 run——N14 不适用），但旧值树只能经 trackedChanges 读。eval check 按惯例 save+reopen 口径写。
3. **参数形态**：布尔字段式 vs 行内标记式（§4 两种先例）；op 名（`tracked.set_style`？`tracked.format`？）。
4. **claimAnchor 参与与否**：rPrChange 拆 run 改变 run 结构但不改 text()；同批与 replace_text 共存时 preflight 捕获的 run 引用失效风险（拆 run 后旧 Run wrapper 对应 XML 仍在但文本被改）。保守做法是参与独占（与 tracked.* 同列）。
5. **capabilityGap**：full 面有 mark_style_change_tracked 但无 run 拆分（bold-existing-phrase 同根因）；compiler 面 op 落地后可过。flag 与否按 §6.12 复跑归因流程，先例是「物理不可达才 flag、有 workaround 可过=行为失败不 flag」。

## Caveats / Not Found

- `EvalTasks.java` 类 javadoc 仍写旧任务数（历史遗留，计数真源是 EvalTasksTest）——读代码时勿被注释误导。
- `PatchEngine` 类 javadoc line 29 起「op 集（v4，12 种）」列表也要随新 op 同步（e98d9f6 模式）。
- 未逐一核验 docs/03 中 tracked 方法表当前是否已含 commitStyleAsTracked（本次只读了 03 头部；实现时若 core 零新增则无需触碰 03）。
- full 面 `delete_run_tracked` 的 target_text 模式（07-22）是否已全部上线未逐行验证（与本任务无依赖，不影响）。
