package io.github.nondirectional.docx.toolkit.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.style.ListKind;
import io.github.nondirectional.docx.toolkit.compiler.DocumentCompiler.CompiledDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;

/**
 * 编译管线端到端测试：Markdown → IR → report-cn profile 编译 → docx 断言 → 落盘重开仍成立。
 *
 * <p>重点锁定三类契约：
 *
 * <ol>
 *   <li><b>CJK 双字体槽</b>——ascii 槽（宋体→Times New Roman）与 eastAsia 槽（宋体/黑体） 必须同时写入，这是编译器存在的核心理由之一（core
 *       公开 API 只有单槽）；
 *   <li><b>排版兜底</b>——行距 1.5、首行缩进 2 字符（480 缇）、H1 居中、表头加粗+灰底、 等宽百分比列宽；
 *   <li><b>往返保真</b>——save 后重新 open，上述断言全部保持。
 * </ol>
 */
class CompilerPipelineTest {

  private static final String WEEKLY_REPORT =
      "# 项目周报 {align=center}\n"
          + "\n"
          + "本周完成了 nondocx 的表格封装，整体进度符合预期。\n"
          + "\n"
          + "关键进展是 **双字体槽** 与 `行距换算`。\n"
          + "\n"
          + "## 下周计划\n"
          + "\n"
          + "- 完成编译器 spike\n"
          + "  - 补充文档\n"
          + "1. 评审\n"
          + "\n"
          + "| 模块 | 状态 | 负责人 |\n"
          + "|---|---|---|\n"
          + "| nondocx-core | 已完成 | non |\n"
          + "| nondocx-toolkit | 进行中 | non |\n";

