# Research: full 面 mark_style_change_tracked 与 core Run.commitStyleAsTracked（rPrChange）全链调研

- Query: core commitStyleAsTracked 签名/XML/前置条件；full 面 mark_style_change_tracked；修订观测面 PropertyChangeDetails 与写读同批（§6.13）；bold/italic 序列化；w:id/author/date 基建；相关 spec 清单
- Scope: internal
- Date: 2026-10-31

---

## 1. core：`Run.commitStyleAsTracked`

### 位置与签名

`nondocx-core/src/main/java/io/github/nondirectional/docx/core/api/text/Run.java:322-339`

```java
public Run commitStyleAsTracked(String author, RunStyle previousStyle)
```

- `author`：修订作者。`Objects.requireNonNull` + 空白抛 `IllegalArgumentException("author 不能为空白")`（323-326 行）。
- `previousStyle`：**修改前**的样式快照，`Objects.requireNonNull`（327 行）。必须**在改样式前**用 `run.style()` 取得（两步式契约，见 294-313 行 javadoc）。
- 前置条件：run 必须挂在段落上——`delegate.getParagraph() == null` 时抛 `NoSuchElementException("本 run 未挂在段落上,无法记录属性修订")`（328-331 行）。
- 返回 `this`（链式）。与 `<w:trackChanges/>` 开关正交（显式 tracked 路线）。
- 完整用法（javadoc 299-303 行）：

```java
RunStyle before = run.style();   // 1. 先快照改前的样式
run.bold().italic();             // 2. 链式改样式(此时 rPr 是新值)
run.commitStyleAsTracked("甲", before);  // 3. 把「改前/改后」写成 rPrChange
```

### 生成的 XML（委托 `TrackedChangeNodes.commitRPrChange`）

`nondocx-core/src/main/java/io/github/nondirectional/docx/core/internal/poi/TrackedChangeNodes.java:863-905`

- run 无 `rPr` 则 `run.addNewRPr()`（874-875 行）；在 `rPr` 内 `addNewRPrChange()` 建容器（876-877 行）。
- 属性来源：`w:id` = `nextRevisionId(document)`（878 行，自动）；`w:author` = 参数（879 行）；`w:date` = 调用方传入的 `Calendar`——core 层 `Run.commitStyleAsTracked` 固定传 `Calendar.getInstance()`（Run.java:337，**date 不可由调用方覆盖**）。
- 旧值树（pristine）：`change.addNewRPr()` 返回 **`CTRPrOriginal`**（882-883 行），把 `previousStyle` 六属性渲染进去（884-904 行）：

| previousStyle 属性 | 旧值树写法 |
|---|---|
| `isBold()` | `addNewB()`（**无 val** = on） |
| `isItalic()` | `addNewI()`（无 val） |
| `isUnderline()` | `addNewU().setVal(STUnderline.SINGLE)` |
| `font()` | `addNewRFonts().setAscii(font)` |
| `size()`（磅） | `addNewSz().setVal(磅 × 2)`（OOXML sz 是半磅） |
| `color()` | `addNewColor().setVal(color)` |

- XML 形态（docs/05-tracked-changes/04-authoring.md:112-117）：

```xml
<w:rPr>                       <!-- 外层 = 新值（调用方已改完） -->
  <w:b/>
  <w:rPrChange w:id="1" w:author="审阅者甲">
    <w:rPr/>                  <!-- 旧值树（CTRPrOriginal） -->
  </w:rPrChange>
</w:rPr>
```

- **架构层防递归**：`CTRPrOriginal` 的 schema 天然不含 `rPrChange` 子元素，旧值树不可能嵌套 rPrChange（poi-bridge.md N17，spec 477-487 行）。
- accept/reject 语义：accept = 删 `rPrChange` 节点（外层新 rPr 保留）；reject = 旧值树整树覆盖外层 rPr 直接子后删标记（`TrackedChangeNodes` acceptProperty/rejectProperty，1189-1260 行区；spec poi-bridge.md N15 方案 C）。

