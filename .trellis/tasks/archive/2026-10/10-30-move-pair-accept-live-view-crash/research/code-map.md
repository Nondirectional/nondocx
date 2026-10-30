# Research: code map — apply_tracked_changes / move 修订 / unwrap 全调用链

- Query: apply_tracked_changes 实现类与完整调用链；unwrapRunsAndRemove §6.13 现状；move 修订 accept 逻辑；返回串构造位置
- Scope: internal
- Date: 2026-10-30

## Findings

### 调用链全景（文本/move 类 accept）

```
toolkit TrackedChangeQueryTools.applyTrackedChanges (TrackedChangeQueryTools.java:222)
  └─ target=TEXT_OR_MOVE → applyRevisionsBatch (同文件:482)
       └─ applyRevisionsByIds (同文件:503)          ← 逐 id 循环、collect-errors、partial_failure 汇总
            └─ resolveRevisionInput (同文件:582)     ← "doc:..." 走 RevisionRef，否则 stable id
            └─ action = TrackedChanges::accept (core)
core TrackedChanges.accept(id) (TrackedChanges.java:236)
  └─ applySingle (同文件:444)
       ├─ family==MOVE → applyMove (同文件:504)
       │    ├─ findMoveCounterpart (同文件:531)   ← author+text 匹配（date 只作弱约束）
       │    ├─ TrackedChangeNodes.acceptText(from.raw())   ← moveFrom: removeNode 整包装删除
       │    └─ TrackedChangeNodes.acceptText(to.raw())     ← moveTo: unwrapRunsAndRemove
       └─ family==TEXT → applyOne (同文件:479) → acceptText
internal/poi TrackedChangeNodes.acceptText (TrackedChangeNodes.java:165)
  ├─ local==ins/moveTo → unwrapRunsAndRemove (同文件:226)
  └─ local==del/moveFrom → removeNode (同文件:211)  ← c.removeXml() 删整个包装子树
```

批量 accept-all 路径（`apply_text_revisions scope=ALL`）走 `TrackedChanges.applyRepeated`（TrackedChanges.java:411），同样命中 `applyMove`（429-431 行，注释「move 成对操作:两端算作 1 条」）。

### 各关键点文件:行号

| 内容 | 位置 |
|---|---|
| `apply_tracked_changes` 工具入口（@ToolDef 描述串） | `nondocx-toolkit/src/main/java/io/github/nondirectional/docx/toolkit/TrackedChangeQueryTools.java:214-293` |
| 批量统一实现（成功/失败明细循环 + 汇总） | `TrackedChangeQueryTools.java:503-580`（`applyRevisionsByIds`） |
| 成功行格式 `[i] id 已应用 ref=... ✓` / 汇总 `成功 N 条,失败 M 条` | `TrackedChangeQueryTools.java:533-566` |
| PARTIAL_FAILURE 构造（`ToolResult.partial(...)`） | `TrackedChangeQueryTools.java:568-578` |
| core 单条 accept / move 配对 | `nondocx-core/src/main/java/io/github/nondirectional/docx/core/api/track/TrackedChanges.java:236`（accept）、`:444`（applySingle）、`:504-522`（applyMove） |
| move 配对端查找（author+text） | `TrackedChanges.java:531-554`（findMoveCounterpart） |
| acceptText 分派 | `nondocx-core/.../internal/poi/TrackedChangeNodes.java:165-174` |
| **unwrapRunsAndRemove（§6.13 修法现状）** | `TrackedChangeNodes.java:226-250` |
| reject 的 delText→t 恢复 | `TrackedChangeNodes.java:258-272`（restoreDelTextToT） |
| **moveRuns（move 修订创作，崩溃源头）** | `TrackedChangeNodes.java:819-907` |
| toolkit `move_run_tracked` | `nondocx-toolkit/.../TrackedChangeAuthoringTools.java:702-749` |
| core `Paragraph.moveRunsFrom` | `nondocx-core/.../api/text/Paragraph.java:768-798` |
| core `Paragraph.text()`（直接委托 POI） | `Paragraph.java:85-86` |
| core `Document.paragraphs()`（backing=POI 缓存） | `nondocx-core/.../api/Document.java:119-131` |

### unwrapRunsAndRemove 现状（§6.13 修法，2026-10-30 多 run ins 踩实后）

`TrackedChangeNodes.java:226-250`。形态：**run 引用快照 + 逐 run 独立 cursor**——

1. 先把 `wrapper.getRArray(i)` 全量快照进临时 `List<CTR>`（`getRArray` 是活视图，边迁边读会漂移）；
2. 对每个 run 开独立 `XmlCursor`，`rc.moveXml(anchor)`（anchor = wrapper 位置的 cursor）；
3. 最后 `anchor.removeXml()` 删空包装。

注释（229-233 行）记录了旧 bug：`child.moveXml + toNextSibling` 循环在首个 moveXml 后游标失效，多 run ins 后续 run 会随 removeXml 一起被删；迁移顺序由 `TrackedAuthoringTest/PatchEngineTest` 的 accept 断言锁定。

