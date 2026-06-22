package com.non.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
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
