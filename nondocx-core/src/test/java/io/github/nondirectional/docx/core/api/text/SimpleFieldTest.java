package io.github.nondirectional.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.InlineElement;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@link Paragraph#addSimpleField(String)} 及便捷方法（{@link Paragraph#addPageNumberField()} / {@link
 * Paragraph#addPageCountField()}）写出的简单域经 save → reopen 后指令文本存活， 以及边界行为。
 *
 * <p>域的<b>读侧</b>识别不在本子任务范围（走 {@code raw()}），故 round-trip 断言通过底层 {@code CTR.getInstrTextArray}
 * 读取指令，不经过公开 API。
 */
class SimpleFieldTest {

  @Test
  void simpleFieldRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("field.docx");
    Document original = Docx.create();
    original.addParagraph().addSimpleField("PAGE");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(readInstrText(opened.paragraph(0))).isEqualTo("PAGE");
    }
  }

  @Test
  void pageNumberFieldEqualsAddSimpleFieldPage() {
    Document a = Docx.create();
    Document b = Docx.create();
    a.addParagraph().addPageNumberField();
    b.addParagraph().addSimpleField("PAGE");

    assertThat(readInstrText(a.paragraph(0))).isEqualTo("PAGE");
    assertThat(readInstrText(b.paragraph(0))).isEqualTo("PAGE");
  }

  @Test
  void pageCountFieldEqualsAddSimpleFieldNumPages() {
    Document a = Docx.create();
    Document b = Docx.create();
    a.addParagraph().addPageCountField();
    b.addParagraph().addSimpleField("NUMPAGES");

    assertThat(readInstrText(a.paragraph(0))).isEqualTo("NUMPAGES");
    assertThat(readInstrText(b.paragraph(0))).isEqualTo("NUMPAGES");
  }

  @Test
  void arbitraryInstructionRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("arbitrary.docx");
    Document original = Docx.create();
    original.addParagraph().addSimpleField("DATE \\@ yyyy");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(readInstrText(opened.paragraph(0))).isEqualTo("DATE \\@ yyyy");
    }
  }

  @Test
  void rejectsBlankInstruction() {
    Paragraph p = Docx.create().addParagraph();
    assertThatThrownBy(() -> p.addSimpleField("   ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullInstruction() {
    Paragraph p = Docx.create().addParagraph();
    assertThatThrownBy(() -> p.addSimpleField(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void returnedRunAcceptsStyle() {
    Run run = Docx.create().addParagraph().addPageNumberField().bold();
    assertThat(run.isBold()).isTrue();
  }

  /** 带缓存结果的域是五 run 结构（begin/instr/separate/result/end），POI 文本读侧能看到缓存值。 */
  @Test
  void cachedResultFieldIsFiveRunsAndReadableAsText() {
    Paragraph p = Docx.create().addParagraph();
    p.addRun("第 ");
    Run result = p.addSimpleField("PAGE", "1").bold();
    p.addRun(" 页");

    assertThat(result.text()).isEqualTo("1");
    assertThat(result.isBold()).isTrue(); // 返回的是结果 run，可链式设样式
    // POI 纯文本读侧能读到缓存值——纯指令域会读出「第  页」（无数字）
    assertThat(p.text()).isEqualTo("第 1 页");
    // 五 run 域结构（段落 run 序 1–5，前后是「第 」「 页」）：begin / instrText / separate / result / end
    assertThat(p.raw().getRuns()).hasSize(7);
    assertThat(readInstrText(p)).isEqualTo("PAGE");
    assertThat(p.raw().getRuns().get(1).getCTR().getFldCharArray(0).getFldCharType().toString())
        .isEqualTo("begin");
    assertThat(p.raw().getRuns().get(3).getCTR().getFldCharArray(0).getFldCharType().toString())
        .isEqualTo("separate");
    assertThat(p.raw().getRuns().get(4).getCTR().getTArray(0).getStringValue()).isEqualTo("1");
    assertThat(p.raw().getRuns().get(5).getCTR().getFldCharArray(0).getFldCharType().toString())
        .isEqualTo("end");
  }

  /** 带缓存结果的域 round-trip 后指令与缓存值都存活（页脚页码场景）。 */
  @Test
  void cachedResultFieldRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cached-field.docx");
    Document original = Docx.create();
    original.addParagraph("正文");
    original.ensureFooter().addParagraph().addSimpleField("PAGE", "1");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(readInstrText(opened.footer().paragraph(0))).isEqualTo("PAGE");
      assertThat(opened.footer().text()).contains("1");
    }
  }

  @Test
  void cachedResultFieldRejectsBlank() {
    Paragraph p = Docx.create().addParagraph();
    assertThatThrownBy(() -> p.addSimpleField("PAGE", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单参");
    assertThatThrownBy(() -> p.addSimpleField("PAGE", (String) null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fieldRunsAppearInInlineElements() {
    Paragraph p = Docx.create().addParagraph();
    p.addPageNumberField();

    // 域产出 3 个 run（begin / instrText / end），全部以空文本 Run 出现在 inline 视图里。
    assertThat(p.inlineElements()).hasSize(3);
    for (InlineElement e : p.inlineElements()) {
      assertThat(e).isInstanceOf(Run.class);
      assertThat(((Run) e).text()).isEmpty();
    }
  }

  /**
   * 读段落的 instrText（域指令）。读侧不在本子任务的公开 API 范围，走 raw CTR。
   *
   * @return 第一个含 instrText 的 run 的指令文本，没有则 null
   */
  private static String readInstrText(Paragraph p) {
    for (XWPFRun run : p.raw().getRuns()) {
      if (run.getCTR().sizeOfInstrTextArray() > 0) {
        return run.getCTR().getInstrTextArray(0).getStringValue();
      }
    }
    return null;
  }
}
