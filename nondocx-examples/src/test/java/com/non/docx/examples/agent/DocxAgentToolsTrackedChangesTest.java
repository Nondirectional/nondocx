package com.non.docx.examples.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;
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
    String acceptResult = tools.acceptCellChange(docId, cellInsId);
    assertThat(acceptResult).startsWith("已应用");
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

    // cellMerge 的 accept 应返回中文错误串(而非抛异常)
    String result = tools.acceptCellChange(docId, id);
    assertThat(result).startsWith("错误");
    assertThat(result).contains("cellMerge");
    // 文档未变,cellMerge 仍在
    assertThat(tools.listTrackedChanges(docId)).contains("共 1 条修订");
  }

  @Test
  void shouldReturnErrorForUnknownDocId() {
    DocxAgentTools tools = new DocxAgentTools();
    assertThat(tools.getTrackedChangesEnabled("doc-999")).contains("不存在");
    assertThat(tools.listTrackedChanges("doc-999")).contains("不存在");
    assertThat(tools.acceptCellChange("doc-999", "x")).contains("不存在");
  }

  // ---------- I 组:创作工具冒烟 ----------

  @Test
  void shouldInsertTrackedRunWithStyle(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("ins.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("段首");
      doc.save(file);
    }
    DocxAgentTools tools = new DocxAgentTools();
    String docId = tools.openDocx(file.toAbsolutePath().toString());
    String result = tools.insertTrackedRun(docId, 0, "甲", "插入文字", true, false, "FF0000");
    assertThat(result).contains("已").contains("插入文字");
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
    String result = tools.markCellInserted(docId, 0, 0, 0, "甲");
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
    assertThat(tools.acceptPropertyChange(docId, id)).startsWith("错误");
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
