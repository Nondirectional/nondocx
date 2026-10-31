package io.github.nondirectional.docx.toolkit.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 行内解析契约：{@code <u>…</u>} 下划线标记的解析结果，以及与既有 {@code **粗**} / {@code *斜*} / `` `代码` `` 的组合。
 *
 * <p>重点锁三类行为：
 *
 * <ol>
 *   <li><b>并集语义</b>——{@code <u>} 内层的粗/斜/代码照常生效，下划线叠在它们之上（不覆盖、不丢弃）；
 *   <li><b>宽容降级</b>——未闭合的 {@code <u>}、孤立的 {@code </u>}、空的 {@code <u></u>} 原样保留为纯文本 （与未闭合的 {@code
 *       **} 同一姿态）；
 *   <li><b>既有行为不变</b>——不含 {@code <u>} 的输入产出与 v1 逐一相同的 span 序列（含稠叠标记的字面语义）。
 * </ol>
 */
class MarkdownParserTest {

  @Test
  void parsesBasicUnderlineSpan() {
    List<Span> spans = MarkdownParser.parseInline("<u>下划线</u>");
    assertThat(spans).hasSize(1);
    assertThat(spans.get(0).text()).isEqualTo("下划线");
    assertThat(spans.get(0).underline()).isTrue();
    assertThat(spans.get(0).bold()).isFalse();
    assertThat(spans.get(0).italic()).isFalse();
    assertThat(spans.get(0).code()).isFalse();
  }

  @Test
  void underlineUnionsWithBoldItalicAndCodeInside() {
    // 下划线内层是粗体：下划线取并集，内层标记照常生效
    List<Span> bold = MarkdownParser.parseInline("<u>**粗**文</u>");
    assertThat(bold).hasSize(2);
    assertThat(bold.get(0).text()).isEqualTo("粗");
    assertThat(bold.get(0).bold()).isTrue();
    assertThat(bold.get(0).underline()).isTrue();
    assertThat(bold.get(1).text()).isEqualTo("文");
    assertThat(bold.get(1).bold()).isFalse();
    assertThat(bold.get(1).underline()).isTrue();

    List<Span> italic = MarkdownParser.parseInline("<u>*斜*</u>");
    assertThat(italic).hasSize(1);
    assertThat(italic.get(0).italic()).isTrue();
    assertThat(italic.get(0).underline()).isTrue();

    List<Span> code = MarkdownParser.parseInline("<u>`代码`</u>");
    assertThat(code).hasSize(1);
    assertThat(code.get(0).code()).isTrue();
    assertThat(code.get(0).underline()).isTrue();
  }

  @Test
  void underlineComposesWithSurroundingInlineMarkers() {
    // 前后缀纯文本 + 下划线片段
    List<Span> around = MarkdownParser.parseInline("前<u>中</u>后");
    assertThat(around).hasSize(3);
    assertThat(around.get(0).text()).isEqualTo("前");
    assertThat(around.get(0).underline()).isFalse();
    assertThat(around.get(1).text()).isEqualTo("中");
    assertThat(around.get(1).underline()).isTrue();
    assertThat(around.get(2).text()).isEqualTo("后");
    assertThat(around.get(2).underline()).isFalse();

    // 粗体外层 + 下划线内层（读侧渲染 **<u>x</u>** 必须能解析回来）
    List<Span> boldOuter = MarkdownParser.parseInline("**<u>粗下</u>**");
    assertThat(boldOuter).hasSize(1);
    assertThat(boldOuter.get(0).text()).isEqualTo("粗下");
    assertThat(boldOuter.get(0).bold()).isTrue();
    assertThat(boldOuter.get(0).underline()).isTrue();

    // 斜体外层 + 下划线内层（读侧渲染 *<u>x</u>*）
    List<Span> italicOuter = MarkdownParser.parseInline("*<u>斜下</u>*");
    assertThat(italicOuter).hasSize(1);
    assertThat(italicOuter.get(0).text()).isEqualTo("斜下");
    assertThat(italicOuter.get(0).italic()).isTrue();
    assertThat(italicOuter.get(0).underline()).isTrue();
  }

  @Test
  void unclosedAndOrphanTagsStayLiteral() {
    // 未闭合：整段原样文本，不产生下划线 span
    List<Span> unclosed = MarkdownParser.parseInline("<u>未闭合");
    assertThat(unclosed).hasSize(1);
    assertThat(unclosed.get(0).text()).isEqualTo("<u>未闭合");
    assertThat(unclosed.get(0).underline()).isFalse();

    // 孤立闭合标签：原样保留，后续正常片段仍解析
    List<Span> orphan = MarkdownParser.parseInline("前</u>后<u>中</u>");
    assertThat(orphan).hasSize(2);
    assertThat(orphan.get(0).text()).isEqualTo("前</u>后");
    assertThat(orphan.get(0).underline()).isFalse();
    assertThat(orphan.get(1).text()).isEqualTo("中");
    assertThat(orphan.get(1).underline()).isTrue();
  }

