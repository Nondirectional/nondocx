package com.non.docx.toolkit.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.view.dto.AnnotatedRun;
import com.non.docx.toolkit.view.dto.AnnotatedView;
import com.non.docx.toolkit.view.dto.ElementView;
import com.non.docx.toolkit.view.dto.IssuesView;
import com.non.docx.toolkit.view.dto.OutlineEntry;
import com.non.docx.toolkit.view.dto.OutlineView;
import com.non.docx.toolkit.view.dto.StatsView;
import com.non.docx.toolkit.view.dto.TextView;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DocumentViewService} 单元测试：6 视图正确性 + 截断 + 一致性。
 *
 * <p>直接测服务层（不经 ViewTools 的 String 边界），验证 DTO 内容正确。ViewTools 的 envelope 格式由 {@link ViewToolsTest}
 * 覆盖。
 */
class DocumentViewServiceTest {

  private DocumentViewService newService() {
    DocxToolkit tk = new DocxToolkit();
    return tk.view.viewService();
  }

  private Path buildSampleDoc(Path tmp) throws Exception {
    Path file = tmp.resolve("sample.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("标题一").heading(HeadingLevel.H1);
      doc.addParagraph("普通段落A");
      doc.addParagraph("子标题").heading(HeadingLevel.H2);
      // 加一个表格：addTable() 无参创建空表，再 addRow/addCell 填充
      Table t = doc.addTable();
      com.non.docx.core.api.table.Row r0 = t.addRow();
      r0.addCell().addParagraph().addRun("单元格00");
      r0.addCell().addParagraph().addRun("单元格01");
      com.non.docx.core.api.table.Row r1 = t.addRow();
      r1.addCell();
      r1.addCell();
      doc.addParagraph("普通段落B");
      doc.save(file);
    }
    return file;
  }

  @Test
  void outlineReturnsParagraphsAndTableInBodyOrder(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      OutlineView view = svc.outline(doc, "doc-1", 1L, ViewQuery.defaults());
      // 4 段落 + 1 表格 = 5 项
      assertThat(view.entries()).hasSize(5);
      // 第一项是 H1 标题
      OutlineEntry first = view.entries().get(0);
      assertThat(first.kind()).isEqualTo("paragraph");
      assertThat(first.headingLevel()).isEqualTo("1");
      assertThat(first.ref()).isNotBlank();
      // 表格在 body 顺序里（bodyIndex 3，排在子标题之后、普通段落B之前）
      OutlineEntry tableEntry =
          view.entries().stream().filter(e -> "table".equals(e.kind())).findFirst().orElseThrow();
      assertThat(tableEntry.rowCount()).isEqualTo(2);
      assertThat(tableEntry.columnCount()).isEqualTo(2);
      // section 数
      assertThat(view.sectionCount()).isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  void outlineTruncatesByMaxItems(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("big.docx");
    try (Document doc = Docx.create()) {
      for (int i = 0; i < 10; i++) {
        doc.addParagraph("段落" + i);
      }
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      ViewQuery query = ViewQuery.defaults().withMaxItems(3);
      OutlineView view = svc.outline(doc, "doc-1", 1L, query);
      assertThat(view.entries()).hasSize(3);
      assertThat(view.meta().truncated()).isTrue();
      assertThat(view.meta().totalCount()).isEqualTo(10);
    }
  }

  @Test
  void textReturnsEntriesWithRef(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      TextView view = svc.text(doc, "doc-1", 1L, ViewQuery.defaults());
      assertThat(view.entries()).hasSize(5);
      // 每项都有 ref
      view.entries().forEach(e -> assertThat(e.ref()).isNotBlank());
      // 第一项文本含"标题一"
      assertThat(view.entries().get(0).text()).contains("标题一");
    }
  }

  @Test
  void annotatedWithoutExpandRunsHasEmptyRuns(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("annot.docx");
    try (Document doc = Docx.create()) {
      Paragraph p = doc.addParagraph();
      p.addRun("加粗").bold();
      p.addRun("普通");
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      AnnotatedView view = svc.annotated(doc, "doc-1", 1L, ViewQuery.defaults());
      assertThat(view.paragraphs()).hasSize(1);
      assertThat(view.paragraphs().get(0).runs()).isEmpty();
    }
  }

  @Test
  void annotatedWithExpandRunsReturnsRunDirectFormat(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("annot.docx");
    try (Document doc = Docx.create()) {
      Paragraph p = doc.addParagraph();
      p.addRun("加粗").bold().fontSize(14).font("宋体");
      p.addRun("斜体").italic();
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      ViewQuery query = ViewQuery.defaults().withExpandRuns(true);
      AnnotatedView view = svc.annotated(doc, "doc-1", 1L, query);
      assertThat(view.paragraphs()).hasSize(1);
      assertThat(view.paragraphs().get(0).runs()).hasSize(2);
      AnnotatedRun run0 = view.paragraphs().get(0).runs().get(0);
      assertThat(run0.text()).contains("加粗");
      assertThat(run0.bold()).isTrue();
      assertThat(run0.fontSize()).isEqualTo(14);
      assertThat(run0.font()).isEqualTo("宋体");
      assertThat(run0.ref()).isNotBlank();
      AnnotatedRun run1 = view.paragraphs().get(0).runs().get(1);
      assertThat(run1.italic()).isTrue();
    }
  }

