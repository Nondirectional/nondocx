# Research: test conventions — save+reopen 锁定测试与 tracked changes 测试清单

- Query: 现有测试惯例（save+reopen 锁定、partial_failure 断言、三段文档+move 对+apply 的现成构造）
- Scope: internal
- Date: 2026-10-30

## Findings

### save+reopen 锁定测试的标杆：TrackedReplaceFormatWorkaroundTest

`nondocx-toolkit/src/test/java/io/github/nondirectional/docx/toolkit/TrackedReplaceFormatWorkaroundTest.java`（**在 toolkit 模块**，docs/11 §6.14 锁定的先例）。结构（31-95 行）：

1. seed：`try (Document seed = Docx.create()) { seed.addParagraph(...); seed.save(file); }`（33-36 行）；
2. live 会话：`DocxToolkit tk = new DocxToolkit()`；`docId = ToolTestSupport.extractDocId(tk.session.openDocx(...))`（38-41 行）；
3. 工具链操作（44-61 行，直接调 `tk.trackedChangeAuthoring.xxx` / `tk.body.xxx`）；
4. 落盘：`tk.session.saveDocx(docId, out)`，`assertThat(ToolTestSupport.isFailure(saveResult)).isFalse()`（63-65 行）；
5. **eval 口径断言**：`try (Document doc = Docx.open(out)) { ... }`——修订列表、`ins.raw().getRList()`、`doc.paragraphs().get(0).text()` 全在 reopen 文档上断言（67-94 行）。

注释明言「按 eval 口径（save+reopen 读落盘文档）」。这是「内存 vs XML 分裂测不出——纯内存断言测不出这类问题」教训（docs/11 §6.8 copyRowTo）的固化写法。

### core 侧测试惯例（TrackedAuthoringTest / TrackedAdvancedTypesTest）

- **live vs reopen 双段断言**：`TrackedAuthoringTest.java:120-143`——live `text()` 断言不含 ins 文本（iruns 未重建），再 save → `Docx.open(file)` 断言 reopen 后 text() 含 ins 文本。注释直接引用 §6.13 live/reopen 语义差异。
- **POI 裸构造 move 对**（现成可抄的「预置 move 修订文档」写法）：`TrackedAdvancedTypesTest.java:32-57`（movePairIsReadable）与 `:61-93`（acceptMovePairAppliesBothEnds）——`poi.getDocument().getBody().addNewP()` → `p.addNewMoveFrom()`（id/author/date + `addNewR().addNewDelText()`）→ `p.addNewMoveTo()`（同型 + `addNewT()`）→ `poi.write(out)` 落盘 → `Docx.open(file)`。
- **accept 联动断言**：`TrackedAdvancedTypesTest.java:83-92`——取 MOVE_FROM 的 id → `accept(fromId)` → `assertThat(doc.trackedChanges().list()).isEmpty()`。
- **孤立 move 边界**：`:95-110`（orphanMoveThrowsOnAccept，断言 NoSuchElementException）。

### toolkit 侧现成测试（与 bug 最接近的三个）

| 测试 | 位置 | 与本 bug 的关系 |
|---|---|---|
| `shouldMoveRunTracked` | `DocxToolkitTrackedChangesTest.java:386-403` | 已有「core 创作两段 + move_run_tracked + list 读回 MOVE_FROM/MOVE_TO」链路；**缺 accept 与 live view 断言**——本任务锁定测试的自然扩展点 |
| `shouldGetAndAcceptRevisionByRefThenReportRemoved` | 同文件 `:433-460+` | 按 ref accept 后 list 报已移除的断言惯例 |
| `shouldRejectWrongFamily` | 同文件 `:405-431` | PARTIAL_FAILURE 断言惯例：`ToolTestSupport.parse(r).code() == ToolResultCode.PARTIAL_FAILURE` + `r.contains("失败 1 条")` |

### partial_failure / 返回串断言惯例