  @Test
  void emptyUnderlineTagStaysLiteralLikeEmptyBold() {
    // `<u></u>` 的 `.+?` 要求至少一个字符 → 不命中，与 `****` 同样的字面降级
    List<Span> emptyUnderline = MarkdownParser.parseInline("<u></u>");
    assertThat(emptyUnderline).hasSize(1);
    assertThat(emptyUnderline.get(0).text()).isEqualTo("<u></u>");
    assertThat(emptyUnderline.get(0).underline()).isFalse();

    List<Span> emptyBold = MarkdownParser.parseInline("****");
    assertThat(emptyBold).hasSize(1);
    assertThat(emptyBold.get(0).text()).isEqualTo("****");
    assertThat(emptyBold.get(0).bold()).isFalse();
  }

  @Test
  void codeSpanKeepsUnderlineTagsLiteral() {
    // 代码 span 优先于 `<u>` 分支（最左匹配从反引号开始）：行内代码里的 `<u>` 不该变成下划线
    List<Span> spans = MarkdownParser.parseInline("看`<u>原文</u>`这里");
    assertThat(spans).hasSize(3);
    assertThat(spans.get(1).text()).isEqualTo("<u>原文</u>");
    assertThat(spans.get(1).code()).isTrue();
    assertThat(spans.get(1).underline()).isFalse();
  }

  @Test
  void keepsChineseAndSpaceBoundaries() {
    List<Span> spans = MarkdownParser.parseInline("前缀 <u> 中文 空格 </u> 后缀");
    assertThat(spans).hasSize(3);
    assertThat(spans.get(0).text()).isEqualTo("前缀 ");
    assertThat(spans.get(1).text()).isEqualTo(" 中文 空格 ");
    assertThat(spans.get(1).underline()).isTrue();
    assertThat(spans.get(2).text()).isEqualTo(" 后缀");
  }

  @Test
  void plainAndLegacyMarkerInputsKeepV1SpanSequence() {
    // 纯文本：单 span、无任何标记
    List<Span> plain = MarkdownParser.parseInline("普通文本");
    assertThat(plain).hasSize(1);
    assertThat(plain.get(0).text()).isEqualTo("普通文本");
    assertThat(plain.get(0).underline()).isFalse();

    // 既有标记：序列与 v1 相同（粗 / 代码 / 斜）
    List<Span> legacy = MarkdownParser.parseInline("**粗**与`代码`又*斜*");
    assertThat(legacy).extracting(Span::text).containsExactly("粗", "与", "代码", "又", "斜");
    assertThat(legacy.get(0).bold()).isTrue();
    assertThat(legacy.get(2).code()).isTrue();
    assertThat(legacy.get(4).italic()).isTrue();
    assertThat(legacy).noneMatch(Span::underline);

    // 粗体内部的其它标记仍是字面（v1「内层其它标记保持字面」语义不变）
    List<Span> nestedLiteral = MarkdownParser.parseInline("**a *b* c**");
    assertThat(nestedLiteral).hasSize(1);
    assertThat(nestedLiteral.get(0).text()).isEqualTo("a *b* c");
    assertThat(nestedLiteral.get(0).bold()).isTrue();
  }

  @Test
  void nestedSameNameTagIsNotSupportedAndKeepsSecondCloseLiteral() {
    // design 语法契约：同名嵌套不做，按最左闭合匹配，第二个 </u> 原样保留
    List<Span> spans = MarkdownParser.parseInline("<u><u>x</u></u>");
    assertThat(spans).hasSize(2);
    assertThat(spans.get(0).text()).isEqualTo("<u>x");
    assertThat(spans.get(0).underline()).isTrue();
    assertThat(spans.get(1).text()).isEqualTo("</u>");
    assertThat(spans.get(1).underline()).isFalse();
  }

  @Test
  void tableCellsStripUnderlineMarkerAsPlainText() {
    // create_document 的表格单元格是纯文本通道：`<u>` 与 `**` 同为「剥标记」，不留字面标签
    MarkdownDoc parsed = MarkdownParser.parse("| **表头** | 备注 |\n|---|---|\n| <u>格</u> | 纯文本 |\n");
    MarkdownDoc.Table table = (MarkdownDoc.Table) parsed.blocks().get(0);
    assertThat(table.header()).containsExactly("表头", "备注");
    assertThat(table.rows().get(0)).containsExactly("格", "纯文本");
  }

  @Test
  void headingStripsUnderlineMarkerToPlainText() {
    // 标题是单 run 扁平文本：`<u>` 不生效但也不留字面标签（与 `**` 同口径）
    MarkdownDoc parsed = MarkdownParser.parse("# 标题<u>下划线</u>");
    MarkdownDoc.Heading heading = (MarkdownDoc.Heading) parsed.blocks().get(0);
    assertThat(heading.text()).isEqualTo("标题下划线");
  }
}
