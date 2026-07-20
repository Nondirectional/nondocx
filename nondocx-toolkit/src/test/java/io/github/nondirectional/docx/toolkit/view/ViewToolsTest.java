package io.github.nondirectional.docx.toolkit.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.toolkit.DocxToolkit;
import io.github.nondirectional.docx.toolkit.ToolTestSupport;
import io.github.nondirectional.docx.toolkit.result.ToolResultParser.Snapshot;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ViewTools} 端到端测试：6 个 {@code view_*} 工具经 {@link DocxToolkit} 门面调用，验证 envelope 格式、 错误处理和 ref
 * 一致性。
 *
 * <p>同时为 P0-03 能力契约测试提供调用点覆盖（{@code viewOutline}/{@code viewText}/... 方法名匹配）。
 */
class ViewToolsTest {

  private Path buildSampleDoc(Path tmp) throws Exception {
    Path file = tmp.resolve("sample.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("标题一").heading(HeadingLevel.H1);
      doc.addParagraph("普通段落");
      Paragraph p = doc.addParagraph();
      p.addRun("加粗").bold().fontSize(14);
      p.addRun("普通");
      doc.save(file);
    }
    return file;
  }

  @Test
  void viewOutlineReturnsEnvelope(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.view.viewOutline(docId, null, null);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    assertThat(snap.code().value()).isEqualTo("ok");
    assertThat(snap.dataText()).contains("entries");
    assertThat(snap.dataText()).contains("标题一");
  }

  @Test
  void viewOutlineRespectsMaxItems(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("big.docx");
    try (Document doc = Docx.create()) {
      for (int i = 0; i < 10; i++) {
        doc.addParagraph("段落" + i);
      }
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.view.viewOutline(docId, 3, null);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    assertThat(snap.dataText()).contains("\"truncated\":true");
  }

  @Test
  void viewTextReturnsEnvelope(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.view.viewText(docId, null, null);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    assertThat(snap.dataText()).contains("entries");
    assertThat(snap.dataText()).contains("普通段落");
  }

  @Test
  void viewAnnotatedWithoutExpandRuns(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.view.viewAnnotated(docId, null, null);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    assertThat(snap.dataText()).contains("paragraphs");
  }

  @Test
  void viewAnnotatedWithExpandRuns(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.view.viewAnnotated(docId, null, true);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    // expandRuns=true 时 runs 非空
    assertThat(snap.dataText()).contains("bold");
    assertThat(snap.dataText()).contains("fontSize");
  }

  @Test
  void viewStatsReturnsEnvelope(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.view.viewStats(docId);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    assertThat(snap.dataText()).contains("paragraphCount");
    assertThat(snap.dataText()).contains("tableCount");
    assertThat(snap.dataText()).contains("sectionCount");
  }

  @Test
  void viewIssuesReturnsEnvelope(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("issues.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("");
      doc.addParagraph("");
      doc.addParagraph("");
      doc.save(file);
    }
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.view.viewIssues(docId, null);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    assertThat(snap.dataText()).contains("passedCount");
    assertThat(snap.dataText()).contains("issues");
  }

  @Test
  void viewElementReturnsEnvelope(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    // 先从 outline 拿 ref
    String outlineResult = tk.view.viewOutline(docId, null, null);
    Snapshot outlineSnap = ToolTestSupport.parse(outlineResult);
    // dataText 是 JSON，提取第一个 ref 字段值
    String dataText = outlineSnap.dataText();
    int refIdx = dataText.indexOf("\"ref\":\"");
    assertThat(refIdx).isGreaterThan(-1);
    int refStart = refIdx + 7;
    int refEnd = dataText.indexOf("\"", refStart);
    String ref = dataText.substring(refStart, refEnd);

    String result = tk.view.viewElement(docId, ref);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    assertThat(snap.dataText()).contains("properties");
  }

  @Test
  void viewElementRejectsInvalidRef(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.view.viewElement(docId, "not-a-valid-ref");
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isFalse();
    // 非法 ref 格式应返回 invalid_ref
    assertThat(snap.code().value()).isIn("invalid_ref", "stale_ref");
  }

  @Test
  void allViewToolsRejectClosedDoc() {
    DocxToolkit tk = new DocxToolkit();
    // 不 open_docx，直接调——应返回 DOCUMENT_CLOSED
    assertThat(ToolTestSupport.parse(tk.view.viewOutline("doc-999", null, null)).success())
        .isFalse();
    assertThat(ToolTestSupport.parse(tk.view.viewText("doc-999", null, null)).success()).isFalse();
    assertThat(ToolTestSupport.parse(tk.view.viewAnnotated("doc-999", null, null)).success())
        .isFalse();
    assertThat(ToolTestSupport.parse(tk.view.viewStats("doc-999")).success()).isFalse();
    assertThat(ToolTestSupport.parse(tk.view.viewIssues("doc-999", null)).success()).isFalse();
    assertThat(ToolTestSupport.parse(tk.view.viewElement("doc-999", "ref")).success()).isFalse();
  }

  @Test
  void getDocumentOverviewMigratedToStatsView(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(file.toString()));

    String result = tk.session.getDocumentOverview(docId);
    Snapshot snap = ToolTestSupport.parse(result);
    assertThat(snap.success()).isTrue();
    // data 升级为 StatsView（含 paragraphCount，不再是旧的中文 key Map）
    assertThat(snap.dataText()).contains("paragraphCount");
    assertThat(snap.dataText()).contains("tableCount");
    assertThat(snap.dataText()).contains("sectionCount");
  }
}