**注意**：该方法只修了「XML 树内容正确性」，没有处理 POI `XWPFParagraph` 缓存中旧句柄的断连（见 crash-root-cause.md）。

### moveRuns 创作逻辑（TrackedChangeNodes.java:819-907）

产出四件套（源段 `moveFromRangeStart`+`moveFrom`+`moveFromRangeEnd`，目标段 `moveToRangeStart`+`moveTo`+`moveToRangeEnd`，靠 `rangeStart` 的 `w:name=_move_<baseId>` 配对，共 6 个 w:id）：

1. **预捕获源 run 文本**（840-847 行，注释明言「moveXml 后源 CTR 句柄会 XmlValueDisconnected,不能在移动后再读」）；
2. 源段：每个直接子 run 的 `t`→`delText`（原地转换），再 `runCur.moveXml(fromCur)` 迁入 `<w:moveFrom>`（**869-874 行——崩溃源头，moveXml 断连 POI 缓存持有的旧 CTR 句柄**）；
3. 目标段：`moveTo.addNewR()` 新建 run 写预捕获文本（893-902 行，返回 `moved` 列表——这些 CTR 是新建的，不入 POI 段落缓存，live 不可见，§6.13 ins 同族）。

### move 对 accept 语义（TrackedChanges.applyMove, :504-522）

- 命中任一端 → `findMoveCounterpart` 找另一端（author+text，文档顺序第一个匹配）→ **先 from 后 to 两端同时执行**；
- accept：`acceptText(from)` = removeNode（moveFrom 整树删除，del 文本消失）+ `acceptText(to)` = unwrapRunsAndRemove（moveTo 内 run 落为目标段直接子）；
- reject：`rejectText(from)` = restoreDelTextToT + unwrap + `rejectText(to)` = removeNode；
- 配对端缺失 → `NoSuchElementException`（:509-511，「找不到配对端…请使用 raw()」）。

**按 id 批量应用 move 对的 partial_failure 来源**：ids 同时含 `move_to:X` 与 `move_from:Y` 时，第一条（任一端）accept 即联动两端；第二条 collect 重算后已无该修订 → `NoSuchElementException("找不到 id 为 ... 的修订")`（TrackedChanges.java:455）→ toolkit 记 `fail++` → PARTIAL_FAILURE。语义上「应用一半即整对完成」，返回串当前无此说明（要加说明的自然落点：`applyRevisionsBatch` javadoc / `applyTrackedChanges` @ToolDef description（TrackedChangeQueryTools.java:216-220）/ `applyMove` javadoc（TrackedChanges.java:487-503））。

### 返回串构造位置（问题 6）

- 单条成功：`TrackedChangeQueryTools.java:533-541`（`[i] <id> 已<verb> ref=<canonical> ✓`）；
- 单条失败（RuntimeException）：`:550-557`（`[i] <input>: 错误(<rootMessage>)`）；
- 汇总行：`:566`（`\n成功 N 条,失败 M 条`）；
- partial/ok 分叉：`:568-578`（`ToolResult.partial(PARTIAL_FAILURE, ok, sb, ..., matchedCount, ok, skipped)`）。

### 影子包装家族先例（问题 7，代码内既有惯例）

| 先例 | 位置 | 手法 |
|---|---|---|
| `delById`：moveXml 断连后按 w:id 重查活句柄 | `Paragraph.java:691-700` | 切片返回的 del 句柄断连后，在 live CTP 的 `getDelList()` 里按 id 重查 |
| `insertedRunsByRevisionId`：detached 副本写回后按 id 重查 | `Paragraph.java:672-684` | 从 live `getInsList()` 按 w:id 重新构造 Run |
| `Table.copyRowTo` 影子行技法（POI 5.3 addRow 脱链） | core `Table.java`（docs/11 §6.8） | 手动 `insertNewTr` 得树内元素 → `addRow` 同步 POI 缓存 → `removeTr` 删影子 |
| moveRuns 预捕获文本 | `TrackedChangeNodes.java:838-847` | moveXml 前读文本，断连后不再读源 |
| spec「accept/reject 后必须 save+reopen」须知 | `.trellis/spec/backend/poi-bridge.md:510` | 已把该现象记为已知边界（测试须知，非产品修复） |
| spec N17 moveXml 断连条目 | `poi-bridge.md:506` | 「moveXml 后源 CTR 句柄 XmlValueDisconnected——文本必须 moveXml 之前预捕获」 |

**尚无统一处理惯例**：现有手法都是「单点规避」（预捕获/按 id 重查/影子节点），没有对「POI XWPFParagraph/XWPFRun 缓存整体失效/重建」的通用机制——这正是本任务缺口。

## Caveats / Not Found

- `nondocx-demo` 模块未深挖（ReviewSubAgentTest / DirtyDetectionTest 引用了 applyTrackedChanges，但与本 bug 的 core 根因无直接关系）。
- docs/07-toolkit.md:144 仅一行工具表条目，无 move 对语义详述。
