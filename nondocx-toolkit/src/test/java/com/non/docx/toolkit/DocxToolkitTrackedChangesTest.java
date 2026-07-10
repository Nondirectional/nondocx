package com.non.docx.toolkit;

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
 * 冒烟测试 {@link DocxToolkit} 的 tracked changes 工具(H 组读取/处理 + I 组创作), 不发 LLM、直接调工具方法验证返回串。
 *
 * <p>不重复测 core 的 accept/reject 正确性(那由 core 的 12 个单元测试背书);这里只验证工具层的包装:返回串格式、 docId 不存在的错误串、list 拿到
 * stable id 后 accept/reject 的衔接、cellMerge 被诚实拒绝。
 *
 * <p>本测试跨 {@link TrackedChangeQueryTools}(读/处理)与 {@link TrackedChangeAuthoringTools}(创作)两个工具类, 经
 * {@link DocxToolkit} 门面驱动——验证拆分后两组共享同一份会话状态(创作出的修订能被读取组按 docId 读回)。
 */
class DocxToolkitTrackedChangesTest {

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

    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    assertThat(docId).startsWith("doc-");

    // enabled:文档未显式开开关 → 未开启
    assertThat(tk.trackedChangeQuery.getTrackedChangesEnabled(docId)).contains("未开启");

    // list:三条 cell 修订,每条带 stable id
    String list = tk.trackedChangeQuery.listTrackedChanges(docId);
    assertThat(list).contains("共 3 条修订");
    assertThat(list).contains("CELL_INS").contains("CELL_DEL").contains("CELL_MERGE");
    // 抽出 cellIns 的 id(形如 cell_ins:...:1)
    String cellInsId = extractId(list, "cell_ins:");
    assertThat(cellInsId).isNotBlank();

    // accept cellIns:工具返回"已应用",再 list 只剩 2 条
    String acceptResult =
        tk.trackedChangeQuery.applyTrackedChanges(docId, "ACCEPT", "CELL", List.of(cellInsId));
    assertThat(acceptResult).contains("已应用");
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 2 条修订");
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
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    String id = extractId(tk.trackedChangeQuery.listTrackedChanges(docId), "cell_merge:");

