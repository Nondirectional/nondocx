package com.non.docx.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Shading;
import com.non.docx.core.api.table.Cell;
import com.non.docx.core.api.table.Row;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 表格样式工具组的单元测试(子任务 1/2/3 的写工具 + 读侧补强)。
 *
 * <p>范式与 {@link DocxToolkitBatchTest} 一致:建表 → 调工具 → save → reopen 断言(round-trip)。 不重复测 core
 * 读写正确性(那由 core 单元测试背书);这里验证<b>工具层的批量行为、写入落盘、读侧摘要补强</b>。
 */
class TableStyleToolsTest {

  // ==================== 子任务 1:单元格视觉样式 ====================

  @Test
  void shouldUpdateTableCellShadingBatchCollectErrors(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("shading.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("甲");
      row.addCell().addParagraph().addRun("乙");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 两条合法 + 一条越界 → 成功两条、失败一条不中断。
    Map<String, Object> e0 = cellShadingEdit(0, 0, 0, "F1F5F9");
    Map<String, Object> e1 = cellShadingEdit(0, 0, 1, "FFE4E6");
    Map<String, Object> eBad = cellShadingEdit(0, 0, 99, "000000");
    String result = tk.table.updateTableCellShading(docId, List.of(e0, e1, eBad));
    assertThat(result).contains("底纹 → F1F5F9").contains("底纹 → FFE4E6");
    assertThat(result).contains("越界");
    assertThat(result).contains("成功 2 条,失败 1 条");

    // save → reopen 断言底纹落盘(强制 clear,跨引擎安全)。
    Path out = tmp.resolve("shading-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      Shading s0 = doc2.tables().get(0).rows().get(0).cells().get(0).shading();
      Shading s1 = doc2.tables().get(0).rows().get(0).cells().get(1).shading();
      assertThat(s0).isNotNull();
      assertThat(s0.fill()).isEqualTo("F1F5F9");
      assertThat(s1).isNotNull();
      assertThat(s1.fill()).isEqualTo("FFE4E6");
    }
  }

  @Test
  void shouldUpdateTableCellVerticalAlignAndReadSummary(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("valign.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("顶部");
      row.addCell().addParagraph().addRun("居中");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> e0 = cellVAlignEdit(0, 0, 0, "TOP");
    Map<String, Object> e1 = cellVAlignEdit(0, 0, 1, "center"); // 大小写不敏感
    String result = tk.table.updateTableCellVerticalAlign(docId, List.of(e0, e1));
    assertThat(result).contains("垂直对齐 → TOP").contains("垂直对齐 → CENTER");
    assertThat(result).contains("成功 2 条,失败 0 条");

    // 读侧摘要应包含垂直对齐(read_table_cell 补强)。
    String read = tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 0), cellCoord(0, 0, 1)));
    assertThat(read).contains("垂直对齐: TOP").contains("垂直对齐: CENTER");
    assertThat(read).contains("底纹: 无"); // 未设底纹

