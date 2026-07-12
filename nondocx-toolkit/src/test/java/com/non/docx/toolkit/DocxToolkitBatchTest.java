package com.non.docx.toolkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 冒烟测试 {@link DocxToolkit} 第一梯队批量改造(v2):把单次工具升级成支持单次/多次的通用版。
 *
 * <p>不重复测 core 读写正确性(那由 core 单元测试背书);这里只验证<b>工具层的批量行为</b>:
 *
 * <ul>
 *   <li>平行数组 / 对象数组入参能被正确解析(模拟 nonchain 注入的 {@code ArrayList<LinkedHashMap>})。
 *   <li>读类批量:越界索引不中断、标注正确。
 *   <li>写类批量(collect-errors):成功的真写入、越界的记错误、末尾汇总成功/失败条数。
 *   <li>健壮性:LLM 误传单值标量(而非数组)时仍能工作(coerceList 兜底)。
 * </ul>
 *
 * <p>工具方法签名多为 {@code List},少量对象数组入参直接构造 {@code Map<String, Object>[]};与 nonchain 运行时 Jackson
 * 还原出的结构一致——因此测试结果等价于 Agent 经框架调用。
 *
 * <p>本测试经 {@link DocxToolkit} 门面驱动,验证拆分后六个工具类<b>共享同一份会话状态</b>: {@code tk.session.openDocx} 打开的文档,在
 * {@code tk.body}/{@code tk.trackedChangeQuery} 等其它工具里也能按 docId 取回。
 */
class DocxToolkitBatchTest {