- `ToolTestSupport.parse(result).code()` 对比 `ToolResultCode.PARTIAL_FAILURE`（DocxToolkitTrackedChangesTest.java:426-428）；
- 字符串 contains 断言：`"失败 1 条"`、`已应用`、`ref=`（`:429`、TrackedReplaceFormatWorkaroundTest 同风格）；
- 「加成对说明」后的新断言点：对 ids=[move_to, move_from] 的批次，第二端现在产出 `错误(找不到 id 为 ... 的修订)` + `失败 1 条` + PARTIAL_FAILURE——语义说明加进 @ToolDef description（TrackedChangeQueryTools.java:216-220）或 `applyMove` javadoc（TrackedChanges.java:487-503）后，可加断言第二端消息含「整对/配对已完成」类文案（具体文案由 design 定）。

### tracked changes 相关测试文件清单

| 文件 | 模块 | 覆盖 |
|---|---|---|
| `core/api/track/TrackedChangesTest.java` | core | 读侧/accept/reject 基础（含 `acceptDelRemovesDeletedText` :392） |
| `core/api/track/TrackedAuthoringTest.java` | core | ins/del/replaceTrackedRange 创作 + accept 顺序锁定（§6.13） |
| `core/api/track/TrackedAuthoringAdvancedTest.java` | core | 带格式插入/rPrChange/move 创作（10 用例，poi-bridge.md N17 回归面） |
| `core/api/track/TrackedAdvancedTypesTest.java` | core | move/property 高级类型 accept（move 对联动、孤立 move 边界） |
| `core/api/track/TrackedCellTypesTest.java` | core | cellIns/cellDel/cellMerge |
| `toolkit/DocxToolkitTrackedChangesTest.java` | toolkit | 工具面：list/get/apply/move_run_tracked/delete_run_tracked 等 |
| `toolkit/TrackedReplaceFormatWorkaroundTest.java` | toolkit | save+reopen 锁定标杆（§6.14） |
| `toolkit/DocxToolkitBatchTest.java` | toolkit | 批量面引用 applyTrackedChanges |
| `toolkit/compiler/PatchEngineTest.java` | toolkit | tracked.insert/replace 编译器路径（§6.13 迁移顺序锁定） |
| `demo/ReviewSubAgentTest.java` / `demo/DirtyDetectionTest.java` | demo | 集成面 |

### 「三段文档 + move 对 + apply」现成辅助方法

**没有**共享 helper；各测试就地构造（两种流派：POI 裸构造落盘再 open——TrackedAdvancedTypesTest；core/toolkit 链路创作——DocxToolkitTrackedChangesTest.shouldMoveRunTracked）。新增锁定测试按 TrackedReplaceFormatWorkaroundTest 的 seed→open→工具链→saveDocx→reopen 结构写即可；崩溃断言需要**在 save 之前的 live 文档上读**（reopen 后缓存重建，永远不炸——这正是该 bug 只在 live 会话出现的原因）。

### 相关 spec / docs

- `.trellis/spec/backend/poi-bridge.md:506`（N17 moveXml 断连条目）、`:510`（accept/reject 后 POI 缓存失效须知——本任务要修的已知边界）
- `.trellis/spec/backend/agent-single.md:208`（TODO #009 记档）、`:294-296`（「靠 id 重查活句柄；accept/reject 断言必须 save+reopen」惯例）
- `docs/11-agent-authoring-architecture.md:910-917`（§6.14 bug 原始记档）
- `docs/07-toolkit.md:144`（apply_tracked_changes 工具表条目）

## Caveats / Not Found

- 未找到任何既有的「live 读不抛异常」类回归测试（现有面全部绕开 live 读，save+reopen 后断言）——本任务将是第一个正面锁定 live 会话崩溃的测试。
- `DocxToolkitBatchTest` 与 demo 模块测试未逐行核对，仅确认引用关系。
