# Research: crash root cause — move 对 accept 后 live view 的 XmlValueDisconnectedException

- Query: 悬空 CTR 包装在哪产生、存放在哪、如何暴露；POI 影子包装机制；项目自建包装层是否有缓存
- Scope: internal（POI 5.2.5 源码 + 项目代码 + 运行探针实证）
- Date: 2026-10-30

## 结论（一句话）

**悬空引用不是 accept 产生的，而是 move 创作时（`TrackedChangeNodes.moveRuns` 的 `runCur.moveXml(fromCur)`，TrackedChangeNodes.java:869-874）产生的**：源段直接子 `<w:r>` 被迁入 `<w:moveFrom>` 的瞬间，POI `XWPFParagraph`（文档打开时构造并缓存在 `XWPFDocument.paragraphs`，构造时已把这些 CTR 包成 `XWPFRun` 存进 `runs`/`iruns`）持有的旧 CTR 句柄即断连；之后任何读（view/read）走 `Paragraph.text()` → `XWPFParagraph.getText()` → `xRun.getCTR().getDelTextArray()` 必炸。accept 只是让修订消失，顺手又对 moveTo 端做了一次 moveXml/removeXml，但目标段缓存里没有那些 run，不产生新的崩溃段。落盘走 XML 树序列化，不经 POI 包装缓存，故完好。

## 探针实证（2026-10-30，本机 POI 5.2.5 实跑）

场景：`Docx.create()` 建两段（段0「被移走的文字」、段1「目标段」）→ save → `Docx.open`（模拟 live 会话 open_docx）→ `moveRunsFrom("probe", 段0, [段0.run0])`（模拟 move_run_tracked）→ `accept(move_to id)`（模拟 apply_tracked_changes）→ 读段 → save+reopen。

输出（原样）：

```
move前 段0 text: [被移走的文字]
move前 段1 text: [目标段]
moveRunTracked 完成, moved runs=1
move后 读段0 崩: XmlValueDisconnectedException: null      ← move 创作后源段已悬空（accept 之前！）
move后 段1 text: [目标段]                                  ← 目标段不崩（moveTo 内 run 是新建，不在 POI 缓存）
修订数=2
accept 目标 id=move_to:body0.paragraph1:4
accept 完成
accept后 读段0 崩: XmlValueDisconnectedException           ← 同一悬空，非 accept 新产生
accept后 段1 text: [目标段]                                ← unwrap 移出的 run 不入 live iruns（live 读不见，§6.13 同族）
第二端 move_from 再 accept: NoSuchElementException: 找不到 id 为 move_from:body0.paragraph0:1 的修订
reopen 段0 text: []                                        ← 落盘完好：源段文本已移走
reopen 段1 text: [目标段被移走的文字]                        ← reopen 后 iruns 重建自 XML，一切正常
reopen 修订数=0                                            ← 无残留修订
```

与 bug 报告「move_to 应用即整对完成、move_from 报找不到→partial_failure、此后任何 view 崩、落盘完好」逐项吻合。补充事实：**只要 move 创作后读过源段也会崩**——fill-template 是 27 次调用尾部才崩，只因 move 与 apply 之间恰好没有碰源段的读调用。

## 悬空引用的产生 / 存放 / 暴露

### 产生点（3 处 moveXml/removeXml，只有第 1 处造成可见崩溃）

| 操作 | 位置 | 被断连的句柄 | 是否落入 POI 段落缓存 |
|---|---|---|---|
| 源 run 迁入 moveFrom（创作） | `TrackedChangeNodes.java:869-874` `runCur.moveXml(fromCur)` | 源段直接子 CTR（**open 时已被 XWPFParagraph 包成 XWPFRun**） | **是 → 源段每次 getText 必炸** |
| accept moveFrom：整包装删除 | `TrackedChangeNodes.java:211-218` `removeNode` → `c.removeXml()` | moveFrom 包装及其内 CTR | moveFrom 内 CTR 在「文档打开时修订已存在」的会话里会入缓存（见下 POI 源码）；本 bug 时序（创作→accept）下不入 |
| accept moveTo：unwrap 迁出 + 删包装 | `TrackedChangeNodes.java:226-250` `rc.moveXml(anchor)` + `anchor.removeXml()` | moveTo 内 CTR 的旧句柄 | 否（XML 手术新建，open 时不存在）；副作用 = unwrap 出的 run 不入 live iruns，live text() 看不见（§6.13 已知形态） |

### 存放点：POI 的包装缓存（不是项目自建层）

POI 5.2.5 源码（本机解包 `poi-ooxml-5.2.5-sources.jar`）：

1. **`XWPFDocument`（XWPFDocument.java:111-114）**：`protected List<XWPFParagraph> paragraphs` 与 `protected List<IBodyElement> bodyElements`——文档打开时一次性填充（:232-233），此后 `getParagraphs()` 只返回不可变视图（:388-389），**无 rebuild/refresh 公开 API**。core `Document.paragraphs()`（Document.java:119-131）每次虽新建 `Paragraph` 包装（项目层无缓存），但 backing 就是这个 POI 缓存列表——所以悬空全部位于 POI 层。
2. **`XWPFParagraph`（XWPFParagraph.java:54-100）**：构造时 `buildRunsInOrderFromXml(paragraph)` 把 runs/iruns 固化。**关键源码（XWPFParagraph.java buildRunsInOrderFromXml）**：

