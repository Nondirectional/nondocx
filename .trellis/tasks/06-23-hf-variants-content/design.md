# Design — 页眉页脚内表格与图片便捷方法

> 子任务 `06-23-hf-variants-content` 的技术设计。
> 需求与验收见 `prd.md`；执行计划见 `implement.md`。

## 设计目标

为 `Header` / `Footer` 补齐表格便捷方法（`addTable` / `tables`），并确认图片路径可直接复用 `Paragraph.addImage`（无需新代码）。

## 探针结论（已实测验证）

实现前写了 `HeaderContentProbeTest`（已删除），用真实 PNG 与表格 round-trip 验证两个关键路径：

### 路径 A（图片）：成立 ✅

`header.addParagraph().addImage(bytes, type, w, h)` 直接复用现有 `Paragraph.addImage`：
- part 关系正确建立（`XWPFHeader` 实现 `IBody`，`XWPFRun.addPicture` 通过 `IRunBody.getPart()` 解析图片关系链工作正常）
- save → reopen 后图片存活，字节 round-trip 精确
- 图片在 `header.paragraph(0).inlineElements()` 里以 `Image` 暴露（与正文段落同行为）

**结论：图片无需任何新代码**，`Header` / `Footer` 已有的 `addParagraph()` + `Paragraph.addImage` 路径天然可用。

### 路径 B（表格）：`XWPFHeaderFooter.createTable` 签名不同 ⚠️

**关键发现**：`XWPFHeaderFooter`（`XWPFHeader` / `XWPFFooter` 的父类）上的 `createTable` 签名是 **`createTable(int rows, int cols)`**，**不**是 `XWPFDocument.createTable()`（无参）。

- POI 的 `XWPFDocument.createTable()` 无参，预填 1 行
- POI 的 `XWPFHeaderFooter.createTable(rows, cols)` 必须传行列数，预填 rows×cols 个单元格

这影响 `Header.addTable()` 的剥离策略（见下方）。

表格 round-trip 验证通过：`createTable(1,1)` → 写内容 → save → reopen，表格结构与内容存活。

## 公共 API 形态

### `Header.addTable()` → `Table`

```java
/**
 * 向此页眉追加一个新的空表格，并返回其活跃包装。
 *
 * <p><b>OOXML</b>：页眉/页脚与正文一样是块容器（{@code <w:hdr>} / {@code <w:ftr>}
 * 内部结构同 {@code <w:body>}，可含段落与表格）。
 *
 * <p><b>POI</b>：{@code XWPFHeaderFooter.createTable(int rows, int cols)} 的签名与
 * {@code XWPFDocument.createTable()}（无参）<b>不同</b> —— 必须传行列数，且会预填 rows×cols 个单元格。
 *
 * <p><b>nondocx</b>：与 {@code Document.addTable} 的「剥掉 POI 预填」语义一致 ——
 * 本方法用 {@code createTable(1, 1)} 创建后剥掉那一行，得到真空表，符合 nondocx 的
 * 「addX = exactly one X」契约（addParagraph/addRun/addTable 都只加一个，不留幽灵默认值）。
 *
 * @return 新追加的空表格
 */
public Table addTable() {
  XWPFTable created = delegate.createTable(1, 1);
  while (created.getRows().size() > 0) {
    created.removeRow(0);
  }
  return new Table(created);
}
```

### `Header.tables()` → `List<Table>`

```java
/**
 * 返回此页眉的表格的活跃视图，按阅读顺序排列。
 *
 * <p>每次访问时都会从委托重新读取视图，因此变更会实时反映。语义同 {@code Document.tables()}。
 *
 * @return 活跃、不可修改的表格列表
 */
public List<Table> tables() {
  return new AbstractList<Table>() {
    @Override public Table get(int i) { return new Table(delegate.getTables().get(i)); }
    @Override public int size()       { return delegate.getTables().size(); }
  };
}
```

`Footer` 对称：`addTable()` + `tables()`。

### 图片：无新代码

`Header` / `Footer` 已有 `addParagraph()`，返回的 `Paragraph` 已有 `addImage(byte[], ImageType, int, int)`。用户链式调用即可：

```java
header.addParagraph().addImage(logoBytes, ImageType.PNG, 200, 80);
```

