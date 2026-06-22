# Design — toolkit 质量自检工具

> 子任务 `06-22-quality-check-tools` 的技术设计。PRD 见 `prd.md`。

---

## 1. 设计目标与边界

在 `nondocx-toolkit` 新增 `QualityCheckTools`（第 7 个工具类），对内存中的 `Document` 跑版式/兼容性自检，返回字符串报告给 Agent。让 LLM 写完文档后能自查「版式有没有问题」，而不必肉眼排查或反复打开 Word/WPS 验证。

**Q1 决议：内存为主**。会话只跟踪 `docId → Document`（不跟踪文件路径），磁盘方案需 save 临时文件，成本高且不复用 nondocx 投资。走内存活对象 API，复用 nondocx 已建模的能力（含子任务 1 的 shading、本任务的表格分页）。

**Q2 决议：9 项内存可跑 + 新建 API 补表格分页**。纳入 10 项检查；排除需磁盘/XML/语义判断的 5 项。

**边界**：

- 只**报告**，不修复（修复留给显式工具方法）。
- 不解包读 XML，不引入新依赖。
- 不做语义判断（如「结论是否含糊」）。

---

## 2. 架构：新代码去哪里

```
nondocx-toolkit/src/main/java/com/non/docx/toolkit/
├── QualityCheckTools.java          [新] 第 7 个工具类，继承 ToolkitToolContext
└── DocxToolkit.java                [改] +qualityCheck 字段，scanAll 注册

nondocx-core/src/main/java/com/non/docx/core/
├── api/table/
│   └── Row.java                    [改] +headerRow()/cantSplit()（为检查 #4 提供 API）
└── internal/poi/
    └── RowNodes.java               [新] tblHeader/cantSplit 的 typed 桥接
```

**分层契约**：

- `QualityCheckTools`：toolkit 公开工具类，`@ToolDef` 方法 `check_quality`，签名 POI-free（`String docId` → `String report`）。
- `Row`：core 公开包装器，`headerRow(boolean)`/`cantSplit(boolean)` 链式 mutator，POI-free 签名。
- `RowNodes`：internal/poi 桥接，typed accessor 脏活（参照 `ShadingNodes`/`CellNodes` 模式）。

---

## 3. QualityCheckTools 公开 API

```java
public final class QualityCheckTools extends ToolkitToolContext {

  QualityCheckTools(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    super(sharedSessions, sharedSeq);
  }

  @ToolDef(
      name = "check_quality",
      description = "对 doc_id 指定的文档跑版式/兼容性自检，返回 ❌/⚠️/✅ 报告。"
          + "可选 checks 数组指定跑哪些（空则全量）：blank-pages, line-spacing, table-pagination, "
          + "image-overflow, font-fallback, cjk-indent, heading-levels, shading-solid, toc, cleanliness.")
  public String checkQuality(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "checks", description = "检查项数组（空则全量）", required = false)
          List<String> checks) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    // 跑检查 → 拼报告
  }
}
```

**报告格式**（对齐 toolkit 现有 StringBuilder + 中文 + 换行）：

```
📋 文档质量自检报告: doc-3

✅ [blank-pages] 无连续空段
⚠️ [line-spacing] 正文存在 3 种行距：1.0（5段）、1.5（2段）、未设（1段）
❌ [table-pagination] 第 2 个表格缺少表头行（建议首行设 headerRow）
❌ [shading-solid] 单元格 (表格1, 行2, 列1) 误用 SOLID 底纹，WPS 会显示黑块（见 renderer-compatibility.md#shading-solid）
✅ [cjk-indent] 中文正文首行缩进正确
...
───
通过 7/10 | ❌ 2 errors | ⚠️ 1 warnings
```

---

## 4. 检查项详细设计（10 项）

每项一个 `private CheckResult checkXxx(Document doc)` 方法，返回 `CheckResult(name, passed, message, severity)`。

### check 1: blank-pages（空白页）

