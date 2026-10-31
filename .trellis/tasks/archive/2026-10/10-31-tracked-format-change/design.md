# Design：tracked.set_style op + 修订观测面呈现

前置：两份 research（`research/compiler-eval-research.md`、`research/full-core-rprchange-research.md`）是本设计的事实基础，文件内均有精确行号。

## 1. 总体分层

```
edit_document(ops=[{kind:"tracked.set_style", ...}])        ← CompilerTools @ToolDef 描述/示例/Nested
  └─ PatchEngine.planOp → planTrackedSetStyle（新）           ← preflight 全校验、claimAnchor 独占
       └─ 执行期：find 定位 → 拆 run（copyRun 倒序先例） → 快照 → run.bold()/italic() → commitStyleAsTracked
            └─ core（零改动）：Run.commitStyleAsTracked / TrackedChangeNodes.commitRPrChange
读侧（写读同批）：
  a) compiler 面 MarkdownView：文档级修订元数据行（新）
  b) full 面 view DocumentViewService：REVISION 详情 PropertyChangeDetails 摘要（补分支）
  c) core 读侧（零改动）：trackedChanges().list() → RPR_CHANGE + PropertyChangeDetails
```

## 2. op 规格：`tracked.set_style`

### 参数

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| `anchor` | string | 是 | 段落锚点 `"pN"`（与既有 op 一致） |
| `find` | string | 是 | 段内目标文本，精确子串（`paragraph.text().contains` preflight；与 tracked.delete/replace 同模型） |
| `bold` | boolean | 否* | 显式出现即设置：true→`w:b w:val="on"`；false→`w:val="off"`（取消）；缺省=不动 |
| `italic` | boolean | 否* | 同上（w:i） |
| `author` | string | 是 | 修订作者，非空白（`author()` 助手） |

\* bold 与 italic 至少显式给一个，否则 preflight 拒绝：「未指定任何格式变更；需至少提供 bold 或 italic（true 设置 / false 取消）」。

### preflight（planTrackedSetStyle）

1. anchor 合法、段落存在（Snapshot.requireParagraph）。
2. `str(find)` 非空；`p.text().contains(find)` 未命中则 PatchException，消息内教学正确写法（照 set_style「错误消息就地教全写法」惯例，PatchEngine:501-513 式样）。
3. `author()` 非空白。
4. bold/italic 至少一个（见上）。
5. **claimAnchor 参与独占**（D4）：与 tracked.*/replace_text 同列——理由：执行期拆 run 重建段落 run 结构，同批其他改文本 op 持有的 run 定位（按 index/text 快照）可能失效，保守拒绝并提示拆批。

### 执行语义（execute）

1. 在目标段落内按 `find` 定位：与 planTrackedFindOp（PatchEngine:727-782）同模型——多处命中**全部处理**（从右到左应用，防偏移漂移，07-22 design 的文本定位模型一致）。
2. 对每个命中片段：若片段与邻文同 run（部分覆盖），先拆 run——复用 `replaceFormattedInRun`（PatchEngine:372-409）的 copyRun 深拷贝 + **倒序插入**技法（顺序拷贝会格式累积，测试抓过的真实坑），拆出「命中片段独占的 run」（完整 rPr 继承）。
3. 对每个目标 run：`RunStyle before = run.style()`（拆分后取，拷贝继承源样式）→ `if (bold 显式) run.bold(bold); if (italic 显式) run.italic(italic);` → `run.commitStyleAsTracked(author, before)`。
4. `changed.add(anchor)`；无 warnings 分支（无可预期警告）。
5. 幂等边界：对已含 rPrChange 的 run 再次 commit 会追加新 id 的 rPrChange（core 语义如此），不做特判——文档注明即可。

### 分发注册（两处）

- `planOp` switch 加 case（PatchEngine:202-203 旁）。
- 未知 op 错误消息的可用 op 列表（PatchEngine:206-214）追加。
- PatchEngine 类 javadoc 的「op 集（v4，12 种）」列表同步 → v5，13 种。

### 命名理由（D1）

`tracked.set_style`：修订族前缀 `tracked.*`（insert/delete/replace 既有命名空间）+ 非修订面既有格式 op 名 `set_style`，语义对称（「修订版 set_style」）。备选 `tracked.format` 无对称先例，弃。

## 3. 读侧观测（写读同批落地）

### 3a. MarkdownView（compiler 面 read_document）修订元数据行

- 位置：文档渲染**尾部**追加区块（页眉元数据行 `<!-- 页眉: … -->` 在头部的先例对应；修订不打断正文阅读）。
- 形态（每条修订一行，HTML 注释不污染 markdown 语义）：

```
<!-- 修订 2 条 -->
<!-- 修订#0: INS author=甲 「插入文本摘…” -->
<!-- 修订#1: RPR_CHANGE author=甲 属性=RUN_PROPERTIES 新[b] 旧[] -->
```

- 数据源：`doc.trackedChanges().list()`（core 读侧已完备）；TEXT 家族给 snippet（既有 describeRevision/outline 先例取法），PROPERTY 家族给 `property=RUN_PROPERTIES 新[newSummary] 旧[oldSummary]`；CELL 家族给 type+author。
- 无修订时**不输出任何行**（零噪声，AC3）。条数行与每条行都带 `修订` 关键字便于复审核对。
- live/reopen：trackedChanges() 读 live 文档即可（rPrChange 不依赖 reopen 才可见——§6.13 的 ins 文本坑不适用；写 op 测试内直接断言）。

