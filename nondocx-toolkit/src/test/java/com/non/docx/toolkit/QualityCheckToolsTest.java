package com.non.docx.toolkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Cell;
import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;

/**
 * 验证 {@link QualityCheckTools} 的 10 项检查：合规文档全 ✅、各违规文档对应 ❌/⚠️、checks 过滤、docNotFound。
 *
 * <p>经 {@link DocxToolkit} 门面驱动，验证第 7 个工具类注入正确、共享会话状态。
 */
class QualityCheckToolsTest {

  /** 经门面跑全量检查，返回报告字符串。 */
  private static String runAll(DocxToolkit tk, String docId) {
    return tk.qualityCheck.checkQuality(docId, null);
  }

  /** save → open → 返回 docId。 */
  private static String saveAndOpen(DocxToolkit tk, Document doc, Path tmp, String name)
      throws Exception {
    Path file = tmp.resolve(name);
    doc.save(file);
    return com.non.docx.toolkit.ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));
  }

  // ---------- 合规文档全 ✅ ----------

  @Test
  void cleanDocumentPassesAllChecks(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    doc.addParagraph().addRun("First paragraph.").font("Arial");
    doc.addParagraph().addRun("Second paragraph.").font("Arial");

    String docId = saveAndOpen(tk, doc, tmp, "clean.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("通过 10/10 | ❌ 0 errors | ⚠️ 0 warnings");
    // 各检查项行不应有 ❌/⚠️（全 ✅）—— 汇总行的 ❌/⚠️ 是计数标签，不算违规
    for (String line : report.split("\n")) {
      if (line.startsWith("  ✅") || line.startsWith("  ⚠️") || line.startsWith("  ❌")) {
        assertThat(line).startsWith("  ✅");
      }
    }
  }

  // ---------- blank-pages ----------

  @Test
  void detectsConsecutiveBlankParagraphs(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    doc.addParagraph("content");
    doc.addParagraph(""); // 空
    doc.addParagraph(""); // 空
    doc.addParagraph(""); // 空（连续 3）
    doc.addParagraph("more");

    String docId = saveAndOpen(tk, doc, tmp, "blank.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("⚠️ [blank-pages]");
    assertThat(report).contains("连续 ≥3 空段");
  }

  // ---------- line-spacing ----------

  @Test
  void detectsInconsistentLineSpacing(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    Paragraph p1 = doc.addParagraph();
    p1.addRun("p1");
    p1.lineSpacing(1.0);
    Paragraph p2 = doc.addParagraph();
    p2.addRun("p2");
    p2.lineSpacing(1.0);
    Paragraph p3 = doc.addParagraph();
    p3.addRun("p3");
    p3.lineSpacing(1.5);

    String docId = saveAndOpen(tk, doc, tmp, "spacing.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("⚠️ [line-spacing]");
    assertThat(report).contains("2 种行距");
  }

  // ---------- table-pagination ----------

  @Test
  void detectsMissingHeaderRowAndCantSplit(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    Table t = doc.addTable();
    Row header = t.addRow();
    header.cell("A").cell("B"); // 未设 headerRow
    Row data = t.addRow();
    data.cell("1").cell("2"); // 未设 cantSplit

    String docId = saveAndOpen(tk, doc, tmp, "pagination.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("⚠️ [table-pagination]");
    assertThat(report).contains("headerRow").contains("cantSplit");
  }

  @Test
  void tablePaginationPassesWhenProperlyConfigured(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    Table t = doc.addTable();
    Row header = t.addRow();
    header.cell("A").cell("B");
    header.headerRow(true).cantSplit(true);
    Row data = t.addRow();
    data.cell("1").cell("2");
    data.cantSplit(true);

    String docId = saveAndOpen(tk, doc, tmp, "pagination-ok.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("✅ [table-pagination]");
  }

  // ---------- font-fallback ----------

  @Test
  void detectsRiskyFont(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    doc.addParagraph().addRun("text").font("方正姚体"); // 非白名单

    String docId = saveAndOpen(tk, doc, tmp, "font.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("⚠️ [font-fallback]");
    assertThat(report).contains("方正姚体");
  }

  // ---------- cjk-indent ----------

  @Test
  void detectsMissingCjkIndent(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    Paragraph p = doc.addParagraph();
    p.addRun("这是一段没有首行缩进的中文正文。"); // CJK 但 indentationFirstLine=0

    String docId = saveAndOpen(tk, doc, tmp, "cjk.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("⚠️ [cjk-indent]");
    assertThat(report).contains("无首行缩进");
  }

  // ---------- heading-levels ----------

  @Test
  void detectsHeadingSkip(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    Paragraph t1 = doc.addParagraph();
    t1.addRun("Title");
    t1.heading(HeadingLevel.H1);
    Paragraph t2 = doc.addParagraph();
    t2.addRun("Sub");
    t2.heading(HeadingLevel.H3); // H1 → H3 跳级

    String docId = saveAndOpen(tk, doc, tmp, "heading.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("⚠️ [heading-levels]");
    assertThat(report).contains("H1").contains("H3");
  }

  // ---------- shading-solid ----------

  @Test
  void detectsSolidShadingViaRaw(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    Table t = doc.addTable();
    Row r = t.addRow();
    Cell cell = r.addCell();
    cell.text("x");
    // 用 raw 写 SOLID（公开 API 不暴露 SOLID，检查器要走 raw 兜底）
    CTTc tc = cell.raw().getCTTc();
    CTShd shd = tc.isSetTcPr() ? tc.getTcPr().getShd() : tc.addNewTcPr().addNewShd();
    shd.setVal(STShd.SOLID);
    shd.setFill("FF0000");

    String docId = saveAndOpen(tk, doc, tmp, "solid.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("❌ [shading-solid]");
    assertThat(report).contains("#shading-solid");
  }

  @Test
  void clearShadingPassesCheck(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    Table t = doc.addTable();
    t.addRow().addCell().text("x").shading("F1F5F9"); // CLEAR，安全

    String docId = saveAndOpen(tk, doc, tmp, "clear.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("✅ [shading-solid]");
  }

  // ---------- cleanliness ----------

  @Test
  void detectsPlaceholderAndMarkdown(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    doc.addParagraph().addRun("TODO: 待填写这里的内容");
    doc.addParagraph().addRun("看这段 **加粗** 文本");

    String docId = saveAndOpen(tk, doc, tmp, "dirty.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("⚠️ [cleanliness]");
  }

  // ---------- toc ----------

  @Test
  void tocWithNoHeadingsWarnsEmpty(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    // 无标题也无 TOC → toc 检查通过（"无 TOC 域（文档也无标题，可接受）"）
    doc.addParagraph().addRun("plain text");

    String docId = saveAndOpen(tk, doc, tmp, "notoc.docx");
    String report = runAll(tk, docId);

    assertThat(report).contains("✅ [toc]");
  }

  // ---------- checks 过滤 ----------

  @Test
  void checksFilterRunsOnlySelected(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    doc.addParagraph("a");
    doc.addParagraph(""); // 空
    doc.addParagraph(""); // 空
    doc.addParagraph(""); // 空（连续 3 → blank-pages 报警）
    doc.addParagraph().addRun("TODO"); // cleanliness 报警

    String docId = saveAndOpen(tk, doc, tmp, "filter.docx");
    // 只跑 blank-pages
    String report = tk.qualityCheck.checkQuality(docId, List.of("blank-pages"));

    assertThat(report).contains("[blank-pages]");
    assertThat(report).contains("通过 0/1"); // 只 1 项检查，且该项报警未通过
    assertThat(report).doesNotContain("[cleanliness]");
  }

  @Test
  void checksFilterReportsUnknownNames(@TempDir Path tmp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Document doc = Docx.create();
    doc.addParagraph("x");

    String docId = saveAndOpen(tk, doc, tmp, "unknown.docx");
    String report =
        tk.qualityCheck.checkQuality(docId, List.of("blank-pages", "nonexistent-check"));

    // 未知项保留为可见提示，避免 Agent 以为该检查真的执行过。
    assertThat(report).contains("✅ [blank-pages]");
    assertThat(report).contains("✅ [nonexistent-check] 未知检查项，已跳过");
    assertThat(report).contains("通过 2/2");
  }

  // ---------- docNotFound ----------

  @Test
  void docNotFoundReturnsErrorString() {
    DocxToolkit tk = new DocxToolkit();
    String report = tk.qualityCheck.checkQuality("doc-999", null);
    assertThat(report).contains("文档句柄 doc-999 不存在");
  }

  // ---------- 门面注入验证 ----------

  @Test
  void facadeInjectsQualityCheckAsSeventhTool() {
    DocxToolkit tk = new DocxToolkit();
    assertThat(tk.qualityCheck).isNotNull();
    // scanAll 应注册全部 7 个工具类（间接验证：无异常 + qualityCheck 可用）
    com.non.chain.tool.ToolRegistry reg = tk.scanAll(new com.non.chain.tool.ToolRegistry());
    assertThat(reg).isNotNull();
  }
}