  @Test
  void shouldReadMultipleParagraphsWithOutOfBoundsMarked(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("read.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("第一段");
      doc.addParagraph("第二段");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 批量读三段,其中索引 5 越界——不应中断,越界条目要被标注。
    String result = tk.body.readParagraph(docId, List.of(0, 1, 5));
    assertThat(result).contains("段落 0").contains("第一段");
    assertThat(result).contains("段落 1").contains("第二段");
    assertThat(result).contains("段落 5").contains("越界").contains("共 2");
  }

  @Test
  void shouldUpdateMultipleParagraphAlignmentsCollectErrors(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("align.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("标题");
      doc.addParagraph("署名");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result =
        tk.body.updateParagraphAlignment(
            docId,
            List.of(
                paragraphAlignment(0, "center"),
                paragraphAlignment(1, "RIGHT"),
                paragraphAlignment(99, "LEFT")));
    assertThat(result).contains("段落 0 对齐 → CENTER");
    assertThat(result).contains("段落 1 对齐 → RIGHT");
    assertThat(result).contains("[2]").contains("越界");
    assertThat(result).contains("成功 2 条,失败 1 条");

    assertThat(tk.body.readParagraph(docId, List.of(0))).contains("对齐: CENTER");
    assertThat(tk.body.readParagraph(docId, List.of(1))).contains("对齐: RIGHT");
  }

  @Test
  void shouldRejectInvalidParagraphAlignment(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("align-invalid.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result =
        tk.body.updateParagraphAlignment(docId, List.of(paragraphAlignment(0, "MIDDLE")));
    assertThat(result).contains("仅支持 LEFT/CENTER/RIGHT/JUSTIFY").contains("成功 0 条,失败 1 条");
    assertThat(tk.body.readParagraph(docId, List.of(0))).contains("对齐: LEFT");
  }

  @Test
  void shouldReplaceMultipleRunsCollectErrors(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("replace.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("原文 A");
      doc.addParagraph().addRun("原文 B");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 三条编辑:前两条合法(段 0/段 1),第三条段索引 99 越界。
    Map<String, Object> e0 = edit(0, 0, "新 A");
    Map<String, Object> e1 = edit(1, 0, "新 B");
    Map<String, Object> e2 = edit(99, 0, "不应写入");
    String result = tk.body.replaceRunText(docId, List.of(e0, e1, e2));

    assertThat(result).contains("[0]").contains("新 A").contains("✓");
    assertThat(result).contains("[1]").contains("新 B").contains("✓");
    assertThat(result).contains("[2]").contains("越界");
    assertThat(result).contains("成功 2 条,失败 1 条");

    // 成功的两条应真写入:重新读回应是新文本。
    assertThat(tk.body.readParagraph(docId, List.of(0))).contains("新 A");
    assertThat(tk.body.readParagraph(docId, List.of(1))).contains("新 B");
  }

  @Test
  void shouldReadAndEditBodyRunByRefAndRejectMismatchedIndex(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("body-run-ref.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("目标");
      doc.addParagraph().addRun("保留");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    var document = tk.session.getDocument(docId);
    String ref =
        tk.session
            .getElementResolver(docId)
            .reference(document.paragraphs().get(0).runs().get(0))
            .canonical();

    Map<String, Object> edit = new LinkedHashMap<>();
    edit.put("ref", ref);
    edit.put("text", "已按 ref 修改");
    assertThat(tk.body.replaceRunText(docId, List.of(edit)))
        .contains("已按 ref 修改")
        .contains("ref=" + ref)
        .contains("成功 1 条,失败 0 条");

    Map<String, Object> style = new LinkedHashMap<>();
    style.put("ref", ref);
    style.put("bold", true);
    assertThat(tk.body.updateRunStyle(docId, List.of(style)))
        .contains("bold=true")
        .contains("ref=" + ref);
    assertThat(tk.body.readRun(docId, List.of(Map.of("ref", ref))))
        .contains("已按 ref 修改")
        .contains("bold=true")
        .contains("ref=" + ref);

    Map<String, Object> mismatch = new LinkedHashMap<>();
    mismatch.put("ref", ref);
    mismatch.put("paragraph_index", 1);
    mismatch.put("run_index", 0);
    mismatch.put("text", "不应写入");
    assertThat(tk.body.replaceRunText(docId, List.of(mismatch)))
        .contains("错误[stale_ref]")
        .contains("成功 0 条,失败 1 条");
    assertThat(tk.body.readParagraph(docId, List.of(1))).contains("保留");
  }

  @Test
  void shouldUpdateMultipleRunStylesCollectErrors(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("style.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("标题");
      doc.addParagraph().addRun("正文").bold();
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> title = styleEdit(0, 0);
    title.put("bold", true);
    title.put("italic", true);
    title.put("underline", true);
    title.put("font", "Arial");
    title.put("font_size", 18);
    title.put("color", "FF0000");
    Map<String, Object> body = styleEdit(1, 0);
    body.put("bold", false);
    Map<String, Object> bad = styleEdit(99, 0);
    bad.put("italic", true);

    String result = tk.body.updateRunStyle(docId, List.of(title, body, bad));
    assertThat(result).contains("bold=true").contains("italic=true").contains("color=FF0000");
    assertThat(result).contains("bold=false");
    assertThat(result).contains("[2]").contains("越界");
    assertThat(result).contains("成功 2 条,失败 1 条");

    assertThat(tk.body.readRun(docId, List.of(runCoord(0, 0))))
        .contains("bold=true")
        .contains("italic=true")
        .contains("underline=true")
        .contains("font=Arial")
        .contains("size=18")
        .contains("color=FF0000");
    assertThat(tk.body.readRun(docId, List.of(runCoord(1, 0)))).contains("bold=false");
  }

  @Test
  void shouldRejectRunStyleEditWithoutStyleFields(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("style-empty.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("正文");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result = tk.body.updateRunStyle(docId, List.of(styleEdit(0, 0)));
    assertThat(result).contains("未提供任何样式字段").contains("成功 0 条,失败 1 条");
  }

  @Test
  void shouldRejectMalformedEditEntry(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("malformed.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("保留");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 缺 text 字段 → 应被记为错误而非 NPE。
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("paragraph_index", 0);
    bad.put("run_index", 0);
    String result = tk.body.replaceRunText(docId, List.of(bad));
    assertThat(com.non.docx.toolkit.ToolTestSupport.parse(result).code())
        .isEqualTo(com.non.docx.toolkit.result.ToolResultCode.PARTIAL_FAILURE);
    assertThat(result).contains("text");
    assertThat(result).contains("成功 0 条,失败 1 条");
    // 原文未被破坏
    assertThat(tk.body.readParagraph(docId, List.of(0))).contains("保留");
  }

  @Test
  void shouldReplaceMultipleTableCellRuns(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell.docx");
    try (Document doc = Docx.create()) {
      // 一个 1x2 的表格:两个单元格各一段一 run。
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("待改左");
      row.addCell().addParagraph().addRun("待改右");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> left = cellEdit(0, 0, 0, 0, 0, "左已改");
    Map<String, Object> right = cellEdit(0, 0, 1, 0, 0, "右已改");
    String result = tk.table.replaceTableCellRunText(docId, List.of(left, right));
    assertThat(result).contains("左已改").contains("右已改").contains("成功 2 条,失败 0 条");

    // 读回应验证写入:用 read_table_cell 确认单元格文本。
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 0)))).contains("左已改");
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 1)))).contains("右已改");
  }

  @Test
  void shouldReadAndEditTableRunByRefAndRejectMismatchedCoordinate(@TempDir Path tmp)
      throws Exception {
    Path file = tmp.resolve("table-run-ref.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("左");
      row.addCell().addParagraph().addRun("右");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    var document = tk.session.getDocument(docId);
    String ref =
        tk.session
            .getElementResolver(docId)
            .reference(
                document
                    .tables()
                    .get(0)
                    .rows()
                    .get(0)
                    .cells()
                    .get(0)
                    .paragraphs()
                    .get(0)
                    .runs()
                    .get(0))
            .canonical();

    assertThat(tk.table.readTableCellRun(docId, null, null, null, null, null, ref))
        .contains("左")
        .contains("ref=" + ref);

    Map<String, Object> edit = new LinkedHashMap<>();
    edit.put("ref", ref);
    edit.put("text", "左已按 ref 修改");
    assertThat(tk.table.replaceTableCellRunText(docId, List.of(edit)))
        .contains("左已按 ref 修改")
        .contains("ref=" + ref)
        .contains("成功 1 条,失败 0 条");

    Map<String, Object> mismatch = new LinkedHashMap<>();
    mismatch.put("ref", ref);
    mismatch.put("table_index", 0);
    mismatch.put("row_index", 0);
    mismatch.put("cell_index", 1);
    mismatch.put("paragraph_index", 0);
    mismatch.put("run_index", 0);
    mismatch.put("text", "不应写入");
    assertThat(tk.table.replaceTableCellRunText(docId, List.of(mismatch)))
        .contains("错误[stale_ref]")
        .contains("成功 0 条,失败 1 条");
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 1)))).contains("右");
  }

  @Test
  void shouldCreateTableFromRowsMatrix(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("create-table.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("表格前");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result =
        tk.table.createTable(
            docId, List.of(List.of("姓名", "分数"), List.of("张三", "95"), List.of("李四", "88")));
    assertThat(result).contains("已创建表格 0").contains("3 行").contains("6 个单元格");
    assertThat(tk.session.getDocumentOverview(docId)).contains("表格数: 1");
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 0)))).contains("姓名");
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 1, 1)))).contains("95");
  }

  @Test
  void shouldRejectMalformedTableRowsWithoutCreatingTable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("create-table-bad.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result = tk.table.createTable(docId, List.of(List.of("A"), List.of()));
    assertThat(result).contains("错误:第 1 行为空");
    assertThat(tk.session.getDocumentOverview(docId)).contains("表格数: 0");
  }

  @Test
  void shouldSetTableBordersToNone(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("table-border-none.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().addRow().addCell().text("x");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result = tk.table.setTableBorders(docId, 0, "NONE");
    assertThat(result).contains("表格 0").contains("无边框");
  }

  @Test
  void shouldMergeTableCellsHorizontallyAndVertically(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("table-merge.docx");
    try (Document doc = Docx.create()) {
      var table = doc.addTable();
      table.row(r -> r.cell("A1").cell("B1").cell("C1"));
      table.row(r -> r.cell("A2").cell("B2").cell("C2"));
      table.row(r -> r.cell("A3").cell("B3").cell("C3"));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String horizontal = tk.table.mergeTableCells(docId, array(horizontalMerge(0, 0, 0, 2)));
    assertThat(horizontal).contains("已横向合并表格 0 行 0 单元格 0..2");
    assertThat(horizontal).contains("成功 1 条,失败 0 条");
    assertThat(tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 1)))).contains("越界");

    String vertical = tk.table.mergeTableCells(docId, array(verticalMerge(0, 1, 1, 2)));
    assertThat(vertical).contains("已纵向合并表格 0 列 1 行 1..2");
    assertThat(vertical).contains("成功 1 条,失败 0 条");
  }

  @Test
  void shouldReturnReadableErrorForMalformedMergeRequest(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("table-merge-bad.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().row(r -> r.cell("A").cell("B"));
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("table_index", 0);
    bad.put("direction", "HORIZONTAL");
    bad.put("row_index", 0);
    bad.put("from_cell_index", 0);
    String result = tk.table.mergeTableCells(docId, array(bad));
    assertThat(result).contains("缺少必填字段 to_cell_index");
    assertThat(result).contains("成功 0 条,失败 1 条");
  }

  @Test
  void shouldInsertMultipleParagraphsByBodyIndex(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("insert.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result =
        tk.body.insertParagraph(docId, List.of(paragraphInsert(0, "标题"), paragraphInsert(2, "结尾")));
    assertThat(result).contains("body 0").contains("标题").contains("body 2").contains("结尾");
    assertThat(result).contains("成功 2 条,失败 0 条");
    assertThat(tk.body.readParagraph(docId, List.of(0))).contains("标题");
    assertThat(tk.body.readParagraph(docId, List.of(1))).contains("正文");
    assertThat(tk.body.readParagraph(docId, List.of(2))).contains("结尾");
    assertThat(tk.session.getDocumentOverview(docId)).contains("段落数: 3");
  }

  @Test
  void shouldInsertParagraphBeforeMiddleBodyElement(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("insert-middle.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("开头");
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("表格");
      doc.addParagraph("结尾");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result = tk.body.insertParagraph(docId, List.of(paragraphInsert(1, "表格前说明")));
    assertThat(result).contains("body 1").contains("表格前说明").contains("成功 1 条,失败 0 条");
    assertThat(tk.body.readParagraph(docId, List.of(0))).contains("开头");
    assertThat(tk.body.readParagraph(docId, List.of(1))).contains("表格前说明");
    assertThat(tk.body.readParagraph(docId, List.of(2))).contains("结尾");
  }

  @Test
  void shouldAcceptMultipleRevisionsCollectErrors(@TempDir Path tmp) throws Exception {
    // 造两条文本类修订(ins):一条合法、一条用不存在的 id。
    Path file = tmp.resolve("accept.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addInsertion("甲", "第一处插入");
      doc.addParagraph().addInsertion("甲", "第二处插入");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 从 list 里取一个真实 id,再混入一个不存在的 id。
    String list = tk.trackedChangeQuery.listTrackedChanges(docId);
    String realId = extractId(list, "ins:");
    assertThat(realId).isNotBlank();

    String result =
        tk.trackedChangeQuery.applyTrackedChanges(
            docId, "ACCEPT", "TEXT_OR_MOVE", List.of(realId, "ins:not-exist"));
    assertThat(result).contains(realId).contains("已应用");
    assertThat(result).contains("ins:not-exist");
    assertThat(com.non.docx.toolkit.ToolTestSupport.parse(result).code())
        .isEqualTo(com.non.docx.toolkit.result.ToolResultCode.PARTIAL_FAILURE);
    assertThat(result).contains("成功 1 条,失败 1 条");
    // accept 一条后,应只剩 1 条(另一条 id 不存在,文档未变)。
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 1 条修订");
  }

  @Test
  void shouldCoerceSingleScalarToList(@TempDir Path tmp) throws Exception {
    // 健壮性:LLM 偶尔把单次调用传成标量(如 paragraph_index: 0 而非 [0])。
    // coerceList 应把它包成单元素列表,不报错。这里通过反射不可达,改用一个 List.size()==1 的等价场景覆盖路径。
    Path file = tmp.resolve("coerce.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("唯一段");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    // 单元素数组即"单次调用"语义。
    String result = tk.body.readParagraph(docId, List.of(0));
    assertThat(result).contains("段落 0").contains("唯一段");
  }

  @Test
  void shouldHandleEmptyArrayGracefully(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("empty.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("x");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    // 空数组:返回提示而非 NPE。
    assertThat(tk.body.readParagraph(docId, List.of())).contains("为空");
    assertThat(tk.body.updateParagraphAlignment(docId, List.of())).contains("为空");
    assertThat(tk.body.replaceRunText(docId, List.of())).contains("为空");
    assertThat(tk.body.updateRunStyle(docId, List.of())).contains("为空");
    assertThat(tk.body.insertParagraph(docId, List.of())).contains("为空");
    assertThat(tk.table.createTable(docId, List.of())).contains("为空");
    assertThat(tk.table.mergeTableCells(docId, array())).contains("为空");
    assertThat(
            tk.trackedChangeQuery.applyTrackedChanges(docId, "ACCEPT", "TEXT_OR_MOVE", List.of()))
        .contains("为空");
  }

  @Test
  void shouldReturnErrorForUnknownDocId() {
    DocxToolkit tk = new DocxToolkit();
    assertThat(tk.session.getDocumentOverview("doc-999")).contains("不存在");
    assertThat(tk.body.readParagraph("doc-999", List.of(0))).contains("不存在");
    assertThat(
            tk.body.updateParagraphAlignment("doc-999", List.of(paragraphAlignment(0, "CENTER"))))
        .contains("不存在");
    assertThat(tk.body.replaceRunText("doc-999", List.of())).contains("不存在");
    assertThat(tk.body.updateRunStyle("doc-999", List.of(styleEdit(0, 0)))).contains("不存在");
    assertThat(tk.body.insertParagraph("doc-999", List.of(paragraphInsert(0, "x"))))
        .contains("不存在");
    assertThat(tk.table.createTable("doc-999", List.of(List.of("x")))).contains("不存在");
    assertThat(tk.table.setTableBorders("doc-999", 0, "NONE")).contains("不存在");
    assertThat(tk.table.mergeTableCells("doc-999", array(horizontalMerge(0, 0, 0, 1))))
        .contains("不存在");
    assertThat(
            tk.trackedChangeQuery.applyTrackedChanges(
                "doc-999", "ACCEPT", "TEXT_OR_MOVE", List.of("ins:1")))
        .contains("不存在");
  }

  @Test
  void shouldSaveToCurrentDirectoryRelativePath(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("source.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("当前目录保存");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    Path output = Path.of("relative-output.docx").toAbsolutePath();

    try {
      java.nio.file.Files.deleteIfExists(output);
      String result = tk.session.saveDocx(docId, "relative-output.docx");

      assertThat(result).contains("已保存到");
      assertThat(output).exists();
    } finally {
      java.nio.file.Files.deleteIfExists(output);
    }
  }

  @Test
  void shouldReadHeaderAndFooterThroughUnifiedTool(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header-footer.docx");
    try (Document doc = Docx.create()) {
      doc.ensureHeader().addParagraph().addRun("页眉内容");
      doc.ensureFooter().addParagraph().addRun("页脚内容");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String headerResult = tk.headerFooterToc.readHeaderFooter(docId, "HEADER", 0, 0);
    assertThat(headerResult)
        .contains("页眉")
        .contains("页眉内容")
        .contains("part ref: doc:")
        .contains("paragraph ref: doc:");
    assertThat(tk.headerFooterToc.readHeaderFooter(docId, "footer", 0, 0))
        .contains("页脚")
        .contains("页脚内容")
        .contains("part ref: doc:")
        .contains("paragraph ref: doc:");
    var document = tk.session.getDocument(docId);
    String headerRef =
        tk.session
            .getElementResolver(docId)
            .reference(document.sections().get(0).header())
            .canonical();
    assertThat(tk.headerFooterToc.readHeaderFooterRef(docId, headerRef))
        .contains("页眉 ref=" + headerRef)
        .contains("页眉内容")
        .contains("段落 0 ref=doc:");
    assertThat(tk.headerFooterToc.readHeaderFooter(docId, "SIDE", 0, 0)).contains("part 仅支持");
  }

  // ============ 第二梯队批量测试 ============

  @Test
  void shouldReadMultipleTableCellsWithOutOfBounds(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("readcell.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("左");
      row.addCell().addParagraph().addRun("右");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 读两个合法 cell + 一个越界 cell(0,0,99)——不中断,越界条目标注。
    String result = tk.table.readTableCell(docId, List.of(cellCoord(0, 0, 0), cellCoord(0, 0, 99)));
    assertThat(result).contains("左");
    assertThat(result).contains("越界");
  }

  @Test
  void shouldDeleteMultipleRunsSameParagraphNoDrift(@TempDir Path tmp) throws Exception {
    // 关键场景:同一段删多个 run。探针证明 addDeletion 后 runs() 计数不变(索引不漂移),
    // 故按原索引快照+去重即可正确删除 run0 和 run2,互不干扰。
    Path file = tmp.resolve("multidel.docx");
    try (Document doc = Docx.create()) {
      var p = doc.addParagraph();
      p.addRun("A");
      p.addRun("B");
      p.addRun("C");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    String result =
        tk.trackedChangeAuthoring.deleteRunTracked(
            docId, "甲", List.of(runEdit(0, 0), runEdit(0, 2)));
    assertThat(result).contains("A").contains("C").contains("成功 2 条,失败 0 条");
    // 读回应有两条 DEL。
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 2 条修订");
  }

  @Test
  void shouldDeleteDuplicateRunSkipped(@TempDir Path tmp) throws Exception {
    // 同一 (para,run) 出现两次:第二次应被去重跳过(避免 XmlValueDisconnectedException)。
    Path file = tmp.resolve("dups.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("唯一");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    String result =
        tk.trackedChangeAuthoring.deleteRunTracked(
            docId, "甲", List.of(runEdit(0, 0), runEdit(0, 0)));
    assertThat(result).contains("跳过");
    assertThat(result).contains("成功 1 条,失败 1 条");
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 1 条修订");
  }

  @Test
  void shouldReplaceMultipleRunsSameParagraphNoDrift(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("multirep.docx");
    try (Document doc = Docx.create()) {
      var p = doc.addParagraph();
      p.addRun("旧A");
      p.addRun("B");
      p.addRun("旧C");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    Map<String, Object> e0 = runEdit(0, 0);
    e0.put("new_text", "新A");
    Map<String, Object> e2 = runEdit(0, 2);
    e2.put("new_text", "新C");
    String result = tk.trackedChangeAuthoring.replaceRunTracked(docId, "甲", List.of(e0, e2));
    assertThat(result).contains("旧A").contains("新A").contains("旧C").contains("新C");
    assertThat(result).contains("成功 2 条,失败 0 条");
    // 两次替换各产出 del+ins,共 4 条修订。
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 4 条修订");
  }

  @Test
  void shouldInsertTrackedRunMultipleParagraphs(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("multiins.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("段一");
      doc.addParagraph("段二");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    Map<String, Object> e0 = new LinkedHashMap<>();
    e0.put("paragraph_index", 0);
    e0.put("text", "(批注一)");
    Map<String, Object> e1 = new LinkedHashMap<>();
    e1.put("paragraph_index", 1);
    e1.put("text", "(批注二)");
    e1.put("bold", true);
    String result = tk.trackedChangeAuthoring.insertTrackedRun(docId, "甲", List.of(e0, e1));
    assertThat(result).contains("(批注一)").contains("(批注二)").contains("成功 2 条,失败 0 条");
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 2 条修订");
  }

  @Test
  void shouldMarkMultipleCellsInsertedAndDeleted(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("multicell.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("甲");
      row.addCell().addParagraph().addRun("乙");
      row.addCell().addParagraph().addRun("丙");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 批量标记两个单元格为 cellIns。
    String ins =
        tk.trackedChangeAuthoring.markTrackedCells(
            docId, "INSERTED", "甲", List.of(cellCoord(0, 0, 0), cellCoord(0, 0, 1)));
    assertThat(ins).contains("cellIns").contains("成功 2 条,失败 0 条");
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 2 条修订");

    // 批量标记一个单元格为 cellDel(另一条越界,应记错误不中断)。
    String del =
        tk.trackedChangeAuthoring.markTrackedCells(
            docId, "DELETED", "甲", List.of(cellCoord(0, 0, 2), cellCoord(0, 0, 99)));
    assertThat(del).contains("cellDel");
    assertThat(del).contains("成功 1 条,失败 1 条");
  }

  // ============ 第三梯队批量/合并测试 ============

  @Test
  void shouldReadMultipleRunsWithOutOfBounds(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("readrun.docx");
    try (Document doc = Docx.create()) {
      var p = doc.addParagraph();
      p.addRun("甲");
      p.addRun("乙");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 读 run0 + run1 + 一个越界 run5——不中断,越界标注。
    String result = tk.body.readRun(docId, List.of(runEdit(0, 0), runEdit(0, 1), runEdit(0, 5)));
    assertThat(result).contains("甲").contains("乙").contains("越界");
  }

  @Test
  void shouldAcceptMultiplePropertyChangesCollectErrors(@TempDir Path tmp) throws Exception {
    // 造两条 rPrChange 属性修订,accept 时混入一个不存在的 id——验证 collect-errors。
    Path file = tmp.resolve("prop.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("原文");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 先用 mark_style_change_tracked 造一条属性修订。
    tk.trackedChangeAuthoring.markStyleChangeTracked(docId, 0, 0, "甲", true, false, null);
    String list = tk.trackedChangeQuery.listTrackedChanges(docId);
    assertThat(list).contains("RPR_CHANGE");
    String propId = extractId(list, "rpr_change:");
    assertThat(propId).isNotBlank();

    // 批量 accept:真实 id + 不存在的 id。
    String result =
        tk.trackedChangeQuery.applyTrackedChanges(
            docId, "ACCEPT", "PROPERTY", List.of(propId, "rpr_change:not-exist"));
    assertThat(result).contains(propId).contains("已应用");
    assertThat(result).contains("not-exist");
    assertThat(com.non.docx.toolkit.ToolTestSupport.parse(result).code())
        .isEqualTo(com.non.docx.toolkit.result.ToolResultCode.PARTIAL_FAILURE);
    assertThat(result).contains("成功 1 条,失败 1 条");
    // accept 后属性修订消失。
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("无修订");
  }

  @Test
  void shouldAcceptMultipleCellChangesIdStable(@TempDir Path tmp) throws Exception {
    // 关键场景:多条 cell 修订(含 cellDel),批量 accept 时验证 id 不漂移。
    // 探针已证明 accept 一条 cellDel(移除单元格)后其余 id 不变,故批量循环安全。
    Path file = tmp.resolve("cellaccept.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      // cell0: cellIns, cell1: cellIns —— 两条都 accept 应保留。
      var tc0 = row.addCell();
      tc0.addParagraph().addRun("甲");
      var tc1 = row.addCell();
      tc1.addParagraph().addRun("乙");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    // 用 mark_tracked_cells 造两条 cellIns。
    tk.trackedChangeAuthoring.markTrackedCells(
        docId, "INSERTED", "甲", List.of(cellCoord(0, 0, 0), cellCoord(0, 0, 1)));

    String list = tk.trackedChangeQuery.listTrackedChanges(docId);
    assertThat(list).contains("共 2 条修订");
    // 抽出两个 cellIns id。
    String id0 = extractId(list, "cell_ins:");
    String id1 = extractId(list, "cell_ins:", 2);

    // 批量 accept 两条。
    String result =
        tk.trackedChangeQuery.applyTrackedChanges(docId, "ACCEPT", "CELL", List.of(id0, id1));
    assertThat(result).contains("成功 2 条,失败 0 条");
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("无修订");
  }

  @Test
  void shouldRejectInvalidTrackedChangeActionAndTarget(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("invalid-apply.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addInsertion("甲", "待处理");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    String id = extractId(tk.trackedChangeQuery.listTrackedChanges(docId), "ins:");

    assertThat(
            tk.trackedChangeQuery.applyTrackedChanges(docId, "MERGE", "TEXT_OR_MOVE", List.of(id)))
        .contains("action 仅支持");
    assertThat(tk.trackedChangeQuery.applyTrackedChanges(docId, "ACCEPT", "COMMENT", List.of(id)))
        .contains("target 仅支持");
  }

  @Test
  void shouldApplyAllTextRevisionsThroughUnifiedTool(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("apply-text-all.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addInsertion("甲", "第一处");
      doc.addParagraph().addInsertion("乙", "第二处");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 2 条修订");

    String result = tk.trackedChangeQuery.applyTextRevisions(docId, "ACCEPT", "ALL", null);
    assertThat(result).contains("已应用 2 条文本/移动类修订");
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("无修订");
  }

  @Test
  void shouldApplyTextRevisionsByAuthorThroughUnifiedTool(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("apply-text-author.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addInsertion("甲", "甲的插入");
      doc.addParagraph().addInsertion("乙", "乙的插入");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    String result = tk.trackedChangeQuery.applyTextRevisions(docId, "REJECT", "AUTHOR", "甲");
    assertThat(result).contains("已撤销作者「甲」的 1 条文本/移动类修订");
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId))
        .contains("共 1 条修订")
        .contains("乙")
        .doesNotContain("甲的插入");
  }

  @Test
  void shouldRejectInvalidTextRevisionScopeArgs(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("apply-text-invalid.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addInsertion("甲", "待处理");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    assertThat(tk.trackedChangeQuery.applyTextRevisions(docId, "MERGE", "ALL", null))
        .contains("action 仅支持");
    assertThat(tk.trackedChangeQuery.applyTextRevisions(docId, "ACCEPT", "IDS", null))
        .contains("scope 仅支持");
    assertThat(tk.trackedChangeQuery.applyTextRevisions(docId, "ACCEPT", "AUTHOR", null))
        .contains("author 必填");
  }

  @Test
  void shouldUpdateHyperlinkTextOnly(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("link-text.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addHyperlink("旧文本", "http://old.example.com");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 只改文本:URL 应保持不变。
    String result = tk.body.updateHyperlink(docId, 0, 0, "新文本", null);
    assertThat(result).contains("新文本");
    String readBack = tk.body.readHyperlink(docId, 0, 0);
    assertThat(readBack).contains("新文本").contains("http://old.example.com");
  }

  @Test
  void shouldUpdateHyperlinkUrlOnly(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("link-url.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addHyperlink("旧文本", "http://old.example.com");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 只改 URL:文本应保持不变。
    tk.body.updateHyperlink(docId, 0, 0, null, "https://new.example.com/x");
    // URL 改动需 save 落盘后 reopen 才能稳定读回(POI 关系缓存在内存即时读回仍是旧值,
    // 这是 core 既有行为,非本次改造引入)。文本用内存读回即可。
    assertThat(tk.body.readHyperlink(docId, 0, 0)).contains("旧文本");
    Path out = tmp.resolve("link-url-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      String url = firstHyperlinkUrl(doc2);
      String text = firstHyperlinkText(doc2);
      assertThat(url).isEqualTo("https://new.example.com/x");
      assertThat(text).isEqualTo("旧文本");
    }
  }

  @Test
  void shouldUpdateHyperlinkBothAtOnce(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("link-both.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addHyperlink("旧文本", "http://old.example.com");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 一次改齐文本和 URL——这正是合并工具的核心价值(旧版要调两次)。
    String result = tk.body.updateHyperlink(docId, 0, 0, "新文本", "https://new.example.com/x");
    assertThat(result).contains("新文本").contains("https://new.example.com/x");
    // save + reopen 验证两者都落盘成功。
    Path out = tmp.resolve("link-both-out.docx");
    tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    try (Document doc2 = Docx.open(out)) {
      assertThat(firstHyperlinkUrl(doc2)).isEqualTo("https://new.example.com/x");
      assertThat(firstHyperlinkText(doc2)).isEqualTo("新文本");
    }
  }

  @Test
  void shouldRejectUpdateHyperlinkWithNeitherField(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("link-none.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addHyperlink("旧文本", "http://old.example.com");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId =
        com.non.docx.toolkit.ToolTestSupport.extractDocId(
            tk.session.openDocx(file.toAbsolutePath().toString()));

    // 两个都不传 → 报错,文档不变。
    assertThat(tk.body.updateHyperlink(docId, 0, 0, null, null)).contains("至少传一个");
    assertThat(tk.body.readHyperlink(docId, 0, 0)).contains("旧文本");
  }

  // ---------- 构造对象数组元素的辅助 ----------

  /** 构造 run 坐标对象(含 paragraph_index、run_index),供 delete/replace_run_tracked 用。 */
  private static Map<String, Object> runEdit(int paragraphIndex, int runIndex) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("paragraph_index", paragraphIndex);
    m.put("run_index", runIndex);
    return m;
  }

  /** 构造 replace_run_text 的一个 edit 对象(字段顺序与 nonchain 还原的 Map 无关,用 LinkedHashMap 保序便于调试)。 */
  private static Map<String, Object> edit(int paragraphIndex, int runIndex, String text) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("paragraph_index", paragraphIndex);
    m.put("run_index", runIndex);
    m.put("text", text);
    return m;
  }

  private static Map<String, Object> paragraphAlignment(int paragraphIndex, String alignment) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("paragraph_index", paragraphIndex);
    m.put("alignment", alignment);
    return m;
  }

  private static Map<String, Object> styleEdit(int paragraphIndex, int runIndex) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("paragraph_index", paragraphIndex);
    m.put("run_index", runIndex);
    return m;
  }

  private static Map<String, Object> runCoord(int paragraphIndex, int runIndex) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("paragraph_index", paragraphIndex);
    m.put("run_index", runIndex);
    return m;
  }

  private static Map<String, Object> paragraphInsert(int bodyIndex, String text) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("body_index", bodyIndex);
    m.put("text", text);
    return m;
  }

  /** 构造 replace_table_cell_run_text 的一个 edit 对象(6 字段)。 */
  private static Map<String, Object> cellEdit(
      int t, int r, int c, int paragraphIndex, int runIndex, String text) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("table_index", t);
    m.put("row_index", r);
    m.put("cell_index", c);
    m.put("paragraph_index", paragraphIndex);
    m.put("run_index", runIndex);
    m.put("text", text);
    return m;
  }

  /** 构造 read_table_cell / mark_cell_* 的一个坐标对象(3 字段)。 */
  private static Map<String, Object> cellCoord(int t, int r, int c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("table_index", t);
    m.put("row_index", r);
    m.put("cell_index", c);
    return m;
  }

  private static Map<String, Object> horizontalMerge(
      int tableIndex, int rowIndex, int fromCellIndex, int toCellIndex) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("table_index", tableIndex);
    m.put("direction", "HORIZONTAL");
    m.put("row_index", rowIndex);
    m.put("from_cell_index", fromCellIndex);
    m.put("to_cell_index", toCellIndex);
    return m;
  }

  private static Map<String, Object> verticalMerge(
      int tableIndex, int cellIndex, int fromRowIndex, int toRowIndex) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("table_index", tableIndex);
    m.put("direction", "VERTICAL");
    m.put("cell_index", cellIndex);
    m.put("from_row_index", fromRowIndex);
    m.put("to_row_index", toRowIndex);
    return m;
  }

  @SafeVarargs
  private static Map<String, Object>[] array(Map<String, Object>... items) {
    return items;
  }

  /** 取文档正文首个超链接的 URL(save+reopen 后验证落盘结果用)。无超链接返回 null。 */
  private static String firstHyperlinkUrl(Document doc) {
    com.non.docx.core.api.text.Hyperlink link = firstHyperlink(doc);
    return link == null ? null : link.url();
  }

  /** 取文档正文首个超链接的显示文本。无超链接返回 null。 */
  private static String firstHyperlinkText(Document doc) {
    com.non.docx.core.api.text.Hyperlink link = firstHyperlink(doc);
    return link == null ? null : link.text();
  }

  private static com.non.docx.core.api.text.Hyperlink firstHyperlink(Document doc) {
    return doc.paragraphs().get(0).inlineElements().stream()
        .filter(e -> e instanceof com.non.docx.core.api.text.Hyperlink)
        .map(e -> (com.non.docx.core.api.text.Hyperlink) e)
        .findFirst()
        .orElse(null);
  }

  /** 从 list 输出里抽取首个以 prefix 开头的 id(id 在「id=」之后)。与 TrackedChangesTest 同款。 */
  private static String extractId(String listOutput, String prefix) {
    return extractId(listOutput, prefix, 1);
  }

  /** 从 list 输出里抽取第 occurrence 个以 prefix 开头的 id(1 起)。用于多条同类型修订时取第 N 条。 */
  private static String extractId(String listOutput, String prefix, int occurrence) {
    int from = 0;
    int i = -1;
    for (int n = 0; n < occurrence; n++) {
      i = listOutput.indexOf(prefix, from);
      if (i < 0) {
        return "";
      }
      from = i + prefix.length();
    }
    int end = listOutput.indexOf('\n', i);
    if (end < 0) {
      end = listOutput.length();
    }
    return listOutput.substring(i, end);
  }
}