### run 拆分/合并

**不做**。`commitStyleAsTracked` 只作用于单个 run 的整个 rPr，不拆 run、不合并相邻同样式 run。调用方（如 full 面工具）先按 paragraph_index/run_index 定位 run，样式变更粒度 = 该 run 全部文本。要做「段内部分文字」的格式修订，需要调用方先做 run 拆分（core 侧有 `Paragraph.copyRun`，replace_text 路径在用）再逐 run commit。

### core 测试

`nondocx-core/src/test/java/io/github/nondirectional/docx/core/api/track/TrackedAuthoringAdvancedTest.java:63-125`

- `rprChangeAuthoringRoundTrip`（66-95）：commit → save/reopen → `list()` 得 1 条 `RPR_CHANGE`，`newSummary` 含 b+i、`oldSummary` 不含 b；`rejectProperty` 后 reopen 无修订、bold/italic 均为 false。**reject 后必须 save+reopen 断言**（POI XWPFRun 在整树替换后断连）。
- `rprChangeAcceptKeepsNewStyle`（97-108）：accept 后 bold 保留、修订消失。
- `rprChangeRejectsNullPreviousStyle`（110-117）：null previousStyle 抛 `IllegalArgumentException`。

---

## 2. full 面与 compiler 面：分层与 `mark_style_change_tracked`

### 两个工具面

- **full 面** = toolkit 全量工具面（`DocxToolkit` 聚合，58 工具：BodyTools/TrackedChangeAuthoringTools/TrackedChangeQueryTools/TableTools/HeaderFooterTocTools/QualityCheckTools/SessionTools…）。docs/11 §6 的 A/B 基线之一。
- **compiler 面** = `CompilerTools`（`nondocx-toolkit/.../toolkit/CompilerTools.java`，6 工具：create_document / read_document / validate_document / edit_document / fill_template / render_preview；+current_document+复审 SubAgent=8 入口）。编辑收敛为 `edit_document` 的 **ops 数组**（`compiler/PatchEngine.java`，v4 共 **12 种 op**：insert / replace_text / delete / set_style / table.set_cell / table.add_row / tracked.insert / tracked.delete / tracked.replace / footer.set_page_number / header.set_text / comment.add）。
- 分层关系（docs/07-toolkit.md、docs/11 §6.13-6.15）：两面对同一 core；compiler 面用「文档 IR + 编译器」路线把整段级语义内建为 op，full 面暴露细粒度 run/paragraph 工具。**本任务 prd 的「compiler 面新增 op」= 在 PatchEngine 加第 13 种 op**（先例：header.set_text，见 spec agent-single.md:315-329）。

### `mark_style_change_tracked` 定义

`nondocx-toolkit/src/main/java/io/github/nondirectional/docx/toolkit/TrackedChangeAuthoringTools.java:568-635`

- `@ToolDef(name = "mark_style_change_tracked")`，`@ToolCapability(operation = UPDATE, element = "tracked_change")`。
- 参数：`doc_id`、`paragraph_index`（0 起）、`run_index`（0 起）、`author`（必填）、`bold`（boolean 可选）、`italic`（boolean 可选）、`color`（String 可选）、`expected_generation`（可选，代次校验）。
- 内部（617-628 行）：定位 run → `RunStyle before = r.style()` 快照 → **仅显式提供的参数才应用**（`if (bold) r.bold(); if (italic) r.italic(); if (color 非空) r.color(color);`）→ `r.commitStyleAsTracked(author, before)`。
- 与 commitStyleAsTracked 的关系：Agent 友好一步式包装（快照/应用/提交三合一），底层就是两步式 core API（javadoc 引 poi-bridge.md N17）。
- **语义限制（本任务关键）**：boolean 参数 false = 「不显式设置」——`if (bold)` 只在 true 时写。**full 面此工具当前无法表达「取消加粗/取消斜体」**（取消需要 `r.bold(false)` 写 `w:b w:val="off"` 再 commit，该工具没有这个分支）。
- 1131-1141 行是 `@Deprecated` 的旧 Java 签名兼容重载（非批量版）。
- 测试：`nondocx-toolkit/src/test/java/io/github/nondirectional/docx/toolkit/DocxToolkitBatchTest.java:822-855`（shouldAcceptMultiplePropertyChangesCollectErrors：用它造 rPrChange → `list_tracked_changes` 返回含 `RPR_CHANGE`，stable id 形如 `rpr_change:...`，可被 `apply_tracked_changes target=PROPERTY` accept）。