  @Test
  void reportCnProfileCompilesStructureStyleAndFonts() {
    MarkdownDoc parsed = MarkdownParser.parse(WEEKLY_REPORT);
    assertThat(parsed.warnings()).isEmpty();
    // 1 标题 + 2 段落 + 1 H2 + 3 列表项 = 7 段落块 + 1 表格块
    assertThat(parsed.blocks()).hasSize(8);

    CompiledDocument compiled = DocumentCompiler.compile(parsed, StyleProfile.builtin("report-cn"));
    Document doc = compiled.document();
    try {
      assertThat(compiled.paragraphCount()).isEqualTo(7);
      assertThat(compiled.tableCount()).isEqualTo(1);

      // H1：标题级别 + 居中 + 黑体（eastAsia）/Arial（ascii）+ 16pt
      var h1 = doc.paragraph(0);
      assertThat(h1.heading()).isEqualTo(HeadingLevel.H1);
      assertThat(h1.alignment()).isEqualTo(Alignment.CENTER);
      assertThat(h1.run(0).text()).isEqualTo("项目周报");
      assertThat(h1.run(0).font()).isEqualTo("Arial");
      assertThat(eastAsia(h1.run(0))).isEqualTo("黑体");
      assertThat(h1.run(0).fontSize()).isEqualTo(16);

      // 正文段 1：宋体双槽 + 小四 + 1.5 行距 + 首行缩进 2 字符（480 缇）
      var p1 = doc.paragraph(1);
      assertThat(p1.lineSpacing()).isEqualTo(1.5);
      assertThat(p1.indentationFirstLine()).isEqualTo(480);
      assertThat(p1.run(0).font()).isEqualTo("Times New Roman");
      assertThat(eastAsia(p1.run(0))).isEqualTo("宋体");
      assertThat(p1.run(0).fontSize()).isEqualTo(12);

      // 正文段 2：行内粗体与代码 span 各自成 run（前后文字各占一段 span）
      var p2 = doc.paragraph(2);
      assertThat(p2.runs()).hasSize(5);
      assertThat(p2.run(1).text()).isEqualTo("双字体槽");
      assertThat(p2.run(1).isBold()).isTrue();
      assertThat(p2.run(3).text()).isEqualTo("行距换算");
      assertThat(p2.run(4).text()).isEqualTo("。");
      assertThat(p2.run(3).font()).isEqualTo("Consolas");

      // H2 标题：黑体 14pt，默认不居中
      var h2 = doc.paragraph(3);
      assertThat(h2.heading()).isEqualTo(HeadingLevel.H2);
      assertThat(h2.alignment()).isEqualTo(Alignment.LEFT);
      assertThat(h2.run(0).fontSize()).isEqualTo(14);

      // 列表：无序 level0 / 嵌套无序 level1 / 有序 level0
      assertThat(doc.paragraph(4).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(doc.paragraph(4).listLevel()).isZero();
      assertThat(doc.paragraph(5).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(doc.paragraph(5).listLevel()).isEqualTo(1);
      assertThat(doc.paragraph(6).listKind()).isEqualTo(ListKind.NUMBERED);

      // 表格：3 行 3 列，表头加粗 + 灰底 + 跨页重复，数据行不跨页拆分
      var table = doc.tables().get(0);
      assertThat(table.rows()).hasSize(3);
      assertThat(table.rows().get(0).headerRow()).isTrue();
      assertThat(table.rows().get(1).cantSplit()).isTrue();
      assertThat(table.rows().get(0).cells().get(1).text()).isEqualTo("状态");
      assertThat(table.rows().get(0).cells().get(0).paragraph(0).run(0).isBold()).isTrue();
      assertThat(table.rows().get(0).cells().get(0).shading().fill()).isEqualTo("F2F2F2");
      assertThat(table.rows().get(1).cells().get(0).paragraph(0).run(0).isBold()).isFalse();
      assertThat(table.rows().get(2).cells().get(1).text()).isEqualTo("进行中");
    } finally {
      doc.close();
    }
  }

  @Test
  void compiledDocumentSurvivesSaveAndReopen(@TempDir Path temp) throws Exception {
    MarkdownDoc parsed = MarkdownParser.parse(WEEKLY_REPORT);
    Path file = temp.resolve("out.docx");
    try (Document doc =
        DocumentCompiler.compile(parsed, StyleProfile.builtin("report-cn")).document()) {
      doc.save(file);
    }
    assertThat(Files.exists(file)).isTrue();
    try (Document reopened = io.github.nondirectional.docx.core.Docx.open(file)) {
      assertThat(reopened.paragraph(0).heading()).isEqualTo(HeadingLevel.H1);
      assertThat(reopened.paragraph(0).alignment()).isEqualTo(Alignment.CENTER);
      assertThat(eastAsia(reopened.paragraph(0).run(0))).isEqualTo("黑体");
      assertThat(reopened.paragraph(1).lineSpacing()).isEqualTo(1.5);
      assertThat(reopened.paragraph(1).indentationFirstLine()).isEqualTo(480);
      assertThat(eastAsia(reopened.paragraph(1).run(0))).isEqualTo("宋体");
      assertThat(reopened.tables().get(0).rows().get(0).cells().get(0).shading().fill())
          .isEqualTo("F2F2F2");
    }
  }

  @Test
  void plainProfileOnlyBuildsStructureWithoutStyling() {
    MarkdownDoc parsed = MarkdownParser.parse("# 标题\n\n正文一段。\n");
    CompiledDocument compiled = DocumentCompiler.compile(parsed, StyleProfile.builtin("plain"));
    Document doc = compiled.document();
    try {
      assertThat(doc.paragraph(0).heading()).isEqualTo(HeadingLevel.H1);
      // plain 不设字体/字号/行距/缩进（未显式设置时 POI 读回 -1 或 null）
      assertThat(doc.paragraph(0).run(0).fontSize()).isNull();
      assertThat(doc.paragraph(1).lineSpacing()).isLessThanOrEqualTo(0);
      assertThat(doc.paragraph(1).indentationFirstLine()).isNegative();
      assertThat(doc.paragraph(1).run(0).font()).isNull();
    } finally {
      doc.close();
    }
  }

  @Test
  void lenientParsingDegradesUnknownSyntaxToParagraphsWithWarnings() {
    // 表格缺分隔行 → 降级为段落 + warning；未闭合粗体 → 原样保留
    MarkdownDoc parsed = MarkdownParser.parse("| A | B |\n| 1 | 2 |\n\n这是 **未闭合 粗体\n");
    assertThat(parsed.warnings()).isNotEmpty();
    assertThat(parsed.warnings().get(0)).contains("分隔行");
    assertThat(parsed.blocks()).hasSize(3);
    var last = (MarkdownDoc.Para) parsed.blocks().get(2);
    assertThat(last.spans().get(0).text()).contains("**");
  }

  @Test
  void escapedPipeAndRaggedRowsAreHandled() {
    MarkdownDoc parsed =
        MarkdownParser.parse("| a\\|b | c |\n|---|---|\n| only-one-cell |\n| x | y | extra |\n");
    var table = (MarkdownDoc.Table) parsed.blocks().get(0);
    assertThat(table.header()).containsExactly("a|b", "c");
    // 行列数不齐：补空/截断，且有 warning
    assertThat(parsed.warnings()).hasSize(2);
    assertThat(table.rows().get(0)).containsExactly("only-one-cell", "");
    assertThat(table.rows().get(1)).containsExactly("x", "y");
  }

  @Test
  void markdownViewRoundTripsCompiledDocument() {
    MarkdownDoc parsed = MarkdownParser.parse(WEEKLY_REPORT);
    Document doc = DocumentCompiler.compile(parsed, StyleProfile.builtin("report-cn")).document();
    MarkdownView.Rendered rendered;
    try {
      rendered = MarkdownView.render(doc, 0);
    } finally {
      doc.close();
    }
    String markdown = rendered.markdown();
    assertThat(markdown)
        .contains("# 项目周报 {align=center} <!-- p0 -->")
        .contains("**双字体槽**")
        .contains("- 完成编译器 spike <!-- p4 -->")
        .contains("  - 补充文档 <!-- p5 -->")
        .contains("1. 评审 <!-- p6 -->")
        .contains("| 模块 | 状态 | 负责人 |\n|---|---|---|")
        .contains("| nondocx-toolkit | 进行中 | non |\n<!-- t0 -->");
    assertThat(rendered.truncated()).isFalse();
    assertThat(rendered.paragraphs()).isEqualTo(7);
    assertThat(rendered.tables()).isEqualTo(1);

    // round-trip：视图再解析 → 编译，结构与数量等价
    MarkdownDoc reparsed = MarkdownParser.parse(markdown);
    assertThat(reparsed.blocks()).hasSize(8);
    assertThat(reparsed.warnings()).isEmpty();
  }

  @Test
  void markdownViewTruncatesAtLineBoundary() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      big.append("第 ").append(i).append(" 段，内容内容内容内容内容内容。\n");
    }
    MarkdownDoc parsed = MarkdownParser.parse(big.toString());
    Document doc = DocumentCompiler.compile(parsed, StyleProfile.builtin("plain")).document();
    try {
      MarkdownView.Rendered rendered = MarkdownView.render(doc, 200);
      assertThat(rendered.truncated()).isTrue();
      assertThat(rendered.markdown()).contains("<!-- 已截断 -->");
    } finally {
      doc.close();
    }
  }

  @Test
  void emptyMarkdownIsRejectedByCompiler() {
    MarkdownDoc parsed = MarkdownParser.parse("   \n");
    assertThat(parsed.blocks()).isEmpty();
    try {
      DocumentCompiler.compile(parsed, StyleProfile.builtin("plain"));
      throw new AssertionError("空 IR 应抛 IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).contains("空");
    }
  }

  /** 读 run 的 eastAsia 字体槽（core 公开 API 无此入口，测试经 raw 断言编译器兜底写入）。 */
  private static String eastAsia(io.github.nondirectional.docx.core.api.text.Run run) {
    CTRPr rpr = run.raw().getCTR().getRPr();
    if (rpr == null || rpr.sizeOfRFontsArray() == 0) {
      return null;
    }
    CTFonts fonts = rpr.getRFontsArray(0);
    return fonts.getEastAsia();
  }
}
