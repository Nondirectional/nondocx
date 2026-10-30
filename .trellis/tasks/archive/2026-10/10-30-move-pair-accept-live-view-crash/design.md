# Design：POI 段落包装缓存「按需重建」修复 move/accept 后 live 崩溃

## 0. 自主决策记录（无人值守拍板）

| # | 分叉 | 决策 | 理由 |
|---|---|---|---|
| D1 | 修法层次 | **core 修根因**，view/toolkit 读侧零兜底 | 种子推荐；spec 惯例「不掩盖家族问题」；读路径面广（text/runs/view/snapshot），兜底救不完 |
| D2 | 技术路线 | **按需缓存重建**（rebuild-if-disconnected），同包桥接类访问 POI protected 字段，无反射 | 见 §2 三路线对比。无条件重建会打翻 §6.13 锁定的 live 语义（TrackedAuthoringTest:137）；读路径绕开会隐式改 text() 语义 |
| D3 | 触发策略 | 手术点调用「检测到断连句柄才重建」，不无条件重建 | 保 R5：纯新建路径（addInsertion/replaceTrackedRange）无断连不触发，live「ins 不可见」语义原样 |
| D4 | 语义说明落点 | toolkit 失败行文案追加 + @ToolDef description + core applyMove javadoc；**不改** ok/fail 计数与 PARTIAL_FAILURE | 种子推荐「返回串加说明不改行为」 |
| D5 | 测试布局 | core 新增 `TrackedMovePairLiveReadTest` + toolkit 新增 `TrackedMovePairApplyTest` + TrackedChangesTest 风格的「open 已有修订→accept→live 读」用例 | research/test-conventions.md：无现成 live 读回归测试，本任务是第一个正面锁定 |

## 1. 根因与修复目标（调研实证，research/crash-root-cause.md）

悬空产生于 `TrackedChangeNodes.moveRuns` 的 `runCur.moveXml(fromCur)`（:869-874）：
源段直接子 CTR 迁入 `<w:moveFrom>` 瞬间，POI `XWPFParagraph.runs/iruns`（open 时固化，
`XWPFParagraph.java:49-50`，protected）中对应 `XWPFRun` 的旧 CTR 句柄断连；此后任何经
POI 缓存的读（`getText()` 对每个缓存 run 先 `getDelTextArray()`）必抛
`XmlValueDisconnectedException`。同族手术点：accept/reject 的 `removeNode`（:211-218）与
`unwrapRunsAndRemove`（:226-250）在「包装 open 时已存在（内 run 已入缓存）」时同样断连。

修复目标：手术后 POI 缓存与 XML 树重新一致——**不引入任何新可见性、不改变落盘 XML**。

## 2. 技术路线对比（为何选按需重建）

| 路线 | 做法 | 否决/采纳理由 |
|---|---|---|
| (a1) 替换缓存里的 XWPFParagraph 对象 | `new XWPFParagraph(ctp, part)` 换掉 `XWPFDocument.paragraphs/bodyElements` 里旧对象 | 要触达 XWPFDocument 两个 protected 容器 + 表格 cell/header/footer 各容器路径，替换面大；旧 XWPFParagraph 可能被外部持有（core 每次新建包装但 XWPFRun 的 parent 引用、toolkit session）引用不一致 |
| (b) 读路径绕开 POI 包装 | `text()` 等改直读 CTP 聚合 | text() 直读会把 ins 内 t 也聚合 → live 突然可见 ins 文本，**直接违反 R5**；且读面广（runs()/view/snapshot），逐点重写且语义须与 POI 完全对齐，错一面 |
| **(a2) 按需重建段落内部 runs/iruns** | 段落对象不动（CTP/delegate 仍在树上），仅当缓存中存在断连句柄时清空并照 POI open 语义重建 | **采纳**。段落对象身份不变、容器零触达、无反射（同包桥接访问 protected）；检测门槛保证纯新建路径不触发、不引入新可见性 |

## 3. 组件设计

### 3.1 POI 同包桥接（internal/poi 的脏活位置）

新文件：`nondocx-core/src/main/java/org/apache/poi/xwpf/usermodel/XWPFParagraphCacheBridge.java`
（包声明 `org.apache.poi.xwpf.usermodel`，与 `XWPFParagraph` 同包——protected 字段
`runs`/`iruns` 同包可见，无需反射；POI 升级若改字段名编译期即暴露）。

