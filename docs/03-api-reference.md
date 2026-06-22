# 03 · API 速查

按类型分组的常用方法表 + 一段代码。**速查优先，不展开原理** —— 原理见 [02 架构](./02-architecture.md) 与各专题页。

> 约定：
> - 🔄 = 返回 `this`，可链式
> - 👁 = 只读 / 不修改文档
> - ✏️ = 破坏性写（改文档）
> - 修订（tracked changes）相关方法只列名 + 指 [05 教程](./05-tracked-changes/README.md)，不在这里展开

---

## 入口：`Docx`（静态工厂）

获取 `Document` 的唯一入口。无状态。

| 方法 | 说明 |
|---|---|
| `Docx.open(Path/File/InputStream)` 👁 | 打开现有 docx；流会被完全缓冲且不关闭 |
| `Docx.create()` 👁 | 创建空文档 |

```java
try (Document doc = Docx.open(Path.of("a.docx"))) { ... }
Document fresh = Docx.create();
```

异常：`DocxIOException`（读失败）/ `DocxFormatException`（不是有效 docx）。详见 [08 异常](./08-exceptions-and-raw.md)。

---

## `Document`

文档根。`AutoCloseable`。所有集合都是**活视图**（每次访问重读委托）。

| 方法 | 说明 |
|---|---|
| `bodyElements()` 👁 | **结构真相**：按真实 Word 正文顺序返回段落+表格 |
| `bodyElement(int i)` 👁 | body 顺序索引取一个 |
| `paragraphs()` / `paragraph(int i)` 👁 | 段落**筛选**视图（不连续，跳过表格） |
| `addParagraph()` / `addParagraph(text)` ✏️🔄 | 末尾追加空段 / 带文本段 |
| `insertParagraph(int bodyIndex)` ✏️🔄 | 按 **body 顺序**插入（不歧义） |
| `removeParagraph(int paragraphIndex)` ✏️ | 按**筛选段落索引**删 |
| `tables()` / `table(int i)` 👁 | 表格筛选视图 |
| `addTable()` ✏️🔄 | 末尾追加空表（剥离 POI 预填默认行） |
| `sections()` / `section(int i)` 👁 | 分节视图（永远 ≥ 1 个） |
| `header()` / `footer()` 👁 | 第一节默认页眉/页脚，**不存在返回 null，不创建** |
| `ensureHeader()` / `ensureFooter()` ✏️🔄 | 不存在则**创建**（首次创建补 A4+1inch 兜底） |
| `toc()` 👁 | 首个目录，无则 null（详见 [09 FAQ](./09-faq-and-boundaries.md#toc)） |
| `trackedChanges()` 👁 | 修订能力门面（详见 [05 教程](./05-tracked-changes/README.md)） |
| `save(File/Path/OutputStream)` ✏️ | 保存。`OutputStream` 重载**不关流** |
| `close()` ✏️ | 释放 POI 资源（try-with-resources 推荐） |
| `raw()` | 逃生舱 → `XWPFDocument` |

> **`paragraphs()` vs `bodyElements()` 的索引**：`paragraph(i)` 跳过表格；`insertParagraph(i)` 按 body 顺序。混用时注意区分，详见各方法 Javadoc。

---

## `Paragraph`（段落，`<w:p>`）

正文块容器，装内联元素（run / 超链接 / 图片）。

### 内容访问

| 方法 | 说明 |
|---|---|
| `inlineElements()` 👁 | **结构真相**：按阅读顺序返回 run/超链接/图片（图片算 `Image` 不算 `Run`） |
| `runs()` / `run(int i)` 👁 | 纯 run 的筛选视图 |
| `addRun()` / `addRun(text)` ✏️🔄 | 末尾追加空 run / 带文本 run |
| `addHyperlink(text, url)` ✏️🔄 | 追加超链接 |
| `addImage(bytes, type, wPx, hPx)` ✏️🔄 | 追加内联图片（像素 → EMU 内部转换） |
| `removeInlineElement(int i)` ✏️ | 删第 i 个内联元素 |
| `text()` 👁 | 全段纯文本 |

### 段落级样式

| 方法 | 说明 |
|---|---|
| `heading(level)` / `clearHeading()` / `heading()` ✏️🔄 / 👁 | 标题级别 `H1`–`H6` |
| `alignment(a)` / `alignment()` ✏️🔄 / 👁 | `LEFT/CENTER/RIGHT/JUSTIFY` |
| `indent(leftTwips, firstLineTwips)` / `indentationLeft()` / `indentationFirstLine()` ✏️🔄 / 👁 | 缩进（twips，1 inch = 1440 twips） |
| `lineSpacing(multiple)` / `lineSpacing()` ✏️🔄 / 👁 | 行距倍数（1.0/1.5/2.0） |
| `list(kind, level)` / `clearList()` / `listKind()` / `listLevel()` ✏️🔄 / 👁 | 列表成员：`BULLET/NUMBERED`，level 0–8 |

### 修订（tracked changes）创作

| 方法 | 教程位置 |
|---|---|
| `addInsertion(author, text)` ✏️🔄 | [05/04](./05-tracked-changes/04-authoring.md) |
| `addDeletion(author, targetRun)` ✏️🔄 | 同上 |
| `moveRunsFrom(author, sourceParagraph, runs)` ✏️ | 同上 |

> 长度单位：twips（`indent`、Section 的 `margins`）。1 inch = 1440 twips。字号 `fontSize` 用磅。

---

## `Run`（run，`<w:r>`）

段落内连续文本片段 + 内联字符样式。链式核心。

### 文本

| 方法 | 说明 |
|---|---|
| `text(t)` 🔄 / `text()` | 设 / 读文本（设时**清空旧 `<w:t>` 再写**，绕过 POI 追加陷阱，详见 spec N9） |

### 内联样式（六属性）

| 方法 | 说明 |
|---|---|
| `bold(b)` / `bold()` / `isBold()` 🔄 / 👁 | 粗体 |
| `italic(b)` / `italic()` / `isItalic()` 🔄 / 👁 | 斜体 |
| `underline(b)` / `underline()` / `isUnderline()` 🔄 / 👁 | 下划线（SINGLE / NONE） |
| `fontSize(pts)` / `fontSize()` 🔄 / 👁 | 字号（磅；未设返回 null） |
| `font(name)` / `font()` 🔄 / 👁 | 字体名 |
| `color(hex)` / `color()` 🔄 / 👁 | 6 位十六进制 RGB，如 `"FF0000"` |
| `style()` 👁 | 一次取六属性快照 → `RunStyle` |

### 修订（tracked changes）

| 方法 | 教程位置 |
|---|---|
| `replaceTracked(author, newText)` ✏️🔄 | [05/04](./05-tracked-changes/04-authoring.md) |
| `commitStyleAsTracked(author, previousStyle)` ✏️🔄 | 同上（rPrChange 创作） |

---

## `Table` / `Row` / `Cell`（表格）

### `Table`（`<w:tbl>`）

| 方法 | 说明 |
|---|---|
| `rows()` / `row(int i)` 👁 | 行视图 |
| `addRow()` ✏️🔄 | 追加空行 |
| `removeRow(int i)` ✏️ | 删行 |
| `row(Consumer<Row> config)` ✏️🔄 | 声明式追加行（builder 风格） |

### `Row`（`<w:tr>`）

| 方法 | 说明 |
|---|---|
| `cells()` / `cell(int i)` 👁 | 单元格视图 |
| `addCell()` ✏️🔄 | 追加空 cell |
| `removeCell(int i)` ✏️ | 删 cell |
| `cell(text)` / `cell(Consumer<Cell> config)` ✏️🔄 | 快速文本 cell / 声明式 |

### `Cell`（`<w:tc>`）

| 方法 | 说明 |
|---|---|
| `paragraphs()` / `paragraph(int i)` 👁 | cell 内段落（一个 cell 至少一段） |
| `addParagraph()` ✏️🔄 | cell 内追加段 |
| `text(t)` / `text()` ✏️🔄 / 👁 | 便捷：替换/读取 cell 的全部纯文本 |
| `markInserted(author)` / `markDeleted(author)` ✏️🔄 | 修订：cell 结构存亡（[05/04](./05-tracked-changes/04-authoring.md)） |

> **创建语义**：`addTable()`/`addRow()`/`addCell()` 都**剥离 POI 预填的默认子元素**，保证「addX = 恰好一个 X」。详见 spec N2。

---

## `Section`（分节，`<w:sectPr>`）

页面属性 + 节内页眉页脚入口。一个文档至少一个节。

| 方法 | 说明 |
|---|---|
| `paperSize(size)` / `paperSize()` ✏️🔄 / 👁 | `A4/LETTER/LEGAL/A5/B5/A3` |
| `orientation(o)` / `orientation()` ✏️🔄 / 👁 | `PORTRAIT/LANDSCAPE` |
| `margins(top, right, bottom, left)` ✏️🔄 | 边距（twips） |
| `marginTop/Right/Bottom/Left()` 👁 | 各边距（twips） |
| `header()` / `footer()` 👁 | 本节默认页眉/页脚，**不存在返回 null，不创建** |
| `ensureHeader()` / `ensureFooter()` ✏️🔄 | 不存在则**创建**（首次创建补 A4+1inch 兜底） |

> 节的边界：节中断存在段落 `<w:pPr><w:sectPr>` 上；body 末尾的 `<w:sectPr>` 是最后一节。

---

## `Header` / `Footer`

页眉/页脚容器，只支持默认（奇数页）一份。

| 方法 | 说明 |
|---|---|
| `paragraphs()` / `paragraph(int i)` 👁 | 内部段落 |
| `addParagraph()` ✏️🔄 | 追加段 |
| `text()` 👁 | 全部纯文本 |
| `raw()` | → `XWPFHeader` / `XWPFFooter` |

> 读/写分离：`Section.header()` 只读、`Section.ensureHeader()` 创建。详见 [02 架构 §raw](./02-architecture.md#3-raw-逃生舱--唯一的-poi-出口)。

---

## `Hyperlink` / `Image`（内联元素）

### `Hyperlink`（`<w:hyperlink>`）

| 方法 | 说明 |
|---|---|
| `text(t)` 🔄 / `text()` | 显示文本 |
| `url(u)` 🔄 / `url()` | 目标 URL（设时重建 OPC 关系，详见 spec N10） |
| `raw()` | → `XWPFHyperlinkRun` |

### `Image`（drawing/`<wp:inline>`）

| 方法 | 说明 |
|---|---|
| `type()` 👁 | `PNG/JPEG/GIF/TIFF` |
| `width()` / `height()` 👁 | 宽/高（**像素**，内部 EMU↔像素） |
| `bytes()` 👁 | 原始图片字节 |
| `raw()` | → `XWPFPicture` |

> 图片挂在 run 内部（`<w:r><w:drawing>`），在 `inlineElements()` 里以 `Image` 形态出现，不是 `Run`。详见 spec N6。

---

## `TableOfContents` / `TocEntry`（目录，**只读**）

| 方法 | 说明 |
|---|---|
| `toc.entries()` 👁 | 条目列表 |
| `toc.dirty()` 👁 | 是否标记需刷新 |
| `entry.title()` / `level()` / `pageNumber()` / `anchor()` 👁 | 标题/层级(1–9)/页码/锚点 |

> **只读、只取首个 TOC**。创建/刷新目录需 Word 分页引擎，属 `raw()`。详见 [09 FAQ](./09-faq-and-boundaries.md#toc)。

---

## `TrackedChanges` / `TrackedChange`（修订）

入口 `doc.trackedChanges()`。完整 API 与语义见 [05 教程](./05-tracked-changes/README.md)，下面只给入口速记。

| 方法 | 类别 |
|---|---|
| `enabled()` / `enable()` / `disable()` | 开关 |
| `list()` / `get(id)` | 只读枚举 |
| `acceptAll/rejectAll`、`acceptByAuthor/rejectByAuthor`、`accept(id)/reject(id)` | 文本/移动类 accept/reject |
| `acceptProperty(id)` / `rejectProperty(id)` | 属性类（rPrChange）accept/reject |
| `acceptCell(id)` / `rejectCell(id)` | 单元格类（cellIns/cellDel）accept/reject |

---

## 速查：长度单位

| 单位 | 用在 | 换算 |
|---|---|---|
| **twips** | `indent`、`margins` | 1 inch = 1440 twips |
| **磅 (points)** | `Run.fontSize` | — |
| **像素 (px)** | `Paragraph.addImage`、`Image.width/height` | 库内部按 9525 EMU/px 换算 |
| **倍数** | `Paragraph.lineSpacing` | 1.0 = 单倍 |

---

## 下一步

- 想知道为什么 `save→reopen` 后 `equals` 仍相等 → [04 · 往返保真与内容相等性](./04-round-trip-and-equality.md)
- 想用声明式构建 → [06 · 构建器轨道](./06-builder-track.md)
- 遇到异常或要下探 POI → [08 · 异常与 raw 领地](./08-exceptions-and-raw.md)