**不在 `Header` / `Footer` 上加 `addImage` 便捷方法**：避免与 `Paragraph.addImage` 重复，且页眉里的图片通常需要在段落里定位（对齐、与其他 run 的顺序），经过 `addParagraph` 是正确粒度。

## `equals` / `hashCode` 扩展？

**不扩展**。现有 `Header.equals` / `Footer.equals` 比较 `paragraphs()`。表格是否纳入相等性？

考虑：
- 现有 `Header.equals` 只比段落（`paragraphs()`），不含表格
- `Document.equals` 比 `bodyElements()`（段落 + 表格）
- `Header` 的段落视图（`paragraphs()`）与表格视图（`tables()`）是分离的

**决策：本次不扩展 `Header.equals` 纳入表格**。理由：
- 现有 `Header.equals` 的契约是「段落内容相等」，扩展会改变语义
- 页眉里同时有段落和表格是罕见场景，相等性扩展的收益低
- 若需对含表格的页眉做 round-trip 断言，用户可单独比较 `header.tables()`

> 这是诚实的「表格读得到但 equals 不比较」边界，与 TOC 的 SDT 形态不参与 equals（N11）同型。留给 docs-spec 记录。

## 与 Rule 1 的关系

- `Header` / `Footer` 持有 `final XWPFHeader` / `XWPFFooter` delegate（现有结构），不新增字段
- `addTable` / `tables` 操作委托，标准写穿透 + 活跃视图（与 `Document.addTable` / `tables` 同型）
- 无 POI 类型泄漏到公开签名

## 测试策略

### Round-trip（核心）

```java
@Test
void headerTableRoundTrips(@TempDir Path tmp) throws Exception {
  Path file = tmp.resolve("header-table.docx");
  Document original = Docx.create();
  original.ensureHeader().addTable().addRow().addCell().addParagraph().addRun("cell");
  original.save(file);

  try (Document opened = Docx.open(file)) {
    assertThat(opened.header().tables()).hasSize(1);
    assertThat(opened.header().tables().get(0).getRow(0).getCell(0).getText()).contains("cell");
  }
}
```

### 图片复用（确认路径 A）

```java
@Test
void headerImageRoundTripsViaParagraph(@TempDir Path tmp) throws Exception {
  Path file = tmp.resolve("header-image.docx");
  byte[] png = solidPng(4, 4, 0xFF0000);
  Document original = Docx.create();
  original.ensureHeader().addParagraph().addImage(png, ImageType.PNG, 4, 4);
  original.save(file);

  try (Document opened = Docx.open(file)) {
    Paragraph p = opened.header().paragraph(0);
    assertThat(p.inlineElements()).hasSize(1);
    assertThat(p.inlineElement(0)).isInstanceOf(Image.class);
    assertThat(((Image) p.inlineElement(0)).bytes()).isEqualTo(png);
  }
}
```

### 段落 + 表格共存

```java
@Test
void paragraphAndTableCoexistInHeader(@TempDir Path tmp) throws Exception {
  // header 既有段落又有表格，round-trip 后两者都存活
}
```

### 空表 / 活跃视图

- `header.tables()` 无表格时返回空列表
- `header.addTable()` 返回真空表（无幽灵行）

### Footer 对称

- `footer.addTable()` / `footer.tables()` 同型测试

### 回归

- `HeaderFooterTest` / `RoundTripTest` 全绿
- `Header` / `Footer` 的 `paragraphs()` / `addParagraph()` / `text()` 行为不变

## 风险与边界

| 风险 | 缓解 |
|---|---|
| `createTable(1,1)` 剥离后 POI 留下空 `tblGrid` | 与 `Document.addTable` 同行为，已有 round-trip 验证（`RoundTripTest`）|
| 页眉表格在 WPS 的列宽问题 | 表格行为与正文表格一致，由现有 `table-width-dxa` spec 锚点覆盖 |
| `Header.equals` 不含表格 | 诚实边界，Javadoc 标注，docs-spec 记录 |

## 不在本设计内

- `Header.addImage` 便捷方法（避免与 `Paragraph.addImage` 重复）
- `Header.equals` 纳入表格
- 页眉内的非表格/非图片富内容（文本框、shape、水印）
- `Header.tables()` 的索引式 `table(int)` 便捷方法（YAGNI，用户可用 `tables().get(i)`）

## 待 research 确认点

无。探针已验证两个关键路径。