- **算法**：遍历 `doc.paragraphs()`，检测连续 ≥3 个空段、或单个段落含双分页符（`<w:br w:type="page"/>` x2）。
- **API**：`paragraph.text()` 空 + `paragraph.inlineElements()` 检查 page break。nondocx 若无 break API，走 `raw()` 兜底或降级为「只查连续空段」。
- **severity**：⚠️ warning。

### check 2: line-spacing（行距一致性）

- **算法**：遍历正文段落（排除表格内、标题、列表），收集 `lineSpacing()` 值。若 >2 种不同值 → 报告。
- **API**：`paragraph.lineSpacing()`（返回 multiple，未设为 -1.0）。
- **severity**：⚠️ warning。

### check 4: table-pagination（表格分页控制）

- **算法**：遍历每个表格。若表格有 >1 行，检查首行是否有 `headerRow()`，数据行（非首行）是否有 `cantSplit()`。缺则报告。
- **API**：**新建** `Row.headerRow()` / `Row.cantSplit()`（R4）。
- **severity**：⚠️ warning。

### check 5: image-overflow（图片溢出）

- **算法**：遍历所有图片（经 `doc.paragraphs()` 的 `inlineElements()` 找 `Image`），取 `image.width()`，换算到 twips（× 1440/96），对比各 section 可用宽度（`paperSize().widthTwips() - marginLeft() - marginRight()`）。超 → 报告。
- **API**：`image.width()`（像素，96 DPI）+ `section.paperSize()` + `section.marginLeft()/marginRight()`。
- **severity**：❌ error。

### check 6: font-fallback（字体回退）

- **算法**：遍历所有 run，收集 `run.style().font()`。若含已知罕见/中文专用字体（如「楷体」「仿宋」在英文环境缺）→ 报告。维护一个「常见安全字体」白名单（宋体/黑体/Arial/Times 等）。
- **API**：`run.style().font()`。
- **severity**：⚠️ warning。

### check 7: cjk-indent（CJK 首行缩进）

- **算法**：遍历正文段落（排除表格内、列表、居中对齐、标题）。若段落含 CJK 字符（正则 `[\u4e00-\u9fff]`）但 `indentationFirstLine()` ≤ 0 → 报告。
- **API**：`paragraph.text()` + `paragraph.indentationFirstLine()` + `paragraph.heading()`（null 才算正文）+ `paragraph.alignment()`（非 CENTER）。
- **severity**：⚠️ warning。

### check 8: heading-levels（标题层级连续）

- **算法**：遍历段落，收集 `heading()` 序列。若出现跳级（H1→H3 跳过 H2）→ 报告。
- **API**：`paragraph.heading()`。
- **severity**：⚠️ warning。

### check 11: shading-solid（ShadingType 误用）

- **算法**：遍历所有 cell + paragraph。子任务 1 的读侧已把 SOLID 归并 NIL，故**需要 raw 兜底**：读 `cell.raw().getCTTc().getTcPr().getShd().getVal()`，若 == `STShd.SOLID` → 报告。引用锚点 `#shading-solid`。
- **API**：`cell.raw()` + CT 路径（检查器内部可用 POI，对外仍 POI-free）。
- **severity**：❌ error。
- **锚点引用**：报告含 `见 renderer-compatibility.md#shading-solid`。

### check 12: toc（TOC 质量）

- **算法**：若 `doc.toc()` 为 null/空 → 报告「无 TOC 域」。若有 TOC，检查正文是否有 `heading()` != null 的段落（无 → 报告「标题未用 Heading 样式，TOC 会空」）。
- **API**：`doc.toc()` + `paragraph.heading()`。
- **severity**：⚠️ warning。

### check 14: cleanliness（文档清洁度）

- **算法**：遍历所有文本，正则匹配占位符（`TODO|FIXME|XXX|待填写|占位`）、Markdown 残留（`^#{1,6}\s`、`\*\*.*\*\*`、`[.*]\(.*\)`）、草稿措辞（`草稿|draft|测试`）。命中 → 报告。
- **API**：遍历 `paragraph.text()` + `cell.text()`。
- **severity**：⚠️ warning。

