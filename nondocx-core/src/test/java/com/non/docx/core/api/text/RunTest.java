package com.non.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.RunStyle;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证可链式变体器、内联样式的往返持久化、内容相等性以及 {@link Run} 的 {@code style()} 快照。 */
class RunTest {

  @Test
  void chainableMutatorsApplyLiveThroughTheDelegate() {
    Document doc = Docx.create();
    Run run = doc.addParagraph().addRun();

    Run returned =
        run.text("hello").bold().italic().underline().fontSize(14).font("Arial").color("FF0000");

    assertThat(returned).isSameAs(run);
    assertThat(run.text()).isEqualTo("hello");
    assertThat(run.isBold()).isTrue();
    assertThat(run.isItalic()).isTrue();
    assertThat(run.isUnderline()).isTrue();
    assertThat(run.fontSize()).isEqualTo(14);
    assertThat(run.font()).isEqualTo("Arial");
    assertThat(run.color()).isEqualTo("FF0000");
  }

  @Test
  void boldItalicUnderlineCanBeClearedViaBooleanOverload() {
    Run run = Docx.create().addParagraph().addRun("x").bold().italic().underline();

    run.bold(false).italic(false).underline(false);

    assertThat(run.isBold()).isFalse();
    assertThat(run.isItalic()).isFalse();
    assertThat(run.isUnderline()).isFalse();
  }

  @Test
  void unsetStyleAttributesAreNull(@TempDir Path tmp) throws Exception {
    Document doc = Docx.create();
    Run run = doc.addParagraph().addRun("plain");

    assertThat(run.isBold()).isFalse();
    assertThat(run.isItalic()).isFalse();
    assertThat(run.isUnderline()).isFalse();
    assertThat(run.fontSize()).isNull();
    assertThat(run.font()).isNull();
    assertThat(run.color()).isNull();

    RunStyle snapshot = run.style();
    assertThat(snapshot).isEqualTo(RunStyle.empty());
  }

  @Test
  void stylesSurviveRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("run.docx");

    Document original = Docx.create();
    original
        .addParagraph()
        .addRun("styled")
        .bold()
        .italic()
        .underline()
        .fontSize(18)
        .font("Courier New")
        .color("00AA00");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Run back = opened.paragraph(0).run(0);
      assertThat(back.text()).isEqualTo("styled");
      assertThat(back.isBold()).isTrue();
      assertThat(back.isItalic()).isTrue();
      assertThat(back.isUnderline()).isTrue();
      assertThat(back.fontSize()).isEqualTo(18);
      assertThat(back.font()).isEqualTo("Courier New");
      assertThat(back.color()).isEqualToIgnoringCase("00AA00");
    }
  }

  @Test
  void equalsByContentIgnoresDelegateIdentity() {
    Document a = Docx.create();
    Document b = Docx.create();

    Run r1 = a.addParagraph().addRun("hi").bold().fontSize(12);
    Run r2 = b.addParagraph().addRun("hi").bold().fontSize(12);
    Run r3 = b.addParagraph().addRun("hi"); // same text, different style
    Run r4 = b.addParagraph().addRun("ho").bold().fontSize(12); // same style, different text

    assertThat(r1).isEqualTo(r2);
    assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    assertThat(r1).isNotEqualTo(r3);
    assertThat(r1).isNotEqualTo(r4);
    assertThat(r1).isNotEqualTo(null);
    assertThat(r1).isNotEqualTo("not a run");
  }

  @Test
  void textRejectsNull() {
    Run run = Docx.create().addParagraph().addRun();
    assertThatThrownBy(() -> run.text(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("text");
  }

  @Test
  void rawReturnsSameDelegateInstance() {
    Run run = Docx.create().addParagraph().addRun("x");
    assertThat(run.raw()).isSameAs(run.raw());
  }

  /**
   * N9 回归测试：对一个已有文本的 run 调 {@code text(新文本)}，必须是替换语义， 不能把新文本拼到旧文本后面（POI {@code XWPFRun.setText}
   * 的追加行为）。
   */
  @Test
  void textReplacesRatherThanAppends() {
    Run run = Docx.create().addParagraph().addRun("旧文本");
    run.text("新文本");
    assertThat(run.text()).isEqualTo("新文本");

    // 反复替换也不应累积
    run.text("再换一次");
    assertThat(run.text()).isEqualTo("再换一次");
  }

  /** N9 回归测试（往返）：替换 run 文本后 save→reopen，读回的必须是新文本， 且不能是「旧+新」拼接。 */
  @Test
  void textReplacementSurvivesRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("run-replace.docx");

    Document original = Docx.create();
    original.addParagraph().addRun("原文");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      opened.paragraph(0).run(0).text("替换后的文本");
      opened.save(file);
    }

    try (Document reopened = Docx.open(file)) {
      String back = reopened.paragraph(0).run(0).text();
      assertThat(back).isEqualTo("替换后的文本");
      assertThat(back).doesNotContain("原文");
    }
  }
}
