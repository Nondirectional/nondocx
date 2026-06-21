package com.non.docx.examples.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange;

/**
 * 冒烟测试 DocxAgentTools 新增的 tracked changes 工具(H 组),不发 LLM、直接调工具方法验证返回串。
 *
 * <p>不重复测 core 的 accept/reject 正确性(那由 core 的 12 个单元测试背书);这里只验证工具层的包装:返回串格式、 docId 不存在的错误串、list 拿到
 * stable id 后 accept/reject 的衔接、cellMerge 被诚实拒绝。
 */
class DocxAgentToolsTrackedChangesTest {

  private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  @Test
  void shouldListAndAcceptCellRevision(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("revisions.docx");
    try (Document doc = Docx.create()) {
      XWPFDocument poi = doc.raw();
      CTBody body = poi.getDocument().getBody();
      CTTbl tbl = body.addNewTbl();
      CTRow tr = tbl.addNewTr();
      addCellIns(tr.addNewTc(), "1", "甲");
      addCellDel(tr.addNewTc(), "2", "乙");
      addCellMerge(tr.addNewTc(), "3", "丙");
      doc.save(file);
    }

    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    assertThat(docId).startsWith("doc-");

    // enabled:文档未显式开开关 → 未开启
    assertThat(tools.getTrackedChangesEnabled(docId)).contains("未开启");

    // list:三条 cell 修订,每条带 stable id
    String list = tools.listTrackedChanges(docId);
    assertThat(list).contains("共 3 条修订");
    assertThat(list).contains("CELL_INS").contains("CELL_DEL").contains("CELL_MERGE");
    // 抽出 cellIns 的 id(形如 cell_ins:...:1)
    String cellInsId = extractId(list, "cell_ins:");
    assertThat(cellInsId).isNotBlank();

    // accept cellIns:工具返回"已应用",再 list 只剩 2 条
    String acceptResult = tools.acceptCellChange(docId, List.of(cellInsId));
    assertThat(acceptResult).contains("已应用");
    assertThat(tools.listTrackedChanges(docId)).contains("共 2 条修订");
  }