    // save → reopen 断言垂直对齐落盘。
    Path out = tmp.resolve("valign-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      Cell c1 = doc2.tables().get(0).rows().get(0).cells().get(1);
      assertThat(c1.verticalAlign()).isEqualTo(com.non.docx.core.api.style.VerticalAlign.CENTER);
    }
  }

  @Test
  void shouldRejectInvalidVerticalAlign(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("valign-bad.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().addRow().addCell().addParagraph().addRun("x");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result =
        tk.table.updateTableCellVerticalAlign(docId, List.of(cellVAlignEdit(0, 0, 0, "MIDDLE")));
    assertThat(result).contains("仅支持 TOP/CENTER/BOTTOM").contains("成功 0 条,失败 1 条");
  }

  @Test
  void shouldUpdateTableCellRunStyleBatch(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-run-style.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("表头");
      row.addCell().addParagraph().addRun("数据");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> header = cellRunStyleEdit(0, 0, 0, 0, 0);
    header.put("bold", true);
    header.put("color", "FF0000");
    header.put("font_size", 14);
    Map<String, Object> data = cellRunStyleEdit(0, 0, 1, 0, 0);
    data.put("italic", true);
    String result = tk.table.updateTableCellRunStyle(docId, List.of(header, data));
    assertThat(result).contains("bold=true").contains("color=FF0000").contains("font_size=14");
    assertThat(result).contains("italic=true");
    assertThat(result).contains("成功 2 条,失败 0 条");

    // 读侧:read_table_cell_run 应返回样式摘要(与 BodyTools.read_run 对称)。
    String read = tk.table.readTableCellRun(docId, 0, 0, 0, 0, 0);
    assertThat(read).contains("表头").contains("bold=true").contains("color=FF0000");
  }

  @Test
  void shouldRejectCellRunStyleWithoutFields(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-run-style-empty.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().addRow().addCell().addParagraph().addRun("x");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result =
        tk.table.updateTableCellRunStyle(docId, List.of(cellRunStyleEdit(0, 0, 0, 0, 0)));
    assertThat(result).contains("未提供任何样式字段").contains("成功 0 条,失败 1 条");
  }

  @Test
  void shouldUpdateTableCellParagraphAlignment(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-align.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("左");
      row.addCell().addParagraph().addRun("中");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> e0 = cellAlignEdit(0, 0, 0, 0, "RIGHT");
    Map<String, Object> e1 = cellAlignEdit(0, 0, 1, 0, "center");
    String result = tk.table.updateTableCellParagraphAlignment(docId, List.of(e0, e1));
    assertThat(result).contains("对齐 → RIGHT").contains("对齐 → CENTER");
    assertThat(result).contains("成功 2 条,失败 0 条");

    // save → reopen 断言对齐落盘。
    Path out = tmp.resolve("cell-align-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      var p0 = doc2.tables().get(0).rows().get(0).cells().get(0).paragraphs().get(0);
      var p1 = doc2.tables().get(0).rows().get(0).cells().get(1).paragraphs().get(0);
      assertThat(p0.alignment()).isEqualTo(com.non.docx.core.api.style.Alignment.RIGHT);
      assertThat(p1.alignment()).isEqualTo(com.non.docx.core.api.style.Alignment.CENTER);
    }
  }

  @Test
  void shouldReturnErrorForUnknownDocIdOnStyleTools() {
    DocxToolkit tk = new DocxToolkit();
    assertThat(
            tk.table.updateTableCellShading("doc-999", List.of(cellShadingEdit(0, 0, 0, "FF0000"))))
        .contains("不存在");
    assertThat(
            tk.table.updateTableCellVerticalAlign(
                "doc-999", List.of(cellVAlignEdit(0, 0, 0, "TOP"))))
        .contains("不存在");
    assertThat(
            tk.table.updateTableCellRunStyle("doc-999", List.of(cellRunStyleEdit(0, 0, 0, 0, 0))))
        .contains("不存在");
    assertThat(
            tk.table.updateTableCellParagraphAlignment(
                "doc-999", List.of(cellAlignEdit(0, 0, 0, 0, "CENTER"))))
        .contains("不存在");
  }

  // ==================== 子任务 2:表格行属性与列宽 ====================

  @Test
  void shouldUpdateHeaderRowAndCantSplitBatch(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("row-props.docx");
    try (Document doc = Docx.create()) {
      var table = doc.addTable();
      table.row(r -> r.cell("表头A").cell("表头B"));
      table.row(r -> r.cell("数据A").cell("数据B"));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 标记第 0 行为表头行,第 1 行禁止跨页拆分。
    Map<String, Object> hr = rowEdit(0, 0);
    hr.put("header_row", true);
    Map<String, Object> cs = rowEdit(0, 1);
    cs.put("cant_split", true);
    String hrResult = tk.table.updateTableHeaderRow(docId, List.of(hr));
    String csResult = tk.table.updateTableRowCantSplit(docId, List.of(cs));
    assertThat(hrResult).contains("表头行 ✓").contains("成功 1 条,失败 0 条");
    assertThat(csResult).contains("禁止跨页拆分 ✓").contains("成功 1 条,失败 0 条");

    // 读侧 read_table_row 应反映设置。
    String read = tk.table.readTableRow(docId, List.of(rowEdit(0, 0), rowEdit(0, 1)));
    assertThat(read).contains("表头行: true").contains("禁止跨页拆分: true");

    // save → reopen 断言行属性落盘。
    Path out = tmp.resolve("row-props-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      Row r0 = doc2.tables().get(0).rows().get(0);
      Row r1 = doc2.tables().get(0).rows().get(1);
      assertThat(r0.headerRow()).isTrue();
      assertThat(r1.cantSplit()).isTrue();
    }
  }

  @Test
  void shouldCancelHeaderRowAndCantSplit(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("row-props-cancel.docx");
    try (Document doc = Docx.create()) {
      var table = doc.addTable();
      table.row(r -> r.cell("表头").headerRow(true));
      table.row(r -> r.cell("数据").cantSplit(true));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> hr = rowEdit(0, 0);
    hr.put("header_row", false);
    Map<String, Object> cs = rowEdit(0, 1);
    cs.put("cant_split", false);
    assertThat(tk.table.updateTableHeaderRow(docId, List.of(hr))).contains("取消表头行 ✓");
    assertThat(tk.table.updateTableRowCantSplit(docId, List.of(cs))).contains("允许跨页拆分 ✓");

    String read = tk.table.readTableRow(docId, List.of(rowEdit(0, 0), rowEdit(0, 1)));
    assertThat(read).contains("表头行: false").contains("禁止跨页拆分: false");
  }

  @Test
  void shouldCollectErrorsOnRowPropsOutOfBounds(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("row-props-bad.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell("仅一行"));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> ok = rowEdit(0, 0);
    ok.put("header_row", true);
    Map<String, Object> bad = rowEdit(0, 9);
    bad.put("header_row", true);
    String result = tk.table.updateTableHeaderRow(docId, List.of(ok, bad));
    assertThat(result).contains("成功 1 条,失败 1 条").contains("越界");

    // 读侧越界不中断。
    String read = tk.table.readTableRow(docId, List.of(rowEdit(0, 0), rowEdit(0, 9)));
    assertThat(read).contains("表头行: true").contains("越界");
  }

  @Test
  void shouldSetColumnPercentsAndReadBack(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("colpct.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell("A").cell("B"));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result = tk.table.setTableColumnPercents(docId, 0, List.of(30, 70));
    assertThat(result).contains("列宽百分比 → [30, 70]");

    // 读侧应返回两列(PCT 按 A4 9026 twips 近似换算)。
    String read = tk.table.readTableColumnWidths(docId, 0);
    assertThat(read).contains("共 2 列").contains("列 0:").contains("列 1:");

    // save → reopen 断言列数与近似宽度。
    Path out = tmp.resolve("colpct-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      var widths = doc2.tables().get(0).columnWidths();
      assertThat(widths).hasSize(2);
      // 30% of 9026 ≈ 2708, 70% ≈ 6318
      assertThat(widths.get(0)).isCloseTo(2708, within(50));
      assertThat(widths.get(1)).isCloseTo(6318, within(50));
    }
  }

  @Test
  void shouldSetColumnWidthsAndReadBack(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("coldxa.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell("A").cell("B"));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result = tk.table.setTableColumnWidths(docId, 0, List.of(4513, 4513));
    assertThat(result).contains("列宽(twips) → [4513, 4513]");

    // save → reopen:DXA 原样返回。
    Path out = tmp.resolve("coldxa-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      var widths = doc2.tables().get(0).columnWidths();
      assertThat(widths).containsExactly(4513, 4513);
    }
  }

  @Test
  void shouldRejectColumnWidthsOnUnknownTable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("colbad.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("无表格");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    assertThat(tk.table.setTableColumnPercents(docId, 0, List.of(50, 50))).contains("越界");
    assertThat(tk.table.setTableColumnWidths(docId, 0, List.of(100, 100))).contains("越界");
    assertThat(tk.table.readTableColumnWidths(docId, 0)).contains("越界");
    // 未知 docId
    assertThat(tk.table.setTableColumnPercents("doc-999", 0, List.of(50, 50))).contains("不存在");
    assertThat(tk.table.updateTableHeaderRow("doc-999", List.of())).contains("不存在");
    assertThat(tk.table.readTableRow("doc-999", List.of(rowEdit(0, 0)))).contains("不存在");
  }

  // ==================== 子任务 3:结构编辑与段落长尾样式 ====================

  @Test
  void shouldAddAndRemoveTableRow(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("struct-row.docx");
    try (Document doc = Docx.create()) {
      var table = doc.addTable();
      table.row(r -> r.cell("A1").cell("B1"));
      table.row(r -> r.cell("A2").cell("B2"));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 追加空行,返回新索引 2。
    String add = tk.table.addTableRow(docId, 0);
    assertThat(add).contains("新行索引 2");
    // 空行有 0 个单元格(addRow 已剥离预填);用 read_table_row 间接确认行存在。
    assertThat(tk.table.readTableRow(docId, List.of(rowEdit(0, 2)))).contains("表格 0 行 2");

    // save → reopen 断言行数 = 3(新增一行)。
    Path out = tmp.resolve("struct-row-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      assertThat(doc2.tables().get(0).rows()).hasSize(3);
    }

    // 删第 0 行(索引漂移提醒):原第 1 行(A2/B2)前移成第 0 行。
    String del = tk.table.removeTableRow(docId, 0, 0);
    assertThat(del).contains("已删除").contains("剩余 2 行").contains("索引已前移");
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 0)))).contains("A2");
  }

  @Test
  void shouldAddAndRemoveTableCell(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("struct-cell.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell("A").cell("B"));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String add = tk.table.addTableCell(docId, 0, 0);
    assertThat(add).contains("新单元格索引 2");
    // 追加后该行有 3 个单元格(A、B、空)。
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 0), cellCoord(0, 0, 1))))
        .contains("A")
        .contains("B");

    String del = tk.table.removeTableCell(docId, 0, 0, 0);
    assertThat(del).contains("已删除").contains("剩余 2").contains("索引已前移");
    // 删第 0 个后,原第 1 个("B")变成第 0 个。
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 0)))).contains("B");
  }

  @Test
  void shouldUpdateCellParagraphHeadingAndClear(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-heading.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell(c -> c.addParagraph().addRun("一段文本")));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> set = cellParaEdit(0, 0, 0, 0);
    set.put("heading", "h2"); // 大小写不敏感
    String result = tk.table.updateTableCellParagraphHeading(docId, List.of(set));
    assertThat(result).contains("标题 → H2").contains("成功 1 条,失败 0 条");

    // save → reopen 断言标题级别。
    Path out = tmp.resolve("cell-heading-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      var p = doc2.tables().get(0).rows().get(0).cells().get(0).paragraphs().get(0);
      assertThat(p.heading()).isEqualTo(com.non.docx.core.api.style.HeadingLevel.H2);
    }

    // clear=true 清除标题。
    Map<String, Object> clear = cellParaEdit(0, 0, 0, 0);
    clear.put("clear", true);
    tk.table.updateTableCellParagraphHeading(docId, List.of(clear));
    Path out2 = tmp.resolve("cell-heading-clear.docx");
    tk.session.saveDocx(docId, out2.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out2)) {
      var p = doc2.tables().get(0).rows().get(0).cells().get(0).paragraphs().get(0);
      assertThat(p.heading()).isNull();
    }
  }

  @Test
  void shouldUpdateCellParagraphIndentAndSpacing(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-indent-spacing.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell(c -> c.addParagraph().addRun("文本")));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> ind = cellParaEdit(0, 0, 0, 0);
    ind.put("left_twips", 720);
    ind.put("first_line_twips", 360);
    Map<String, Object> sp = cellParaEdit(0, 0, 0, 0);
    sp.put("line_spacing", 1.5);
    String indResult = tk.table.updateTableCellParagraphIndent(docId, List.of(ind));
    String spResult = tk.table.updateTableCellParagraphSpacing(docId, List.of(sp));
    assertThat(indResult).contains("left=720").contains("firstLine=360");
    assertThat(spResult).contains("行距 → 1.5");

    // save → reopen 断言。
    Path out = tmp.resolve("cell-indent-spacing-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      var p = doc2.tables().get(0).rows().get(0).cells().get(0).paragraphs().get(0);
      assertThat(p.indentationLeft()).isEqualTo(720);
      assertThat(p.indentationFirstLine()).isEqualTo(360);
      assertThat(p.lineSpacing()).isEqualTo(1.5);
    }
  }

  @Test
  void shouldUpdateCellParagraphListAndClear(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-list.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell(c -> c.addParagraph().addRun("条目")));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> set = cellParaEdit(0, 0, 0, 0);
    set.put("list_kind", "BULLET");
    set.put("level", 0);
    String result = tk.table.updateTableCellParagraphList(docId, List.of(set));
    assertThat(result).contains("BULLET(level 0)").contains("成功 1 条,失败 0 条");

    // save → reopen 断言列表成员。
    Path out = tmp.resolve("cell-list-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      var p = doc2.tables().get(0).rows().get(0).cells().get(0).paragraphs().get(0);
      assertThat(p.listKind()).isEqualTo(com.non.docx.core.api.style.ListKind.BULLET);
      assertThat(p.listLevel()).isEqualTo(0);
    }

    // clear=true 清除列表(走 unsetNumPr,不踩 N4 的空 numId 坑)。
    Map<String, Object> clear = cellParaEdit(0, 0, 0, 0);
    clear.put("clear", true);
    tk.table.updateTableCellParagraphList(docId, List.of(clear));
    Path out2 = tmp.resolve("cell-list-clear.docx");
    tk.session.saveDocx(docId, out2.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out2)) {
      var p = doc2.tables().get(0).rows().get(0).cells().get(0).paragraphs().get(0);
      assertThat(p.listKind()).isNull();
    }
  }

  @Test
  void shouldUpdateCellParagraphShading(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-pshading.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell(c -> c.addParagraph().addRun("文本")));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> set = cellParaEdit(0, 0, 0, 0);
    set.put("fill", "FEF3C7");
    String result = tk.table.updateTableCellParagraphShading(docId, List.of(set));
    assertThat(result).contains("段落底纹 → FEF3C7");

    // save → reopen 断言段落底纹(强制 clear,跨引擎安全)。
    Path out = tmp.resolve("cell-pshading-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      var p = doc2.tables().get(0).rows().get(0).cells().get(0).paragraphs().get(0);
      assertThat(p.shading()).isNotNull();
      assertThat(p.shading().fill()).isEqualTo("FEF3C7");
    }
  }

  @Test
  void shouldCollectErrorsOnCellParagraphEdits(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-para-bad.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell(c -> c.addParagraph().addRun("仅一段")));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 一条合法 heading + 一条非法 heading 值 + 一条段落越界。
    Map<String, Object> ok = cellParaEdit(0, 0, 0, 0);
    ok.put("heading", "H3");
    Map<String, Object> badVal = cellParaEdit(0, 0, 0, 0);
    badVal.put("heading", "H9");
    Map<String, Object> oob = cellParaEdit(0, 0, 0, 99);
    oob.put("heading", "H1");
    String result = tk.table.updateTableCellParagraphHeading(docId, List.of(ok, badVal, oob));
    assertThat(result).contains("标题 → H3");
    assertThat(result).contains("仅支持 H1/H2/H3/H4/H5/H6");
    assertThat(result).contains("段落索引");
    assertThat(result).contains("成功 1 条,失败 2 条");
  }

  @Test
  void shouldReturnErrorForUnknownDocIdOnStructTools() {
    DocxToolkit tk = new DocxToolkit();
    assertThat(tk.table.addTableRow("doc-999", 0)).contains("不存在");
    assertThat(tk.table.removeTableRow("doc-999", 0, 0)).contains("不存在");
    assertThat(tk.table.addTableCell("doc-999", 0, 0)).contains("不存在");
    assertThat(tk.table.removeTableCell("doc-999", 0, 0, 0)).contains("不存在");
    assertThat(tk.table.updateTableCellParagraphHeading("doc-999", List.of())).contains("不存在");
    assertThat(tk.table.updateTableCellParagraphShading("doc-999", List.of())).contains("不存在");
  }

  // ---------- 构造 edit 对象的辅助 ----------

  private static Map<String, Object> cellCoord(int t, int r, int c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("table_index", t);
    m.put("row_index", r);
    m.put("cell_index", c);
    return m;
  }

  /** 构造行坐标对象(含 table_index、row_index),供行属性工具用。 */
  private static Map<String, Object> rowEdit(int t, int r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("table_index", t);
    m.put("row_index", r);
    return m;
  }

  private static Map<String, Object> cellShadingEdit(int t, int r, int c, String fill) {
    Map<String, Object> m = cellCoord(t, r, c);
    m.put("fill", fill);
    return m;
  }

  private static Map<String, Object> cellVAlignEdit(int t, int r, int c, String verticalAlign) {
    Map<String, Object> m = cellCoord(t, r, c);
    m.put("vertical_align", verticalAlign);
    return m;
  }

  private static Map<String, Object> cellRunStyleEdit(
      int t, int r, int c, int paragraphIndex, int runIndex) {
    Map<String, Object> m = cellCoord(t, r, c);
    m.put("paragraph_index", paragraphIndex);
    m.put("run_index", runIndex);
    return m;
  }

  private static Map<String, Object> cellAlignEdit(
      int t, int r, int c, int paragraphIndex, String alignment) {
    Map<String, Object> m = cellCoord(t, r, c);
    m.put("paragraph_index", paragraphIndex);
    m.put("alignment", alignment);
    return m;
  }

  /** 构造段落级样式 edit 的基础 4 字段坐标(table/row/cell/paragraph_index)。 */
  private static Map<String, Object> cellParaEdit(int t, int r, int c, int paragraphIndex) {
    Map<String, Object> m = cellCoord(t, r, c);
    m.put("paragraph_index", paragraphIndex);
    return m;
  }
}