### full 面既有 run 样式工具（对照）

`BodyTools.update_run_style`（BodyTools.java:881 起）——直接改样式、**非修订**（§6.14 workaround 链的组件）。compiler 面 set_style 只有整段 bold/italic，无 run 级；「给既有文字加粗」当前 = `replace_text find=X text=**X**` 身份替换（非修订）或 tracked.replace（删+插，非 rPrChange）。

---

## 3. 修订观测面：读取与呈现

### core 数据模型（`nondocx-core/.../api/track/`）

| 类型 | 文件 | 要点 |
|---|---|---|
| `PropertyChangeDetails` | PropertyChangeDetails.java:29-100 | `kind` + `newSummary` + `oldSummary`；不可变值对象；`summarize(List<String>)` 把属性树直接子**本地名**排序拼 `[b,i]`（**不看 val**） |
| `PropertyChangeKind` | PropertyChangeKind.java:16-21 | `RUN_PROPERTIES`（rPrChange）/ `PARAGRAPH_PROPERTIES`（pPrChange） |
| `TrackedChangeType` | TrackedChangeType.java:30-50 | INS/DEL/MOVE_FROM/MOVE_TO（TEXT+MOVE 家族）、**RPR_CHANGE**/PPR_CHANGE/SECT_PR_CHANGE（PROPERTY）、CELL_INS/CELL_DEL/CELL_MERGE（CELL） |
| `TrackedChange` | TrackedChange.java | `wId()`（N17：raw() 对属性/单元格类抛异常，必须用 wId） |

### 读侧解析：rPrChange **已经能被读出**

`TrackedChangeNodes.collect` → `walkParagraph`（1104-1130）→ `collectPropertyChanges`（1139-1162）：遍历段落直属每个 `CTR` 的 `rPr.isSetRPrChange()` → 产出 `TrackedChangeType.RPR_CHANGE` + `PropertyChangeDetails(kind=RUN_PROPERTIES, newSummary=summarizeChildren(外层 rPr), oldSummary=summarizeChildren(change.getRPr()))`。**pPrChange 不覆盖**（`CTPPrChange` CT 类型在 POI 5.2.5 精简 schema 缺失，v1 已知边界）。

### 呈现层

1. **full 面 `list_tracked_changes`**（TrackedChangeQueryTools.java:142-177）→ `describeRevision`（447-457）→ `appendDetails`（460-475）：rPrChange 渲染为 `type=RPR_CHANGE, family=PROPERTY, author="甲", property=RUN_PROPERTIES(新 [b]/旧 []), ref=..., id=rpr_change:...`。文本行摘要（非 JSON）。`get_tracked_change`（180-207）单条同格式。`apply_tracked_changes`（215+）`target=PROPERTY` 走 `acceptProperty`/`rejectProperty`。
2. **view 面（full 面视图工具）REVISION 详情**（`view/DocumentViewService.java:519-548`）：`type/author/date` + `details instanceof TextChangeDetails` 才放 `text/bold/italic`（527-547，经 `change.raw().getRList()` 聚合 `sizeOfBArray()>0`）。**PropertyChangeDetails 分支目前不输出 bold/italic 字段**——rPrChange 的「格式证据」只有 newSummary/oldSummary 的本地名。这是本任务观测面扩展点之一。
3. **outline 修订明细**（view/dto/RevisionEntry.java，经 snapshot/RevisionSummary）：`ref/type/author/snippet/bold/italic`，bold/italic 恒出现（§6.13 补的，false 也是证据）。
4. **compiler 面 `read_document`**（compiler/MarkdownView.java，236 行）：**不渲染修订**——ins/del/rPrChange 均无输出（批注/页眉/页脚有 `<!-- ... -->` 元数据行惯例，修订没有）。run 渲染 `renderRuns`（142-166 行）按 `run.isBold()` 输出 `**…**`。修订可见性语义在 docs/07-toolkit.md:285-295 记载（runs() 不暴露 ins 内 run、live/reopen 差异）。

