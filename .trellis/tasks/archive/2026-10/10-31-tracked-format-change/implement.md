# Implement：tracked.set_style + 观测面 + eval 执行清单

前置阅读顺序：implement.jsonl 所列 spec/research → prd.md → design.md → 本文件。
通用约束：子代理禁止 `git commit/push/merge`（提交由主会话完成）；中文错误消息；所有修订断言 save+reopen 口径。

## 切片 1：PatchEngine op（核心写侧）

- [x] 1.1 `PatchEngine.planTrackedSetStyle`：参数校验（anchor/find/author/bold|italic 至少其一）+ claimAnchor 参与独占 + PlannedOp（find 多处命中从右到左、copyRun 拆 run 倒序插入、拆分后快照 → 改 bold/italic → commitStyleAsTracked）。
- [x] 1.2 `planOp` case + 未知 op 错误消息列表 + 类 javadoc「op 集 v5，13 种」。
- [x] 1.3 PatchEngineTest ≥4 个（参照 trackedReplaceWithInlineFormatCreatesOneInsWithStyledRuns:557-621 三层断言式样）：
  - 目标词与邻文同 run：save+reopen 后 1 条 RPR_CHANGE（author/newSummary 含 b/oldSummary 不含 b）+ 目标词加粗 + 邻文非粗 + text() 不变；
  - accept 后 bold 保留且修订消失；reject 后还原（均 save+reopen）；
  - preflight 拒绝三态：bold/italic 均缺省 / find 未命中（消息含教学）/ 同锚点同批与 replace_text 冲突提示拆批；
  - 多处命中全部处理 + italic(false) 取消路径（w:val="off"，reopen 后 isItalic=false 且仍有 RPR_CHANGE）。
- [x] 1.4 `mvn -q -pl nondocx-toolkit test -Dtest=PatchEngineTest` 过。

## 切片 2：读侧观测（写读同批，与切片 1 同一提交批）

- [x] 2.1 `MarkdownView`：文档尾修订元数据行（`<!-- 修订 N 条 -->` + 每条 `<!-- 修订#id: TYPE author=… … -->`，PROPERTY 类带 `属性=RUN_PROPERTIES 新[…] 旧[…]`，TEXT 类带 snippet，无修订零输出）。
- [x] 2.2 `DocumentViewService` REVISION 详情：PropertyChangeDetails 分支输出 property 摘要（与 list_tracked_changes 口径对齐）；核实 outline recent[] 对 RPR_CHANGE 的 snippet/bold/italic 填充现状，小改可补则补，否则记边界。
- [x] 2.3 测试：MarkdownView 含 rPrChange 文档输出修订行断言 + 无修订文档零输出断言；view 详情 RPR_CHANGE 摘要断言 + TextChangeDetails 不回归断言。
- [x] 2.4 `mvn -q -pl nondocx-toolkit test` 过（含既有 MarkdownView/view 全量回归）。

## 切片 3：工具描述 + 提示词

- [x] 3.1 `CompilerTools.editDocument`：description 增 op 句 + ops 示例 JSON 增 tracked.set_style + `@NestedParamCapability` 清单。
- [x] 3.2 提示词双处同口径：`EvalRunner.COMPILER_SYSTEM_PROMPT` + demo `AgentBridge`（「以修订/留痕方式改既有文字格式 → tracked.set_style」）。
- [x] 3.3 CompilerToolsTest 相应断言同步（若锁描述文本）。

## 切片 4：eval 入组

- [x] 4.1 `EvalTasks.buildTasks()` 尾部 append `tracked-bold-phrase`（seed/prompt/check 按 design §4；check 含「RPR_CHANGE 存在 + 无 DEL」防 workaround 与严格子集防退化）。
- [x] 4.2 `EvalTasksTest`：`hasTwentyFiveUniqueTasks` → 26（方法名同步）+ `marksKnownCapabilityGapTasks` containsExactly 尾部插 `tracked-bold-phrase`。
- [x] 4.3 `mvn -q -pl nondocx-examples test -Dtest=EvalTasksTest` 过（含防恒真 everyTaskFailsAtLeastOneCheckOnUnmodifiedSeed）。

## 切片 5：质量门禁 + 代码提交（主会话）

- [x] 5.1 `mvn spotless:apply` 后全量 `mvn test` 全绿。（trellis-check 执行：core 368 / toolkit 284 / examples 19 / demo 47，BUILD SUCCESS）
- [x] 5.2 主会话提交：`feat(toolkit,demo,examples): 以修订方式变更既有文字格式的 op(tracked.set_style)与修订观测呈现`（commit 0c5de5f）。

## 切片 6：eval 复跑 + 文档（主会话）

- [x] 6.1 `mvn install -q`（skipTests，测试已于 5.1 全量绿）→ 复跑 `--surface compiler --group baseline`（26 条，runId 20261031-092431-976 @0c5de5f）：**26/26 全过，平均 4.23**；tracked-bold-phrase PASS（4 次调用，首个 op 一步命中，复审直接引用 rpr_change 证据判达成）。capabilityGap=true 维持（依据 bold-existing-phrase 同根因结构性归因；full 面本批未复跑，已在 docs/11 §6.16 与 spec 注明）。
- [x] 6.2 文档三件：docs/07（v5 op 枚举/JSON/描述段 + read_document 修订行说明）、docs/11 §6.16、spec agent-single（compiler surface Convention 两条 + 基线 26 条 + gap 11 条 + runId 链式追加）。
- [x] 6.3 提交：`docs(07,11),spec(agent-single): tracked.set_style op 文档与修订观测面呈现`。

## 切片 7：收尾（主会话）

- [ ] 7.1 归档任务（task.py archive）。
- [ ] 7.2 journal 追加（决策点 + runId + 结果）。
- [ ] 7.3 提交 `chore(task),chore: 归档 10-31-tracked-format-change 与 journal`。

## 回滚点

- 切片 5.2 前：全部改动未提交，`git checkout -- .` 即净。
- 切片 6.3 前：代码已提交，文档独立可 revert。
- eval 复跑失败处置：先修代码再重走 5→6（runId 须对应最终代码 commit）。

## 验证命令速查

```bash
mvn -q -pl nondocx-toolkit test -Dtest=PatchEngineTest
mvn -q -pl nondocx-toolkit test
mvn -q -pl nondocx-examples test -Dtest=EvalTasksTest
mvn spotless:apply && mvn test
mvn -q -pl nondocx-examples -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain exec:java -Dexec.args="--surface compiler --group baseline"
```