```java
/** 段落包装缓存桥接：XML 手术（moveXml/removeXml）后修复 POI XWPFParagraph 缓存的断连句柄。 */
public final class XWPFParagraphCacheBridge {
  /** 缓存中是否存在已断连的 run 句柄（轻探：XmlValueDisconnectedException 即断连）。 */
  public static boolean hasDisconnectedRuns(XWPFParagraph p);
  /** 若有断连句柄则重建：清空 runs/iruns，照 POI open 语义（buildRunsInOrderFromXml）重建。无断连则不动。 */
  public static void refreshIfDisconnected(XWPFParagraph p);
}
```

重建遍历语义**照抄 POI 5.2.5 `buildRunsInOrderFromXml`**（勿自创；已解包核对）：
直接子 `CTR`→`XWPFRun`；`CTHyperlink` 内 run→`XWPFHyperlinkRun`；`CTSimpleField`→
`XWPFFieldRun`；`CTSdtBlock/CTSdtRun`→`XWPFSDT`（仅 iruns）；`CTRunTrackChange`
（ins/del/moveFrom/moveTo 包装）内 run→`XWPFRun` 且嵌套 `getInsArray()` 递归；
`CTSmartTagRun` 递归。runs 与 iruns 均同步重建；`footnoteText` 不动（脚注引用与修订手术
同段的组合属极端边界，维持构造期快照）。

断连检测：对每个已缓存 `XWPFRun` 调一次 `getCTR().getDelTextArray()`（与 getText 崩溃点
同一路径），捕获 `XmlValueDisconnectedException`（xmlbeans 类型，仅出现在 internal/poi
桥接层，不泄漏到公开 API——满足「零 POI 泄漏」约束，xmlbeans 已是 POI 传递依赖）。

### 3.2 手术点接线（TrackedChangeNodes）

`refreshIfDisconnected` 的调用点（全部在 internal/poi，手术完成、XML 树已定形后调用）：

| 手术点 | 位置 | 受影响段 | 触发情况（按需检测后） |
|---|---|---|---|
| move 创作 | `moveRuns`（:819-907）尾部 | 源段（run 被 moveXml 走）+ 目标段（防御性同检） | 源段必断连→重建；目标段（moveTo 内 run 是新建，不在缓存）通常不动 |
| accept ins/moveTo | `unwrapRunsAndRemove`（:226-250）尾部 | 所在段 | 包装 open 时已存在（run 在缓存，unwrap 的 moveXml/removeXml 断连）→重建；本会话新建（run 不在缓存）→无断连不重建 |
| accept del/moveFrom | `removeNode`（:211-218）尾部 | 所在段 | 同上 |
| reject 各分支 | `restoreDelTextToT`（:258-272）+ unwrap/removeNode | 所在段 | 同上 |
| del 创作（check 阶段补） | `addDeletion` 尾部 | 所在段 | `moveXml` 迁的是**段落直接子 run**（必在缓存）→重建；`Run.replaceTracked` 复合路径同享修复。`addDeletionSlice` 不在此列——其两个调用方（deleteTrackedRange/replaceTrackedRange）走 detached 副本 + `replacePlainTextRunViews` 整体重建，已自愈 |

> **check 阶段连带调整（toolkit）**：`replace_run_tracked` 原依赖「replaceTracked 后 POI 仍把 run 计入
> runs()、索引稳定」的**陈旧缓存**口径（其 javadoc 明言）；del 创作接入重建后 runs() 反映真实 XML
> （del/ins 包装内 run 计入、`addNewDel` 追加段末），同段多条替换按输入顺序惰性解析会坐标漂移
> （`DocxToolkitBatchTest.shouldReplaceMultipleRunsSameParagraphNoDrift` 抓出）。已把该工具改为
> **快照解析（目标 wrapper + 旧文本先于执行锚定）→ 逐条执行**，与 `delete_run_tracked` 的快照计划
> 结构对齐；文案、计数、on_error 语义零变化。`delete_run_tracked` 本就快照 + 右到左执行，无需调整。

受影响段的 `XWPFParagraph` 获取：**优先从手术点已有引用直连**（如 moveRuns 的调用链来自
`Paragraph.moveRunsFrom`，可把 `from.raw()`/`to.raw()` 下传），**回查兜底**：从手术
XmlCursor 向上取所在 `CTP`，遍历 `XWPFDocument` body 直属段落 + 表格 cell 段落
（`getParagraphs()` 比对 `getCTP()`）定位。两条路径都只触 core 现有对象，不新增公开 API。

> 不做「手术点外」的统一刷新入口（如 Document.refresh()）——按需定点修复，面最小。