### 「写读同批」与 §6.13 出处

- **§6.13 原文**：`docs/11-agent-authoring-architecture.md:819-860`（「tracked 行内格式：…与两次被踩实的旧坑」）。核心教训（850-856 行）：模型第一个 op 就正确，但修订内 run 不被 `runs()` 暴露，复审在视图里查 bold 落空，把已正确的文档报「未达成」→ 修复 = 修订观测面补 **bold/italic 恒出现**（outline recent[] 每条 + REVISION 详情）。原则：**复审只会依据可见属性下结论，修订内 run 的属性必须挂在修订条目上**。
- **「写读同批」表述**：`docs/11 §6.15`（929-944 行，header.set_text）与 `.trellis/spec/backend/agent-single.md:329`（「写读同批落地（§6.6/§6.13 教训：只有写侧 op 没有读侧观测，复审会误报『未达成』）」）、agent-single.md:336-337（comment.add 同族原则）。
- 对本任务含义：新增 compiler 面 rPrChange op 时，**必须同批补读侧证据**——模型/复审核对「以修订加粗了既有文字」的位置（compiler 面 read_document 或 check 可见的形态）。

---

## 4. bold/italic 序列化（core）

### 写侧（POI 5.2.5 源码实证）

`Run.bold(boolean)`/`italic(boolean)`（Run.java:82-116）委托 POI。poi-ooxml-5.2.5-sources.jar `XWPFRun.java:299-303`：

```java
public void setBold(boolean value) {
    CTRPr pr = getRunProperties(true);
    CTOnOff bold = pr.sizeOfBArray() > 0 ? pr.getBArray(0) : pr.addNewB();
    bold.setVal(value ? STOnOff1.ON : STOnOff1.OFF);
}
```

- `STOnOff1`（officeDocument/sharedTypes，枚举表仅 `"on"`/`"off"`，poi-ooxml-full sources STOnOff1.java:69-77 确认）：**`setBold(true)` → `<w:b w:val="on"/>`；`setBold(false)` → `<w:b w:val="off"/>`**。注意不是 `w:val="0"`——"0"/"1"/"true"/"false" 是同一 ST_OnOff 的合法词法形式（读侧接受），但 POI 写侧经枚举序列化为 on/off。
- **取消加粗 = `w:b w:val="off"`（显式 off，元素保留不删除）**。`setItalic` 同型（448-452 行）。

### 读侧

`XWPFRun.isBold()`（258-261）：`sizeOfBArray()>0 && isCTOnOff(getBArray(0))`；`isCTOnOff`（225-227）= 无 val 视为 on + `POIXMLUnits.parseOnOff` 解析 val（off/0/false → false）。即三种形态：无 `w:b`→false（未设置）、`w:b val="off"`→false（显式关）、`w:b`（无 val）/on/1/true→true。

### RunStyle 快照的有损点

`Run.style()`（Run.java:225-227）= `(isBold, isItalic, isUnderline, font, fontSize, color)`——布尔三态被压平：**「未设置」与「显式 off」在 RunStyle 里都是 false，不可区分**（RunStyle.java javadoc：六属性模型，font/size/color 可 null）。连带两个对 rPrChange 的影响：