```java
if (o instanceof CTR) {                       // 直接子 run
    XWPFRun r = new XWPFRun((CTR) o, this);
    runs.add(r); iruns.add(r);
}
...
if (o instanceof CTRunTrackChange) {          // ins/del/moveFrom/moveTo 包装内的 run 也入缓存！
    for (CTR r : ((CTRunTrackChange) o).getRArray()) {
        XWPFRun cr = new XWPFRun(r, this);
        runs.add(cr); iruns.add(cr);
    }
}
```

即：**open 时已存在的修订包装内 CTR 也会被包成 XWPFRun 进缓存**（这解释了 poi-bridge.md N14「POI 不暴露 ins 内 run」的表述只对「XML 手术新建的 ins」成立——新建的不进已固化的缓存，open 时已存在的会进）。
3. **崩溃栈最后一环（XWPFParagraph.getText，XWPFParagraph.java:202-218）**：

```java
for (IRunElement run : iruns) {
    if (run instanceof XWPFRun) {
        XWPFRun xRun = (XWPFRun) run;
        if (xRun.getCTR().getDelTextArray().length == 0) {   // ← 每个缓存 run 都先调 getDelTextArray()
            out.append(xRun);
        }
    }
    ...
}
```

任何断连 CTR 在 `getDelTextArray()` 处抛 `XmlValueDisconnectedException`——与 bug 栈 `Paragraph.text()` → `XWPFParagraph.getText()` → `CTR.getDelTextArray()` 完全一致（POI 5.2.5 getText 对每个 run 先查 delText 以跳过 deleted run）。

### 暴露面

所有经 `XWPFParagraph` 缓存包装的读路径都会炸，不止 `text()`：`runs()`（Paragraph.java:201）、`DocumentViewService` 的 view/snapshot（DocumentViewService.java:124/463 `p.text()`）、toolkit 全部 view/read 工具。源段一旦悬空，live 会话内任何触到该段的读都失败。

## 项目自建包装层缓存盘点（问题 3 后半）

| 层 | 缓存? | 说明 |
|---|---|---|
| core `Document.paragraphs()` | 无（每次新建 `Paragraph`） | 但 backing 是 POI `getParagraphs()` 缓存列表（Document.java:121） |
| core `Paragraph` | 仅 `final XWPFParagraph delegate`（单对象） | 悬空来自 delegate 内部的 runs/iruns |
| core `Run` | 仅 `final XWPFRun` | 同上 |
| `TrackedChanges` | 无（list/get 每次 `TrackedChangeNodes.collect` 重扫 XML） | 类 javadoc 明言「无字段快照」（TrackedChanges.java:48-55） |
| toolkit sessions | `Map<String, Document>` 文档句柄缓存 | 文档对象不重建，悬空随会话存活 |

**结论：无需在项目层失效什么缓存；要处理的是 POI 层 `XWPFParagraph.runs/iruns` 的固化。**

## 修复落点的事实依据（供 design 参考，非建议清单）

- POI 无段落缓存重建 API：`XWPFDocument.paragraphs`/`bodyElements` 均 protected、open 时固化；`XWPFParagraph` 无 setCTP/refresh。可选思路只有两大类（implement/design 决策）：(a) XML 手术后**替换/重建受影响段落的 XWPFParagraph 缓存对象**（需要触达 POI protected 字段——反射或继承 `XWPFDocument` 的桥接子类，internal/poi 包是项目指定的 POI 脏活位置）；(b) **读路径绕开 POI 包装**（`Paragraph.text()` 等改从 CTP/XML 直读，同 `TrackedChangeNodes.extractText` 的既有做法），但 view 面广、逐点改面大。
- 同族先例（详见 code-map.md「影子包装家族先例」表）：`delById`/`insertedRunByRevisionId` 的「按 id 重查活句柄」、`copyRowTo` 的「让 POI 自己的 API 创建并登记缓存」。
- spec 已记录该边界为「测试须知」：`.trellis/spec/backend/poi-bridge.md:510`「accept/reject 重构树后,POI 的内存 XWPFParagraph/XWPFRun 包装器会 XmlValueDisconnected。验证 accept 后的结构,必须 save→reopen」。本任务 = 把这条「须知」升级为 core 层修复。
- docs/11 §6.14（914-917 行）对本 bug 的原始记档（TODO #009），归「POI 影子包装家族，与 §6.6/§6.13 同族」。

## 复现脚本

探针源码见本文件同目录 `MoveCrashProbe.java`（探针运行输出即上文；classpath = `nondocx-core/target/classes` + `mvn dependency:build-classpath`）。可直接转成锁定测试的骨架：**断言点 = move+accept 之后、save 之前，live `doc.paragraph(0).text()` 不抛异常且读值正确**（现状为抛 XmlValueDisconnectedException）。