### 3.3 语义保证（R5 核心论证）

按需检测的门槛保证「**重建只把缓存修正回与当前 XML 一致，绝不引入新可见性**」：

1. **纯新建创作路径不触发**：`addInsertion`/`replaceTrackedRange`/`deleteTrackedRange` 不产生断连
   → 不重建 → live `text()` 仍不含 ins 新文本 → `TrackedAuthoringTest:137` 断言原样通过。
   （check 阶段实证修正：原稿把 `addDeletion` 列在此处是**错误前提**——它对直接子 run 做
   `moveXml`（TrackedChangeNodes `addDeletion` 的 `runCur.moveXml(delCur)`），探针实证迁入
   即断连缓存句柄、live 读必抛；已在 `addDeletion` 尾部补接 `refreshIfDisconnected`，
   见 §3.2 表末行。）
2. **move 创作后源段**：重建后 iruns 含 moveFrom 包装内 run（其 CTR 是 delText）→
   `getText()` 跳过 → 源段 `text()==""`，与 reopen 口径一致（probe 实证 reopen 段0=""）。
3. **accept 后（open 时已存在的包装）**：重建删除断连句柄 → live 恢复可读——即
   poi-bridge.md:510「accept 后必须 save+reopen」边界的修复；读值 = reopen 口径。
4. **accept 后（本会话新建包装）**：unwrap 出的 run 不在缓存、无断连 → 不重建 →
   「unwrap 出的 run 在 live text() 不可见」现状保持（probe 行为；无现有断言锁定，也不新增
   锁定——§6.13 已知形态）。
5. **已知边缘效应（显式接受）**：同段既有断连句柄又有本会话新建 ins 时，重建会把新建 ins
   的 run 带入缓存（live 可见性向 reopen 对齐）。方向正确（更接近落盘真相）、无现有断言依赖
   旧态，记入桥接类 javadoc，不做特判排除（特判需区分「新建」身份，XML 上无此信息）。

### 3.4 toolkit 返回语义说明（R4，不改行为）

- `TrackedChangeQueryTools.applyRevisionsByIds`（:503-580）：循环中维护本轮已成功应用的
  move 端 id 集合；失败行文案追加——当 input 以 `move_from:`/`move_to:` 开头且对端已在本轮
  成功集合时，追加「move 成对修订,应用一半即整对完成,该端已随配对端应用,属预期」。
  **ok/fail 计数、PARTIAL_FAILURE 触发、异常类型零变化**（前缀判断仅影响文案）。
- `apply_tracked_changes` 的 @ToolDef description（:216-220）补一句成对语义说明。
- core `TrackedChanges.applyMove` javadoc（:487-503）同步说明。

## 4. 测试设计（R3/R5，research/test-conventions.md 惯例）

1. **core `TrackedMovePairLiveReadTest`**（api/track，两段文档按 probe 时序）：
   - live：`moveRunsFrom` 后读源段/目标段不抛；源段 `text()==""`、目标段原值；
   - live：`accept(move_to id)` 后读全部段不抛；`accept(move_from id)` 抛
     `NoSuchElementException`（行为锁定）；
   - save+reopen：源段 ""、目标段含被移文本、修订数 0。
2. **toolkit `TrackedMovePairApplyTest`**（TODO #009 复现步骤直调，seed→openDocx→
   move_run_tracked→applyTrackedChanges 两 id→断言 PARTIAL_FAILURE+「失败 1 条」+新说明文案
   →view_outline/view_text 正常返回且含预期段落文本）。
3. **AC3 用例**（TrackedChangesTest 风格或并入新测试类）：POI 裸构造落盘含 ins/del/moveFrom
   修订的文档（TrackedAdvancedTypesTest:32-93 手法）→ `Docx.open` → accept → **live** 读
   不抛且值正确（现状崩）。
4. 回归面：全量 `mvn test`（TrackedAuthoringTest/TrackedAdvancedTypesTest/PatchEngineTest/
   TrackedReplaceFormatWorkaroundTest/doc 模块）。

## 5. 兼容与回滚

- 落盘 XML 零变化（重建只动内存缓存，不动树）；open/save 行为零变化。
- 新增类仅 internal/poi 同包桥接 + 调用接线，无公开 API 变化；回滚 = revert 单个提交。
- POI 版本锁定 5.2.5（根 pom）；若升级，桥接类字段访问编译期暴露，重建遍历语义需对照新版
  buildRunsInOrderFromXml 复核（桥接类 javadoc 记明）。