  @Test
  void statsMatchesDocumentStructure(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      StatsView stats = svc.stats(doc, "doc-1", 1L);
      // 4 段落 + 1 表格
      assertThat(stats.paragraphCount()).isEqualTo(4);
      assertThat(stats.tableCount()).isEqualTo(1);
      // body 元素数 = 4 段落 + 1 表格 = 5
      assertThat(stats.bodyElementCount()).isEqualTo(5);
      assertThat(stats.sectionCount()).isGreaterThanOrEqualTo(1);
      // 旧 get_document_overview 的 4 个 int 仍在
      assertThat(stats.meta().snapshotVersion()).isEqualTo(2);
    }
  }

  @Test
  void statsCollectsFontsAndSizes(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("fonts.docx");
    try (Document doc = Docx.create()) {
      Paragraph p = doc.addParagraph();
      p.addRun("宋体文本").font("宋体").fontSize(12);
      p.addRun("黑体文本").font("黑体").fontSize(14);
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      StatsView stats = svc.stats(doc, "doc-1", 1L);
      assertThat(stats.fonts()).extracting("fontName").contains("宋体", "黑体");
      assertThat(stats.fontSizes()).contains(12, 14);
    }
  }

  @Test
  void issuesReturnsOnlyFailedChecks(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("issues.docx");
    try (Document doc = Docx.create()) {
      // 制造一个问题：连续空段（blank-pages 检查）
      doc.addParagraph("");
      doc.addParagraph("");
      doc.addParagraph("");
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      IssuesView view = svc.issues(doc, "doc-1", 1L, null);
      // issues 列表只含 passed=false 的项
      assertThat(view.issues()).allSatisfy(i -> assertThat(i.passed()).isFalse());
      // 至少有一个未通过项（blank-pages 或其他）
      assertThat(view.passedCount() + view.warningCount() + view.errorCount()).isEqualTo(10);
    }
  }

  @Test
  void issuesFiltersBySeverity(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("issues.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("");
      doc.addParagraph("");
      doc.addParagraph("");
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      IssuesView all = svc.issues(doc, "doc-1", 1L, null);
      IssuesView warningsOnly = svc.issues(doc, "doc-1", 1L, "warning");
      // warning 过滤后只含 warning 项
      assertThat(warningsOnly.issues())
          .allSatisfy(i -> assertThat(i.severity()).isEqualTo("warning"));
      assertThat(warningsOnly.issues().size()).isLessThanOrEqualTo(all.issues().size());
    }
  }

  @Test
  void elementReturnsParagraphDetails(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      // 先从 outline 拿一个 ref
      OutlineView outline = svc.outline(doc, "doc-1", 1L, ViewQuery.defaults());
      String ref = outline.entries().get(0).ref();
      // 用 ref 取元素详情
      ElementView view = svc.element(doc, "doc-1", 1L, ref);
      assertThat(view.kind()).isEqualTo("paragraph");
      assertThat(view.ref()).isEqualTo(ref);
      assertThat(view.properties()).containsKey("text");
    }
  }

  @Test
  void elementReturnsRunDetails(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("run.docx");
    try (Document doc = Docx.create()) {
      Paragraph p = doc.addParagraph();
      p.addRun("加粗文本").bold();
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      // 先从 annotated(expandRuns=true) 拿 run ref
      AnnotatedView ann =
          svc.annotated(doc, "doc-1", 1L, ViewQuery.defaults().withExpandRuns(true));
      String runRef = ann.paragraphs().get(0).runs().get(0).ref();
      // 用 ref 取 run 详情
      ElementView view = svc.element(doc, "doc-1", 1L, runRef);
      assertThat(view.kind()).isEqualTo("run");
      assertThat(view.properties()).containsEntry("bold", true);
      assertThat(view.properties()).containsKey("text");
    }
  }

  @Test
  void allViewsShareSameGeneration(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      long gen = 42L;
      OutlineView outline = svc.outline(doc, "doc-1", gen, ViewQuery.defaults());
      TextView text = svc.text(doc, "doc-1", gen, ViewQuery.defaults());
      StatsView stats = svc.stats(doc, "doc-1", gen);
      AnnotatedView ann = svc.annotated(doc, "doc-1", gen, ViewQuery.defaults());
      IssuesView issues = svc.issues(doc, "doc-1", gen, null);

      assertThat(outline.meta().sessionGeneration()).isEqualTo(gen);
      assertThat(text.meta().sessionGeneration()).isEqualTo(gen);
      assertThat(stats.meta().sessionGeneration()).isEqualTo(gen);
      assertThat(ann.meta().sessionGeneration()).isEqualTo(gen);
      assertThat(issues.meta().sessionGeneration()).isEqualTo(gen);
    }
  }
}