    // cellMerge 的 accept 应返回含错误的明细串(而非抛异常),整批失败 1 条
    String result = tk.trackedChangeQuery.applyTrackedChanges(docId, "ACCEPT", "CELL", List.of(id));
    assertThat(result).contains("错误");
    assertThat(result).contains("失败 1 条");
    // 文档未变,cellMerge 仍在
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("共 1 条修订");
  }

  @Test
  void shouldReturnErrorForUnknownDocId() {
    DocxToolkit tk = new DocxToolkit();
    assertThat(tk.trackedChangeQuery.getTrackedChangesEnabled("doc-999")).contains("不存在");
    assertThat(tk.trackedChangeQuery.listTrackedChanges("doc-999")).contains("不存在");
    assertThat(tk.trackedChangeQuery.applyTrackedChanges("doc-999", "ACCEPT", "CELL", List.of("x")))
        .contains("不存在");
  }

  /** set_tracked_changes_enabled:开/关往返 + 读回一致 + docId 不存在返回错误串。 */
  @Test
  void shouldToggleTrackedChangesSwitch(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("switch.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("空文档");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());

    // 初始未开启
    assertThat(tk.trackedChangeQuery.getTrackedChangesEnabled(docId)).contains("未开启");
    // 开启:返回串标「已开启」,读回一致
    assertThat(tk.trackedChangeQuery.setTrackedChangesEnabled(docId, true)).contains("已开启");
    assertThat(tk.trackedChangeQuery.getTrackedChangesEnabled(docId)).contains("已开启");
    // 关闭:返回串标「已关闭」,读回一致
    assertThat(tk.trackedChangeQuery.setTrackedChangesEnabled(docId, false)).contains("已关闭");
    assertThat(tk.trackedChangeQuery.getTrackedChangesEnabled(docId)).contains("未开启");

    // docId 不存在返回错误串
    assertThat(tk.trackedChangeQuery.setTrackedChangesEnabled("doc-999", true)).contains("不存在");
  }

  // ---------- I 组:创作工具冒烟 ----------

  @Test
  void shouldDeleteRunTracked(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("del.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("要删的文字");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    String result = tk.trackedChangeAuthoring.deleteRunTracked(docId, "甲", List.of(runEdit(0, 0)));
    assertThat(result).contains("tracked del").contains("要删的文字");
    // 读回应有一条 DEL
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("DEL");
  }

  @Test
  void shouldReplaceRunTracked(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("replace.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("旧文字");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    Map<String, Object> edit = runEdit(0, 0);
    edit.put("new_text", "新文字");
    String result = tk.trackedChangeAuthoring.replaceRunTracked(docId, "甲", List.of(edit));
    assertThat(result).contains("旧文字").contains("新文字").contains("del+ins");
    // 读回:一条 DEL(旧)+ 一条 INS(新)
    String list = tk.trackedChangeQuery.listTrackedChanges(docId);
    assertThat(list).contains("DEL").contains("INS");
  }

  @Test
  void shouldInsertTrackedRunWithStyle(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("ins.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("段首");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    Map<String, Object> edit = new LinkedHashMap<>();
    edit.put("paragraph_index", 0);
    edit.put("text", "插入文字");
    edit.put("bold", true);
    edit.put("italic", false);
    edit.put("color", "FF0000");
    String result = tk.trackedChangeAuthoring.insertTrackedRun(docId, "甲", List.of(edit));
    assertThat(result).contains("插入文字").contains("✓");
    // 读回应有一条 INS
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("INS");
  }

  @Test
  void shouldMarkAndAcceptCellInserted(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().addRow().addCell().addParagraph().addRun("内容");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    String result =
        tk.trackedChangeAuthoring.markTrackedCells(
            docId, "INSERTED", "甲", List.of(cellCoord(0, 0, 0)));
    assertThat(result).contains("cellIns");
    // 读回应有一条 CELL_INS
    assertThat(tk.trackedChangeQuery.listTrackedChanges(docId)).contains("CELL_INS");
  }

  @Test
  void shouldMoveRunTracked(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("被移走的文字");
      doc.addParagraph("目标段");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    String result = tk.trackedChangeAuthoring.moveRunTracked(docId, 0, 0, 1, "甲");
    assertThat(result).contains("moveFrom");
    // 读回应有配对的 MOVE_FROM + MOVE_TO
    String list = tk.trackedChangeQuery.listTrackedChanges(docId);
    assertThat(list).contains("MOVE_FROM").contains("MOVE_TO");
  }

  @Test
  void shouldRejectWrongFamily(@TempDir Path tmp) throws Exception {
    // 文本类 ins 用 target=PROPERTY 应返回错误串(family 不符)
    Path file = tmp.resolve("text.docx");
    try (Document doc = Docx.create()) {
      var p = doc.raw().getDocument().getBody().addNewP();
      var ins = p.addNewIns();
      ins.setId(java.math.BigInteger.ONE);
      ins.setAuthor("甲");
      ins.addNewR().addNewT().setStringValue("X");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    String id = extractId(tk.trackedChangeQuery.listTrackedChanges(docId), "ins:");
    // family 不符 → 该条记错误不中断
    assertThat(tk.trackedChangeQuery.applyTrackedChanges(docId, "ACCEPT", "PROPERTY", List.of(id)))
        .contains("错误")
        .contains("失败 1 条");
  }

  @Test
  void shouldGetAndAcceptRevisionByRefThenReportRemoved(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("revision-ref.docx");
    try (Document doc = Docx.create()) {
      var p = doc.raw().getDocument().getBody().addNewP();
      var ins = p.addNewIns();
      ins.setId(java.math.BigInteger.ONE);
      ins.setAuthor("甲");
      ins.addNewR().addNewT().setStringValue("新增");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = tk.session.openDocx(file.toAbsolutePath().toString());
    String list = tk.trackedChangeQuery.listTrackedChanges(docId);
    String ref = extractRef(list);

    assertThat(ref).startsWith("doc:").contains("/revision:session:");
    assertThat(tk.trackedChangeQuery.getTrackedChange(docId, ref))
        .contains("text=\"新增\"")
        .contains("ref=" + ref);
    assertThat(
            tk.trackedChangeQuery.applyTrackedChanges(
                docId, "ACCEPT", "TEXT_OR_MOVE", List.of(ref)))
        .contains("已应用")
        .contains("ref=" + ref)
        .contains("成功 1 条,失败 0 条");
    assertThat(tk.trackedChangeQuery.getTrackedChange(docId, ref)).contains("错误[element_removed]");
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

  private static String extractRef(String listOutput) {
    int start = listOutput.indexOf("ref=");
    if (start < 0) {
      return "";
    }
    start += "ref=".length();
    int end = listOutput.indexOf(", id=", start);
    return end < 0 ? listOutput.substring(start) : listOutput.substring(start, end);
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