1. `commitRPrChange` 旧值树只在 `isBold()==true` 时 `addNewB()`（无 val）——「改前显式 off」与「改前未设置」的旧值树都是「无 b」，reject 后都回到「无 b」。
2. `PropertyChangeDetails.summarize` 只收本地名不看 val——「加粗」（[]→[b]）与「取消加粗」（[b]→[b]，val on→off）的 newSummary 都是 `[b]`，**观测面摘要无法区分方向**（本任务若要表达「取消加粗」的证据需注意）。

### commitStyleAsTracked 属性覆盖

六种：bold/italic/underline/font/size/color（TrackedChangeNodes.java:884-904；docs/05-tracked-changes/04-authoring.md:128「只能追踪这六种」）。**本任务的 bold/italic 已完全覆盖**（含旧值树渲染）。未覆盖：下划线颜色、字符间距等（raw-only）。

---

## 5. w:id / author / date 注入基建（tracked op 共用）

| 项 | 机制 | 位置 |
|---|---|---|
| w:id | `TrackedChangeNodes.nextRevisionId(document)`：collect 全部 TrackedChange 的 `wId()` 取 max+1，无修订返回 0。**必须用 `wId()` 不能 `raw().getId()`**（后者对属性/单元格类抛 UnsupportedFeatureException，N17 修复的 bug）。id 全自动，调用方不可指定 | TrackedChangeNodes.java:1042-1056；spec poi-bridge.md:508-512 |
| author | 无默认值。toolkit 工具 author 参数必填；core 层 requireNonNull+非空白校验 | Run.java:323-326 |
| date | core 层自动 `Calendar.getInstance()`（Run.java:337 传入）。op 参数层不可覆盖 | 同左 |
| 共用者 | addInsertion/addDeletion/replaceTrackedRange/moveRuns（move 需 6 个 id）/markInserted/markDeleted/commitRPrChange 全走 nextRevisionId | TrackedChangeNodes.java 全文件 |

---

## 6. spec / docs 必读清单（实现与检查子代理）

### `.trellis/spec/backend/`（绝对路径）

| 文件 | 相关小节 | 为什么读 |
|---|---|---|
| `/Users/non/Projects/nondocx/.trellis/spec/backend/index.md` | Guidelines Index + Scope Boundaries（Tracked changes 已落地段，86-88 行） | 分层总索引 |
| `/Users/non/Projects/nondocx/.trellis/spec/backend/poi-bridge.md` | **N12-N17**（301-514 行），尤其 N15（rPrChange 读+accept/reject，方案 C）、N17（rPrChange 创作/CTRPrOriginal 防递归/nextRevisionId 用 wId，471-514）、N25（手术后缓存按需重建与 §6.13 live/reopen 语义，688 行附近） | 修订全家族的 POI 桥接规范 |
| `/Users/non/Projects/nondocx/.trellis/spec/backend/agent-single.md` | 300-360（compiler op 惯例：同锚点独占、header.set_text 先例、**写读同批 329/336-337**）、160-210（eval 基线：prompt 不得提示工具、防恒真/防退化通过） | 新 op 的写侧+读侧+eval 惯例 |
| `/Users/non/Projects/nondocx/.trellis/spec/backend/quality-guidelines.md` | Testing Requirements（73-105：round-trip + save+reopen 断言）、Code Style（fluent mutator） | 测试与风格要求 |
| `/Users/non/Projects/nondocx/.trellis/spec/backend/error-handling.md` | 全文（DocxException 体系、中文消息） | 异常规范 |
| `/Users/non/Projects/nondocx/.trellis/spec/backend/directory-structure.md` | 全文 | 放代码的位置 |
| `/Users/non/Projects/nondocx/.trellis/spec/guides/cross-layer-thinking-guide.md` | 全文 | op 跨 compiler/PatchEngine/core/观测面多层 |

### docs（绝对路径）