  @Test
  void shouldRejectCellMergeBoundary(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("merge.docx");
    try (Document doc = Docx.create()) {
      CTTc tc = doc.raw().getDocument().getBody().addNewTbl().addNewTr().addNewTc();
      tc.addNewP();
      addCellMerge(tc, "3", "丙");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    String id = extractId(tools.listTrackedChanges(docId), "cell_merge:");

    // cellMerge 的 accept 应返回含错误的明细串(而非抛异常),整批失败 1 条
    String result = tools.acceptCellChange(docId, List.of(id));
    assertThat(result).contains("错误");
    assertThat(result).contains("失败 1 条");
    // 文档未变,cellMerge 仍在
    assertThat(tools.listTrackedChanges(docId)).contains("共 1 条修订");
  }

  @Test
  void shouldReturnErrorForUnknownDocId() {
    DocxAgentTools tools = new DocxAgentTools();
    assertThat(tools.getTrackedChangesEnabled("doc-999")).contains("不存在");
    assertThat(tools.listTrackedChanges("doc-999")).contains("不存在");
    assertThat(tools.acceptCellChange("doc-999", List.of("x"))).contains("不存在");
  }

  /** set_tracked_changes_enabled:开/关往返 + 读回一致 + docId 不存在返回错误串。 */
  @Test
  void shouldToggleTrackedChangesSwitch(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("switch.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("空文档");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());

    // 初始未开启
    assertThat(tools.getTrackedChangesEnabled(docId)).contains("未开启");
    // 开启:返回串标「已开启」,读回一致
    assertThat(tools.setTrackedChangesEnabled(docId, true)).contains("已开启");
    assertThat(tools.getTrackedChangesEnabled(docId)).contains("已开启");
    // 关闭:返回串标「已关闭」,读回一致
    assertThat(tools.setTrackedChangesEnabled(docId, false)).contains("已关闭");
    assertThat(tools.getTrackedChangesEnabled(docId)).contains("未开启");

    // docId 不存在返回错误串
    assertThat(tools.setTrackedChangesEnabled("doc-999", true)).contains("不存在");
  }

  // ---------- I 组:创作工具冒烟 ----------

  @Test
  void shouldDeleteRunTracked(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("del.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("要删的文字");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    String result = tools.deleteRunTracked(docId, "甲", List.of(runEdit(0, 0)));
    assertThat(result).contains("tracked del").contains("要删的文字");
    // 读回应有一条 DEL
    assertThat(tools.listTrackedChanges(docId)).contains("DEL");
  }

  @Test
  void shouldReplaceRunTracked(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("replace.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("旧文字");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    Map<String, Object> edit = runEdit(0, 0);
    edit.put("new_text", "新文字");
    String result = tools.replaceRunTracked(docId, "甲", List.of(edit));
    assertThat(result).contains("旧文字").contains("新文字").contains("del+ins");
    // 读回:一条 DEL(旧)+ 一条 INS(新)
    String list = tools.listTrackedChanges(docId);
    assertThat(list).contains("DEL").contains("INS");
  }

  @Test
  void shouldInsertTrackedRunWithStyle(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("ins.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("段首");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    Map<String, Object> edit = new LinkedHashMap<>();
    edit.put("paragraph_index", 0);
    edit.put("text", "插入文字");
    edit.put("bold", true);
    edit.put("italic", false);
    edit.put("color", "FF0000");
    String result = tools.insertTrackedRun(docId, "甲", List.of(edit));
    assertThat(result).contains("插入文字").contains("✓");
    // 读回应有一条 INS
    assertThat(tools.listTrackedChanges(docId)).contains("INS");
  }

  @Test
  void shouldMarkAndAcceptCellInserted(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().addRow().addCell().addParagraph().addRun("内容");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    String result = tools.markCellInserted(docId, "甲", List.of(cellCoord(0, 0, 0)));
    assertThat(result).contains("cellIns");
    // 读回应有一条 CELL_INS
    assertThat(tools.listTrackedChanges(docId)).contains("CELL_INS");
  }

  @Test
  void shouldMoveRunTracked(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("被移走的文字");
      doc.addParagraph("目标段");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    String result = tools.moveRunTracked(docId, 0, 0, 1, "甲");
    assertThat(result).contains("moveFrom");
    // 读回应有配对的 MOVE_FROM + MOVE_TO
    String list = tools.listTrackedChanges(docId);
    assertThat(list).contains("MOVE_FROM").contains("MOVE_TO");
  }

  @Test
  void shouldRejectWrongFamily(@TempDir Path tmp) throws Exception {
    // 文本类 ins 用 accept_property_change 应返回错误串(family 不符)
    Path file = tmp.resolve("text.docx");
    try (Document doc = Docx.create()) {
      var p = doc.raw().getDocument().getBody().addNewP();
      var ins = p.addNewIns();
      ins.setId(java.math.BigInteger.ONE);
      ins.setAuthor("甲");
      ins.addNewR().addNewT().setStringValue("X");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    String id = extractId(tools.listTrackedChanges(docId), "ins:");
    // family 不符 → 该条记错误不中断
    assertThat(tools.acceptPropertyChange(docId, List.of(id))).contains("错误").contains("失败 1 条");
  }

  /** 从 list 输出里抽取首个以 prefix 开头的 id(id 在「id=」之后)。 */
  private static String extractId(String listOutput, String prefix) {
    int i = listOutput.indexOf(prefix);
    if (i < 0) {
      return "";
    }
    int end = listOutput.indexOf('\n', i);
    if (end < 0) {
      end = listOutput.length();
    }
    return listOutput.substring(i, end);
  }

  /** 构造 run 操作的 edit 对象(含 paragraph_index、run_index),供 delete/replace_run_tracked 用。 */
  private static Map<String, Object> runEdit(int paragraphIndex, int runIndex) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("paragraph_index", paragraphIndex);
    m.put("run_index", runIndex);
    return m;
  }

  /** 构造单元格坐标对象(含 table_index、row_index、cell_index),供 mark_cell_* 用。 */
  private static Map<String, Object> cellCoord(int tableIndex, int rowIndex, int cellIndex) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("table_index", tableIndex);
    m.put("row_index", rowIndex);
    m.put("cell_index", cellIndex);
    return m;
  }

  private static void addCellIns(CTTc tc, String id, String author) {
    tc.addNewP();
    CTTcPr tcPr = tc.addNewTcPr();
    CTTrackChange ci = tcPr.addNewCellIns();
    ci.setId(new java.math.BigInteger(id));
    ci.setAuthor(author);
  }

  private static void addCellDel(CTTc tc, String id, String author) {
    tc.addNewP();
    CTTcPr tcPr = tc.addNewTcPr();
    CTTrackChange cd = tcPr.addNewCellDel();
    cd.setId(new java.math.BigInteger(id));
    cd.setAuthor(author);
  }

  /** cellMerge 无 CT 类型,只能 XmlCursor 造裸元素。 */
  private static void addCellMerge(CTTc tc, String id, String author) {
    tc.addNewP();
    CTTcPr tcPr = tc.addNewTcPr();
    XmlCursor cur = tcPr.newCursor();
    try {
      cur.toEndToken();
      cur.beginElement(QName.valueOf("{" + W_NS + "}cellMerge"));
      cur.toPrevSibling();
      cur.insertAttributeWithValue(QName.valueOf("{" + W_NS + "}id"), id);
      cur.insertAttributeWithValue(QName.valueOf("{" + W_NS + "}author"), author);
    } finally {
      cur.dispose();
    }
  }
}