### 3b. DocumentViewService REVISION 详情（full 面 view）

- 现状（DocumentViewService.java:519-548）：`details instanceof TextChangeDetails` 才放 text/bold/italic；PropertyChangeDetails 分支无属性证据。
- 补法：PropertyChangeDetails 分支输出 `property` 字段：`RUN_PROPERTIES 新[b] 旧[]`（kind + newSummary + oldSummary 直拼，与 full 面 list_tracked_changes 的 appendDetails（TrackedChangeQueryTools:460-475）`property=RUN_PROPERTIES(新 [b]/旧 [])` 口径对齐，仅 view 侧结构化）。
- 不改 PropertyChangeDetails 本体（D6）；不动既有 TextChangeDetails 分支（AC4 不回归）。
- outline recent[]（RevisionEntry）已有 bold/italic 恒出现（§6.13 修复）；其数据源对 RPR_CHANGE 条目的填充现状由实现时核实，若 outline 侧 RPR_CHANGE 条目 snippet/bold/italic 为空且改动小（同文件族）则补齐，否则记已知边界——不阻塞主线。

## 4. eval 任务：`tracked-bold-phrase`

- 入组：`EvalTasks.buildTasks()` 尾部 append（baseline 25→26）；`EvalTasksTest.hasTwentyFiveUniqueTasks` → 26（方法名数字同步）；capabilityGap 定义序插 `marksKnownCapabilityGapTasks` containsExactly 尾部（header-write-text 之后）。
- seed：与 `bold-existing-phrase`（EvalTasks:807-857）同构——两段，第二段**单 run**「本月风险指标：逾期率 3.2%，…」（目标词与邻文同 run → full 面物理不可达：mark_style_change_tracked 按 run_index 定位、无拆 run）。
- prompt（不提工具名，spec 硬约束 agent-single ~209；措辞参照 tracked-replace-format:861 的「修订方式」表述）：

  > 以修订方式把第二段中的「逾期率」一词加粗，保留修订痕迹供审阅者确认，其他内容一律保持不变。

  （不含 author 指定——check 不锁 author 值，只锁非空；author 是 op 必填参数，模型自会带。）
- check（save+reopen 口径，无 LLM-as-judge）：
  1. 存在 RPR_CHANGE 且其 PropertyChangeDetails.newSummary 含 `b`、oldSummary 不含 `b`；且**不存在 DEL 修订**（堵 tracked.replace 身份替换 workaround，§6.14）。
  2. 目标词已加粗 + 严格子集防退化（抄 829-838：段内有目标词以外文字时必须存在非粗 run）。
  3. 其余文字未被误加粗（抄 840-849）。
  4. 段落文本未变（抄 850-855）。
  5. 防恒真：未改 seed 上至少一 check 失败（RPR_CHANGE 不存在即挂，天然满足）。
- capabilityGap：**初判 true**（D7）；复跑后若证据显示 full 面可过（理论上不可：无 run 拆分）再改。
- 提示词双处：`EvalRunner.COMPILER_SYSTEM_PROMPT`（68-94）+ demo `AgentBridge` 同口径加指引句：「用户要求以修订/留痕方式改变既有文字格式（加粗/斜体）时用 `tracked.set_style`」。

## 5. 兼容性 / 回滚

- 新 op 纯增量：不改既有 12 op 行为；op 集版本 v4→v5（docs/07 + PatchEngine javadoc）。
- MarkdownView 修订行只在「文档含修订」时输出，既有 read_document 测试输出不变（零回归风险点：既有快照测试若有整文断言，需核实——实现时跑全量测试验证）。
- DocumentViewService 只加分支不改旧分支。
- eval baseline 追加任务不改旧任务定义；计数锁/顺序锁同步。
- 回滚：单 commit 粒度 revert 即可（代码提交与文档提交分开，先例 5fd458c/e98d9f6）。

## 6. 已知边界（记档，不修）

1. 取消加粗方向不可从摘要区分（summarize 只看本地名，newSummary==oldSummary==[b]）；方向证据 = run 实际属性（read_document 的 `**` 呈现 / view bold/italic）。
2. RunStyle 三态压平（「未设置」与「显式 off」同为 false）：reject「取消加粗」后回到「无 b」而非「显式 off」，与 full 面一致。
3. date 由 core 固定 `Calendar.getInstance()`，op 层不可覆盖（与 tracked.* 一致）。
4. 重复对同 run commit 会追加新 rPrChange（core 语义），op 不去重。

## 7. 提交与验证策略（对齐执行要求 R5）

1. 代码提交（`feat(toolkit,demo,examples)` scope）：spotless:apply → 全量 `mvn test` 绿 → commit。
2. `mvn install` → 复跑 baseline 全量（26 条，`--surface compiler --group baseline`）→ runId=`target/eval/<id>`；报告带 @commit。
3. 文档提交（`docs(07,11),spec(agent-single)`）：docs/07、docs/11 §6.16、spec agent-single（Convention+基线计数+runId 链式追加），body 附 runId 与通过率。
4. eval report 文件本身不入库（target/ 产物；先例 5fd458c 未入库 report，实现时 `git show 5fd458c --stat` 复核）。
5. 归档任务 + journal 提交（`chore(task)` / `chore`）。