| 文件 | 相关小节 |
|---|---|
| `/Users/non/Projects/nondocx/docs/05-tracked-changes/04-authoring.md` | §3.3 `commitStyleAsTracked`（95-128 行：XML 形态、CTRPrOriginal、六属性边界）；261-265 能力表 |
| `/Users/non/Projects/nondocx/docs/07-toolkit.md` | edit_document op 集（252-283）、tracked.* 语义与 live/reopen 差异（285-295）、mark_style_change_tracked（157 行表） |
| `/Users/non/Projects/nondocx/docs/11-agent-authoring-architecture.md` | **§6.13（819-860）**、§6.14（862-927，full 面可过而未过案例）、§6.15（929-944，写读同批先例） |
| `/Users/non/Projects/nondocx/docs/03-api-reference.md` | 134（commitStyleAsTracked）、248（acceptProperty/rejectProperty） |

### eval 基建（本任务含 eval 对照）

- 任务定义：`nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/eval/EvalTasks.java`（baseline **当前 25 条**，`EvalTasksTest.hasTwentyFiveUniqueTasks` 计数锁；新增任务 append 尾部并同步改计数）。先例任务 `tracked-replace-format`（861 行起）、`bold-existing-phrase`（809 行起，含「严格子集防退化通过」断言模式 827-836）。
- check 直接用 core API 读 save+reopen 后文档（`doc.trackedChanges().list()` / `TrackedChangeType` / `PropertyChangeDetails`），无 LLM-as-judge。
- 复跑命令与 runId 惯例：见 `.trellis/tasks/archive/2026-10/10-31-header-write-op/research/eval-structure-and-rerun.md`（mvn exec:java `--surface compiler --group baseline`；先提交代码再复跑，报告带 @commit）。

---

## 7. 与本任务直接相关的实现缝隙（事实陈述，非建议）

1. **compiler 面无 rPrChange op**：PatchEngine 12 种 op 无「以修订变更既有文字格式」；既有能力只有 tracked.replace（del+ins，非 rPrChange）与 set_style（整段、非修订）。前驱任务 10-30-tracked-inline-format 的 Out of Scope 明确留了本任务（`.trellis/tasks/archive/2026-10/10-30-tracked-inline-format/prd.md:34-37`：「不做对既有文字的格式变更本身算修订（rPrChange，full 面 mark_style_change_tracked 已有）」）。
2. **full 面 mark_style_change_tracked 表达不了「取消加粗」**（`if (bold)` 只认 true）。
3. **观测面 PropertyChangeDetails 已可读回**（RPR_CHANGE + 新旧摘要），但 (a) view REVISION 详情对 PropertyChangeDetails 不输出 bold/italic 字段；(b) summarize 不分 val 方向；(c) compiler 面 read_document 完全不呈现修订。
4. **run 粒度**：commitStyleAsTracked 单 run 整体生效；「段内部分词」需先拆 run（core `Paragraph.copyRun` 在 replace_text 路径有外科拆分先例，`bold-existing-phrase` eval 有严格子集断言模式）。
5. **同锚点独占规则**：PatchEngine「每段落锚点一批至多一个改文本 op（tracked.*/replace_text）」——rPrChange op 是否改文本/是否参与独占需按 comment.add 先例（不改文本则不参与）判定。
6. **reject 后断连**：rPrChange 的 reject 是整树替换，测试断言须 save+reopen（core 测试先例 66-95 行）。

## Caveats / Not Found

- `EvalTasks.java` 类 javadoc 计数注释有过时先例（写 22 实为 24/25）——计数真源是 `EvalTasksTest`。
- 未读 `update_run_style`（BodyTools.java:881 起）完整实现，只确认存在与非修订语义（§6.14 workaround 链已引用其行为）。
- compiler 面复跑需真实 LLM 端点（qwen3.8-27b，EvalConfig 默认 base-url），本次纯只读调研未跑 eval。
