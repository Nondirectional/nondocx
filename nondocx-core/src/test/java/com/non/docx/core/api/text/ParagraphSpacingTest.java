package com.non.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 {@code Paragraph.lineSpacing(double)} 在保存→打开往返中存活。 */
class ParagraphSpacingTest {

  @Test
  void lineSpacingRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("spacing.docx");

    Document original = Docx.create();
    Paragraph p = original.addParagraph();
    p.lineSpacing(1.5);
    p.addRun("lines");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraph(0).lineSpacing()).isCloseTo(1.5, within(1e-9));
    }
  }

  @Test
  void lineSpacingDefaultsToUnsetSentinel() {
    Paragraph p = Docx.create().addParagraph();
    // POI 在行距未显式设置时报告 -1.0；这在 getter 的 Javadoc 中有说明。
    assertThat(p.lineSpacing()).isEqualTo(-1.0);
  }

  @Test
  void lineSpacingDoubleSurvivesRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("spacing2.docx");

    Document original = Docx.create();
    Paragraph p = original.addParagraph();
    p.lineSpacing(2.0);
    p.addRun("lines");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraph(0).lineSpacing()).isCloseTo(2.0, within(1e-9));
    }
  }
}
