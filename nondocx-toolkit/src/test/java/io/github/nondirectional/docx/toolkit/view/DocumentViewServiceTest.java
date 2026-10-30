package io.github.nondirectional.docx.toolkit.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.toolkit.DocxToolkit;
import io.github.nondirectional.docx.toolkit.view.dto.AnnotatedRun;
import io.github.nondirectional.docx.toolkit.view.dto.AnnotatedView;
import io.github.nondirectional.docx.toolkit.view.dto.CommentEntry;
import io.github.nondirectional.docx.toolkit.view.dto.CommentOverview;
import io.github.nondirectional.docx.toolkit.view.dto.ElementView;
import io.github.nondirectional.docx.toolkit.view.dto.IssuesView;
import io.github.nondirectional.docx.toolkit.view.dto.OutlineEntry;
import io.github.nondirectional.docx.toolkit.view.dto.OutlineView;
import io.github.nondirectional.docx.toolkit.view.dto.RevisionEntry;
import io.github.nondirectional.docx.toolkit.view.dto.RevisionOverview;
import io.github.nondirectional.docx.toolkit.view.dto.StatsView;
import io.github.nondirectional.docx.toolkit.view.dto.TextView;
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
      io.github.nondirectional.docx.core.api.table.Row r0 = t.addRow();
      r0.addCell().addParagraph().addRun("单元格00");
      r0.addCell().addParagraph().addRun("单元格01");
      io.github.nondirectional.docx.core.api.table.Row r1 = t.addRow();
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
      // font() 四槽同写后 eastAsia 槽与西文槽一致
      assertThat(run0.fontEastAsia()).isEqualTo("宋体");
      assertThat(run0.ref()).isNotBlank();
      AnnotatedRun run1 = view.paragraphs().get(0).runs().get(1);
      assertThat(run1.italic()).isTrue();
    }
  }

  /**
   * 槽分离排版的可观测性：编译器写「西文 TNR/Arial + 中文宋体/黑体」双槽后，annotated 与 view_element 都必须能读到 eastAsia
   * 槽——只读复审靠它核对「设为宋体/黑体」类要求， 只见 ascii 槽会把已正确的中文字体误判成「未设置」（heading-font 曾因此空转 24 次）。
   */
  @Test
  void annotatedAndElementExposeSplitFontSlots(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("split-font.docx");
    try (Document doc = Docx.create()) {
      Paragraph p = doc.addParagraph();
      Run heading = p.addRun("标题");
      var ctr = heading.raw().getCTR();
      var rpr = ctr.isSetRPr() ? ctr.getRPr() : ctr.addNewRPr();
      var fonts = rpr.sizeOfRFontsArray() > 0 ? rpr.getRFontsArray(0) : rpr.addNewRFonts();
      fonts.setAscii("Arial");
      fonts.setEastAsia("黑体");
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      AnnotatedView ann =
          svc.annotated(doc, "doc-1", 1L, ViewQuery.defaults().withExpandRuns(true));
      AnnotatedRun run = ann.paragraphs().get(0).runs().get(0);
      assertThat(run.font()).isEqualTo("Arial");
      assertThat(run.fontEastAsia()).isEqualTo("黑体");

      ElementView element = svc.element(doc, "doc-1", 1L, run.ref());
      assertThat(element.properties()).containsEntry("font", "Arial");
      assertThat(element.properties()).containsEntry("fontEastAsia", "黑体");
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
      // alignment 恒出现（未设置=left）——只读复审靠它核对居中类要求，属性缺失会被误读成「未设置」
      assertThat(view.properties()).containsEntry("alignment", "left");
    }
  }

  /** 居中段的元素详情暴露 alignment=center（命名与 {align=…} 写侧记号一致）。 */
  @Test
  void elementExposesAlignmentForReview(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("aligned.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("普通段");
      io.github.nondirectional.docx.core.api.text.Paragraph centered =
          doc.addParagraph("居中标题")
              .heading(io.github.nondirectional.docx.core.api.style.HeadingLevel.H1);
      centered.alignment(io.github.nondirectional.docx.core.api.style.Alignment.CENTER);
      doc.addParagraph("两端对齐")
          .alignment(io.github.nondirectional.docx.core.api.style.Alignment.JUSTIFY);
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      OutlineView outline = svc.outline(doc, "doc-1", 1L, ViewQuery.defaults());
      // 段落 ref 在 outline 里按正文顺序出现
      assertThat(outline.entries()).hasSize(3);
      ElementView plain = svc.element(doc, "doc-1", 1L, outline.entries().get(0).ref());
      assertThat(plain.properties()).containsEntry("alignment", "left");
      ElementView centered = svc.element(doc, "doc-1", 1L, outline.entries().get(1).ref());
      assertThat(centered.properties()).containsEntry("alignment", "center");
      assertThat(centered.properties()).containsEntry("headingLevel", 1);
      ElementView justified = svc.element(doc, "doc-1", 1L, outline.entries().get(2).ref());
      // JUSTIFY 用写侧记号名 both，让读者能与 {align=both} 对上
      assertThat(justified.properties()).containsEntry("alignment", "both");
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

  /** 表格详情含 cellTexts 矩阵——只读复审核对「某格改成了什么」的依据（只有行列数时无法确认）。 */
  @Test
  void elementTableShowsCellTextsMatrix(@TempDir Path tmp) throws Exception {
    Path file = buildSampleDoc(tmp);
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      OutlineView outline = svc.outline(doc, "doc-1", 1L, ViewQuery.defaults());
      String tableRef =
          outline.entries().stream()
              .filter(e -> "table".equals(e.kind()))
              .map(OutlineEntry::ref)
              .findFirst()
              .orElseThrow(() -> new AssertionError("outline 应含表格条目"));
      ElementView view = svc.element(doc, "doc-1", 1L, tableRef);
      assertThat(view.kind()).isEqualTo("table");
      assertThat(view.properties()).containsEntry("rowCount", 2);
      @SuppressWarnings("unchecked")
      java.util.List<java.util.List<String>> cellTexts =
          (java.util.List<java.util.List<String>>) view.properties().get("cellTexts");
      assertThat(cellTexts).hasSize(2);
      assertThat(cellTexts.get(0)).containsExactly("单元格00", "单元格01");
      assertThat(cellTexts.get(1)).containsExactly("", "");
    }
  }

  /** stats 暴露页脚文本预览：无页脚为 null；有页脚给截断文本（复审核对「第 1 页」类内容的依据）。 */
  @Test
  void statsShowsFooterTextPreview(@TempDir Path tmp) throws Exception {
    DocumentViewService svc = newService();
    Path plain = tmp.resolve("plain.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(plain);
    }
    try (Document doc = Docx.open(plain)) {
      assertThat(svc.stats(doc, "doc-1", 1L).footerText()).isNull();
    }

    Path withFooter = tmp.resolve("footer.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.ensureFooter().addParagraph().addRun("第 1 页");
      doc.save(withFooter);
    }
    try (Document doc = Docx.open(withFooter)) {
      StatsView stats = svc.stats(doc, "doc-1", 1L);
      assertThat(stats.hasFooter()).isTrue();
      assertThat(stats.footerText()).contains("第 1 页");
    }
  }

  /** outline 修订概览带作者计数与最近明细（type/author/片段）——复审核对「作者填了谁、改了什么」。 */
  @Test
  void outlineRevisionOverviewCarriesAuthorsAndRecentEntries(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("tracked.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("合同金额为一百万元。");
      doc.paragraph(0).deleteTrackedRange("审查员", 5, 9);
      doc.paragraph(0).addInsertion("审查员", "两百万元");
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      OutlineView outline = svc.outline(doc, "doc-1", 1L, ViewQuery.defaults());
      RevisionOverview revision = outline.revision();
      assertThat(revision.totalCount()).isEqualTo(2);
      assertThat(revision.countByAuthor()).containsEntry("审查员", 2);
      assertThat(revision.recent()).hasSize(2);
      assertThat(revision.recent()).allMatch(e -> "审查员".equals(e.author()));
      assertThat(revision.recent())
          .extracting(RevisionEntry::snippet)
          .containsExactlyInAnyOrder("一百万元", "两百万元");
      // recent 的 ref 是完整 canonical，可直通 view_element 查修订详情
      ElementView detail = svc.element(doc, "doc-1", 1L, revision.recent().get(0).ref());
      assertThat(detail.kind()).isEqualTo("revision");
      assertThat(detail.properties()).containsEntry("author", "审查员");
      assertThat(detail.properties()).containsKey("type");
      assertThat(detail.properties()).containsKey("text");
    }
  }

  /** 段落详情恒出现 comments（空数组=无）——复审核对「这段挂了什么批注」的直接证据。 */
  @Test
  void elementParagraphShowsCommentsAlwaysPresent(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("commented.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("第一段");
      doc.addParagraph("第二段");
      doc.paragraph(1).addComment("审查员", "这段需要补充数据来源");
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      OutlineView outline = svc.outline(doc, "doc-1", 1L, ViewQuery.defaults());
      String p0 = outline.entries().get(0).ref();
      String p1 = outline.entries().get(1).ref();
      // 无批注段：空数组（属性存在，不被读成「未知」）
      ElementView e0 = svc.element(doc, "doc-1", 1L, p0);
      assertThat((java.util.List<?>) e0.properties().get("comments")).isEmpty();
      // 有批注段：id/author/text 齐全
      ElementView e1 = svc.element(doc, "doc-1", 1L, p1);
      java.util.List<java.util.Map<?, ?>> comments =
          (java.util.List<java.util.Map<?, ?>>) e1.properties().get("comments");
      assertThat(comments).hasSize(1);
      assertThat(comments.get(0).get("author")).isEqualTo("审查员");
      assertThat(comments.get(0).get("text")).isEqualTo("这段需要补充数据来源");
      assertThat(comments.get(0).get("id")).isNotNull();
    }
  }

  /** stats 恒出现 commentCount：无批注 0；加批注后按文档级计数。 */
  @Test
  void statsCountsComments(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("counted.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("第一段");
      doc.addParagraph("第二段");
      doc.paragraph(0).addComment("甲", "一条");
      doc.paragraph(1).addComment("乙", "两条");
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      assertThat(svc.stats(doc, "doc-1", 1L).commentCount()).isEqualTo(2);
    }
    Path plain = tmp.resolve("plain.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(plain);
    }
    try (Document doc = Docx.open(plain)) {
      assertThat(svc.stats(doc, "doc-1", 1L).commentCount()).isEqualTo(0);
    }
  }

  /** outline 批注概览带作者计数与最近明细（锚定段落 ref/作者/片段），ref 可直通 view_element。 */
  @Test
  void outlineCommentOverviewCarriesAuthorsAndRecentEntries(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("overview.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("第一段");
      doc.addParagraph("第二段");
      doc.paragraph(0).addComment("审查员", "首段意见");
      doc.paragraph(1).addComment("审查员", "次段意见");
      doc.save(file);
    }
    DocumentViewService svc = newService();
    try (Document doc = Docx.open(file)) {
      CommentOverview comments = svc.outline(doc, "doc-1", 1L, ViewQuery.defaults()).comments();
      assertThat(comments.total()).isEqualTo(2);
      assertThat(comments.countByAuthor()).containsEntry("审查员", 2);
      assertThat(comments.recent()).hasSize(2);
      assertThat(comments.recent())
          .extracting(CommentEntry::snippet)
          .containsExactlyInAnyOrder("首段意见", "次段意见");
      // recent 的锚定段落 ref 可直通 view_element，其 comments 里能找到同一条批注
      for (CommentEntry entry : comments.recent()) {
        ElementView detail = svc.element(doc, "doc-1", 1L, entry.paragraph());
        assertThat(detail.kind()).isEqualTo("paragraph");
        assertThat((java.util.List<?>) detail.properties().get("comments")).isNotEmpty();
      }
    }
  }
}