---

## 5. Row 表格分页 API（R4 新建）

```java
public final class Row {
  public Row headerRow(boolean on)     // 对应 <w:trPr>/<w:tblHeader>
  public boolean headerRow()           // 读
  public Row cantSplit(boolean on)     // 对应 <w:trPr>/<w:cantSplit>
  public boolean cantSplit()           // 读
}
```

**POI 访问路径**（已实测 typed 可达）：

- `CTRow.getTrPr()` / `addNewTrPr()` → `CTTrPr`（叶子）
- `CTTrPr` 继承 `CTTrPrBase`，后者有 `addNewTblHeader()`/`getTblHeader()`/`isSetTblHeader()`/`unsetTblHeader()` 和 `addNewCantSplit()`/`isSetCantSplit()`/`unsetCantSplit()`。
- 全程 typed accessor，无需 XmlCursor。

**RowNodes 桥接**（参照 `ShadingNodes` 模式）：

- `applyHeaderRow(CTRow, boolean)` / `readHeaderRow(CTRow)`
- `applyCantSplit(CTRow, boolean)` / `readCantSplit(CTRow)`

**Row.equals 扩展**：含 headerRow + cantSplit（content-equal 契约）。

---

## 6. CheckResult 值对象

```java
// toolkit 内部，不必公开为 core 类型
static final class CheckResult {
  final String name;        // "blank-pages"
  final boolean passed;
  final String message;     // 详细描述
  final String severity;    // "error" | "warning"
  // toReportLine() → "❌ [name] message"
}
```

放在 `QualityCheckTools` 内部静态类（toolkit 私有，不入 core）。

---

## 7. 关键权衡

| 决策 | 选择 | 代价 | 为什么值 |
|---|---|---|---|
| 内存 vs 磁盘 | 内存 | 检查的是内存状态非落盘 XML | nondocx save 是纯序列化，两者一致；复用 API 投资；会话不跟踪文件路径，磁盘方案成本高 |
| 检查项 MVP 范围 | 10 项（含新建 API 补 #4） | scope 略大 | #4 表格分页是常见痛点；新建 API 顺带补强 core；其余 5 项（边距/宽高比/编号/封面/内容）需磁盘或语义，留给后续 |
| shading-solid 检查走 raw | raw 兜底 | 检查器内部用 POI | 子任务 1 读侧把 SOLID 归并 NIL，公开 API 查不出 SOLID；检查器内部可用 POI（对外仍 POI-free） |
| CheckResult 不入 core | toolkit 内部类 | 不可跨模块复用 | 这是 toolkit 的报告数据，非 domain 概念；避免 core 污染 |
| 报告格式纯文本 | 纯文本 | Agent 要解析 | 对齐 toolkit 现有 6 个工具的报告风格；Agent 已习惯；结构化（icon + bracket name）便于定位 |

---

## 8. 回滚点

每部分独立 git commit：

1. `Row.headerRow()/cantSplit()` + `RowNodes` + core 测试
2. `QualityCheckTools` + 10 项检查 + toolkit 测试
3. `DocxToolkit` 门面注入 + `docs/07-toolkit.md`

任一部分出问题，revert 对应 commit 不影响其它。

---

## 9. 未决（实现期收敛）

- [ ] **blank-pages 的 page break 检测**：nondocx 是否有 break API？若无，降级为「只查连续空段」（postcheck.py 的 #1 也主要查这个）。实现时 grep `Break|pageBreak` 确认。
- [ ] **font-fallback 白名单**：哪些字体算「安全」？初版用最小集（宋体/黑体/SimSun/SimHei/Microsoft YaHei/Arial/Times New Roman/Calibri/Cambria），其余报告。实现期定。
- [ ] **cleanliness 正则**：占位符/Markdown/草稿措辞的具体正则清单，实现期参照 postcheck.py `check_document_cleanliness` 对齐。
- [ ] **`doc.toc()` API 形态**：是返回 `TableOfContents` 还是 List？实现时读 `Document.toc()` 确认。
