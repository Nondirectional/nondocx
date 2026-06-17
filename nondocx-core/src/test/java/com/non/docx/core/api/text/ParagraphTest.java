package com.non.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 {@link Paragraph} 的 run 添加/移除、对齐方式往返、标题级别（H1–H6）往返、 run 排序以及段落内容相等性。 */
class ParagraphTest {

  @Test
  void addRunAppendsAndRunsIsOrdered() {
    Paragraph p = Docx.create().addParagraph();

    p.addRun("first");
    p.addRun("second");
    p.addRun("third");

    assertThat(p.runs()).hasSize(3);
    assertThat(p.run(0).text()).isEqualTo("first");
    assertThat(p.run(1).text()).isEqualTo("second");
    assertThat(p.run(2).text()).isEqualTo("third");
  }

  @Test
  void addRunTextConvenienceSetsText() {
    Run run = Docx.create().addParagraph().addRun("hello");
    assertThat(run.text()).isEqualTo("hello");
  }

  @Test
  void removeInlineElementDropsTheRunAtGivenIndex() {
    Paragraph p = Docx.create().addParagraph();
    p.addRun("a");
    p.addRun("b");
    p.addRun("c");

    p.removeInlineElement(1); // drop "b"

    assertThat(p.runs()).hasSize(2);
    assertThat(p.run(0).text()).isEqualTo("a");
    assertThat(p.run(1).text()).isEqualTo("c");
  }

  @Test
  void removeInlineElementOutOfBoundsThrows() {
    Paragraph p = Docx.create().addParagraph();
    p.addRun("only");

    assertThatThrownBy(() -> p.removeInlineElement(5))
        .isInstanceOf(IndexOutOfBoundsException.class)
        .hasMessageContaining("inline element index 5");
    assertThatThrownBy(() -> p.removeInlineElement(-1))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void alignmentRoundTripsForAllValues(@TempDir Path tmp) throws Exception {
    for (Alignment alignment : Alignment.values()) {
      Path file = tmp.resolve("align-" + alignment + ".docx");

      Document original = Docx.create();
      Paragraph para = original.addParagraph();
      para.alignment(alignment);
      para.addRun("x");
      original.save(file);

      try (Document opened = Docx.open(file)) {
        assertThat(opened.paragraph(0).alignment())
            .as("alignment %s round-trips", alignment)
            .isEqualTo(alignment);
      }
    }
  }

  @Test
  void alignmentDefaultsToLeftWhenUnset() {
    Paragraph p = Docx.create().addParagraph();
    assertThat(p.alignment()).isEqualTo(Alignment.LEFT);
  }

  @Test
  void headingRoundTripsForAllLevels(@TempDir Path tmp) throws Exception {
    for (HeadingLevel level : HeadingLevel.values()) {
      Path file = tmp.resolve("heading-" + level + ".docx");

      Document original = Docx.create();
      Paragraph heading = original.addParagraph().heading(level);
      heading.addRun("Title " + level);
      original.save(file);

      try (Document opened = Docx.open(file)) {
        Paragraph back = opened.paragraph(0);
        assertThat(back.heading()).as("heading %s round-trips", level).isEqualTo(level);
        assertThat(back.text()).isEqualTo("Title " + level);
      }
    }
  }

  @Test
  void clearHeadingRestoresNonHeading() {
    Paragraph p = Docx.create().addParagraph();
    p.addRun("t");
    p.heading(HeadingLevel.H2);
    assertThat(p.heading()).isEqualTo(HeadingLevel.H2);

    p.clearHeading();
    assertThat(p.heading()).isNull();
  }

  @Test
  void nonHeadingStyleReadsBackAsNull() {
    Paragraph p = Docx.create().addParagraph();
    p.addRun("body");
    assertThat(p.heading()).isNull();
  }

  @Test
  void indentRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("indent.docx");

    Document original = Docx.create();
    Paragraph para = original.addParagraph();
    para.indent(720, 360);
    para.addRun("indented");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Paragraph back = opened.paragraph(0);
      assertThat(back.indentationLeft()).isEqualTo(720);
      assertThat(back.indentationFirstLine()).isEqualTo(360);
    }
  }

  @Test
  void paragraphsEqualByInlineContentAndStyle() {
    Document a = Docx.create();
    Document b = Docx.create();

    Paragraph p1 = a.addParagraph().alignment(Alignment.CENTER);
    p1.addRun("hi").bold();
    Paragraph p2 = b.addParagraph().alignment(Alignment.CENTER);
    p2.addRun("hi").bold();

    assertThat(p1).isEqualTo(p2);
    assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
  }

  @Test
  void paragraphsNotEqualWhenStyleOrContentDiffers() {
    Document a = Docx.create();
    Document b = Docx.create();

    Paragraph base = a.addParagraph();
    base.addRun("hi");
    Paragraph same = b.addParagraph();
    same.addRun("hi");

    Paragraph diffText = b.addParagraph();
    diffText.addRun("ho");
    Paragraph diffAlign = b.addParagraph().alignment(Alignment.RIGHT);
    diffAlign.addRun("hi");

    assertThat(base).isEqualTo(same);
    assertThat(base).isNotEqualTo(diffText);
    assertThat(base).isNotEqualTo(diffAlign);
  }
}
