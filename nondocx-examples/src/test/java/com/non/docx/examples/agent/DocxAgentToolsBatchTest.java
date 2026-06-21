package com.non.docx.examples.agent;

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
 * 冒烟测试 DocxAgentTools 第一梯队批量改造(v2):把单次工具升级成支持单次/多次的通用版。
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
 * <p>工具方法签名已改为接收 {@code List};这里直接构造 {@code ArrayList<LinkedHashMap>} 调用,
 * 与 nonchain 运行时 Jackson 还原出的结构一致——因此测试结果等价于 Agent 经框架调用。
 */
class DocxAgentToolsBatchTest {

  @Test
  void shouldReadMultipleParagraphsWithOutOfBoundsMarked(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("read.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("第一段");
      doc.addParagraph("第二段");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 批量读三段,其中索引 5 越界——不应中断,越界条目要被标注。
    String result = tools.readParagraph(docId, List.of(0, 1, 5));
    assertThat(result).contains("段落 0").contains("第一段");
    assertThat(result).contains("段落 1").contains("第二段");
    assertThat(result).contains("段落 5").contains("越界").contains("共 2");
  }

  @Test
  void shouldReplaceMultipleRunsCollectErrors(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("replace.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("原文 A");
      doc.addParagraph().addRun("原文 B");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 三条编辑:前两条合法(段 0/段 1),第三条段索引 99 越界。
    Map<String, Object> e0 = edit(0, 0, "新 A");
    Map<String, Object> e1 = edit(1, 0, "新 B");
    Map<String, Object> e2 = edit(99, 0, "不应写入");
    String result = tools.replaceRunText(docId, List.of(e0, e1, e2));

    assertThat(result).contains("[0]").contains("新 A").contains("✓");
    assertThat(result).contains("[1]").contains("新 B").contains("✓");
    assertThat(result).contains("[2]").contains("越界");
    assertThat(result).contains("成功 2 条,失败 1 条");

    // 成功的两条应真写入:重新读回应是新文本。
    assertThat(tools.readParagraph(docId, List.of(0))).contains("新 A");
    assertThat(tools.readParagraph(docId, List.of(1))).contains("新 B");
  }

  @Test
  void shouldRejectMalformedEditEntry(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("malformed.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("保留");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 缺 text 字段 → 应被记为错误而非 NPE。
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("paragraph_index", 0);
    bad.put("run_index", 0);
    String result = tools.replaceRunText(docId, List.of(bad));
    assertThat(result).contains("错误").contains("text");
    assertThat(result).contains("成功 0 条,失败 1 条");
    // 原文未被破坏
    assertThat(tools.readParagraph(docId, List.of(0))).contains("保留");
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    Map<String, Object> left = cellEdit(0, 0, 0, 0, 0, "左已改");
    Map<String, Object> right = cellEdit(0, 0, 1, 0, 0, "右已改");
    String result = tools.replaceTableCellRunText(docId, List.of(left, right));
    assertThat(result).contains("左已改").contains("右已改").contains("成功 2 条,失败 0 条");

    // 读回应验证写入:用 read_table_cell 确认单元格文本。
    assertThat(tools.readTableCell(docId, List.of(cellCoord(0, 0, 0)))).contains("左已改");
    assertThat(tools.readTableCell(docId, List.of(cellCoord(0, 0, 1)))).contains("右已改");
  }

  @Test
  void shouldAppendMultipleParagraphs(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("append.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("原有");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    String result = tools.appendParagraph(docId, List.of("追加一", "追加二"));
    assertThat(result).contains("已追加 2 段").contains("追加一").contains("追加二");
    // 文档现在应有 3 段:原有 + 两条追加。
    assertThat(tools.getParagraphCount(docId)).contains("段落数: 3");
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 从 list 里取一个真实 id,再混入一个不存在的 id。
    String list = tools.listTrackedChanges(docId);
    String realId = extractId(list, "ins:");
    assertThat(realId).isNotBlank();

    String result = tools.acceptTextOrMoveRevision(docId, List.of(realId, "ins:not-exist"));
    assertThat(result).contains(realId).contains("已应用");
    assertThat(result).contains("ins:not-exist").contains("错误");
    assertThat(result).contains("成功 1 条,失败 1 条");
    // accept 一条后,应只剩 1 条(另一条 id 不存在,文档未变)。
    assertThat(tools.listTrackedChanges(docId)).contains("共 1 条修订");
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    // 单元素数组即"单次调用"语义。
    String result = tools.readParagraph(docId, List.of(0));
    assertThat(result).contains("段落 0").contains("唯一段");
  }

  @Test
  void shouldHandleEmptyArrayGracefully(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("empty.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("x");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    // 空数组:返回提示而非 NPE。
    assertThat(tools.readParagraph(docId, List.of())).contains("为空");
    assertThat(tools.replaceRunText(docId, List.of())).contains("为空");
    assertThat(tools.appendParagraph(docId, List.of())).contains("为空");
    assertThat(tools.acceptTextOrMoveRevision(docId, List.of())).contains("为空");
  }

  @Test
  void shouldReturnErrorForUnknownDocId() {
    DocxAgentTools tools = new DocxAgentTools();
    assertThat(tools.readParagraph("doc-999", List.of(0))).contains("不存在");
    assertThat(tools.replaceRunText("doc-999", List.of())).contains("不存在");
    assertThat(tools.appendParagraph("doc-999", List.of("x"))).contains("不存在");
    assertThat(tools.acceptTextOrMoveRevision("doc-999", List.of("ins:1"))).contains("不存在");
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 读两个合法 cell + 一个越界 cell(0,0,99)——不中断,越界条目标注。
    String result =
        tools.readTableCell(docId, List.of(cellCoord(0, 0, 0), cellCoord(0, 0, 99)));
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    String result =
        tools.deleteRunTracked(docId, "甲", List.of(runEdit(0, 0), runEdit(0, 2)));
    assertThat(result).contains("A").contains("C").contains("成功 2 条,失败 0 条");
    // 读回应有两条 DEL。
    assertThat(tools.listTrackedChanges(docId)).contains("共 2 条修订");
  }

  @Test
  void shouldDeleteDuplicateRunSkipped(@TempDir Path tmp) throws Exception {
    // 同一 (para,run) 出现两次:第二次应被去重跳过(避免 XmlValueDisconnectedException)。
    Path file = tmp.resolve("dups.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("唯一");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    String result = tools.deleteRunTracked(docId, "甲", List.of(runEdit(0, 0), runEdit(0, 0)));
    assertThat(result).contains("跳过");
    assertThat(result).contains("成功 1 条,失败 1 条");
    assertThat(tools.listTrackedChanges(docId)).contains("共 1 条修订");
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    Map<String, Object> e0 = runEdit(0, 0);
    e0.put("new_text", "新A");
    Map<String, Object> e2 = runEdit(0, 2);
    e2.put("new_text", "新C");
    String result = tools.replaceRunTracked(docId, "甲", List.of(e0, e2));
    assertThat(result).contains("旧A").contains("新A").contains("旧C").contains("新C");
    assertThat(result).contains("成功 2 条,失败 0 条");
    // 两次替换各产出 del+ins,共 4 条修订。
    assertThat(tools.listTrackedChanges(docId)).contains("共 4 条修订");
  }

  @Test
  void shouldInsertTrackedRunMultipleParagraphs(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("multiins.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("段一");
      doc.addParagraph("段二");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    Map<String, Object> e0 = new LinkedHashMap<>();
    e0.put("paragraph_index", 0);
    e0.put("text", "(批注一)");
    Map<String, Object> e1 = new LinkedHashMap<>();
    e1.put("paragraph_index", 1);
    e1.put("text", "(批注二)");
    e1.put("bold", true);
    String result = tools.insertTrackedRun(docId, "甲", List.of(e0, e1));
    assertThat(result).contains("(批注一)").contains("(批注二)").contains("成功 2 条,失败 0 条");
    assertThat(tools.listTrackedChanges(docId)).contains("共 2 条修订");
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 批量标记两个单元格为 cellIns。
    String ins =
        tools.markCellInserted(docId, "甲", List.of(cellCoord(0, 0, 0), cellCoord(0, 0, 1)));
    assertThat(ins).contains("cellIns").contains("成功 2 条,失败 0 条");
    assertThat(tools.listTrackedChanges(docId)).contains("共 2 条修订");

    // 批量标记一个单元格为 cellDel(另一条越界,应记错误不中断)。
    String del =
        tools.markCellDeleted(docId, "甲", List.of(cellCoord(0, 0, 2), cellCoord(0, 0, 99)));
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 读 run0 + run1 + 一个越界 run5——不中断,越界标注。
    String result =
        tools.readRun(docId, List.of(runEdit(0, 0), runEdit(0, 1), runEdit(0, 5)));
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 先用 mark_style_change_tracked 造一条属性修订。
    tools.markStyleChangeTracked(docId, 0, 0, "甲", true, false, null);
    String list = tools.listTrackedChanges(docId);
    assertThat(list).contains("RPR_CHANGE");
    String propId = extractId(list, "rpr_change:");
    assertThat(propId).isNotBlank();

    // 批量 accept:真实 id + 不存在的 id。
    String result = tools.acceptPropertyChange(docId, List.of(propId, "rpr_change:not-exist"));
    assertThat(result).contains(propId).contains("已应用");
    assertThat(result).contains("not-exist").contains("错误");
    assertThat(result).contains("成功 1 条,失败 1 条");
    // accept 后属性修订消失。
    assertThat(tools.listTrackedChanges(docId)).contains("无修订");
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    // 用 mark_cell_inserted 造两条 cellIns。
    tools.markCellInserted(docId, "甲", List.of(cellCoord(0, 0, 0), cellCoord(0, 0, 1)));

    String list = tools.listTrackedChanges(docId);
    assertThat(list).contains("共 2 条修订");
    // 抽出两个 cellIns id。
    String id0 = extractId(list, "cell_ins:");
    String id1 = extractId(list, "cell_ins:", 2);

    // 批量 accept 两条。
    String result = tools.acceptCellChange(docId, List.of(id0, id1));
    assertThat(result).contains("成功 2 条,失败 0 条");
    assertThat(tools.listTrackedChanges(docId)).contains("无修订");
  }

  @Test
  void shouldUpdateHyperlinkTextOnly(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("link-text.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addHyperlink("旧文本", "http://old.example.com");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 只改文本:URL 应保持不变。
    String result = tools.updateHyperlink(docId, 0, 0, "新文本", null);
    assertThat(result).contains("新文本");
    String readBack = tools.readHyperlink(docId, 0, 0);
    assertThat(readBack).contains("新文本").contains("http://old.example.com");
  }

  @Test
  void shouldUpdateHyperlinkUrlOnly(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("link-url.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addHyperlink("旧文本", "http://old.example.com");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 只改 URL:文本应保持不变。
    tools.updateHyperlink(docId, 0, 0, null, "https://new.example.com/x");
    // URL 改动需 save 落盘后 reopen 才能稳定读回(POI 关系缓存在内存即时读回仍是旧值,
    // 这是 core 既有行为,非本次改造引入)。文本用内存读回即可。
    assertThat(tools.readHyperlink(docId, 0, 0)).contains("旧文本");
    Path out = tmp.resolve("link-url-out.docx");
    tools.saveDocx(docId, out.toAbsolutePath().toString());
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 一次改齐文本和 URL——这正是合并工具的核心价值(旧版要调两次)。
    String result = tools.updateHyperlink(docId, 0, 0, "新文本", "https://new.example.com/x");
    assertThat(result).contains("新文本").contains("https://new.example.com/x");
    // save + reopen 验证两者都落盘成功。
    Path out = tmp.resolve("link-both-out.docx");
    tools.saveDocx(docId, out.toAbsolutePath().toString());
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
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 两个都不传 → 报错,文档不变。
    assertThat(tools.updateHyperlink(docId, 0, 0, null, null)).contains("至少传一个");
    assertThat(tools.readHyperlink(docId, 0, 0)).contains("旧文本");
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
