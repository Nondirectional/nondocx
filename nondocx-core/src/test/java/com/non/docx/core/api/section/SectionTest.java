package com.non.docx.core.api.section;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证节的页面属性（纸张大小、方向、页边距）在保存→打开往返中存活， 且 {@link Section} 内容相等性涵盖这些页面属性以及 节作用域的默认页眉/页脚段落内容（设计文档
 * §4.4、§7）。相等性必须 只读地解析页眉/页脚内容，以使其永不修改文档。
 */
class SectionTest {

  @Test
  void paperSizeRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("paper.docx");

    Document original = Docx.create();
    original.section(0).paperSize(PaperSize.A4);
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.section(0).paperSize()).isEqualTo(PaperSize.A4);
    }
  }

  @Test
  void paperSizeRecognizesMultipleSizes() {
    for (PaperSize size : PaperSize.values()) {
      Document doc = Docx.create();
      doc.section(0).paperSize(size);
      assertThat(doc.section(0).paperSize())
          .as("paper size %s should round-trip in-memory", size)
          .isEqualTo(size);
    }
  }

  @Test
  void orientationRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("orientation.docx");

    Document original = Docx.create();
    original.section(0).orientation(Orientation.LANDSCAPE);
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.section(0).orientation()).isEqualTo(Orientation.LANDSCAPE);
    }

    // switch back to portrait and round-trip again
    try (Document reopened = Docx.open(file)) {
      reopened.section(0).orientation(Orientation.PORTRAIT);
      Path portraitFile = tmp.resolve("orientation-portrait.docx");
      reopened.save(portraitFile);

      try (Document opened = Docx.open(portraitFile)) {
        assertThat(opened.section(0).orientation()).isEqualTo(Orientation.PORTRAIT);
      }
    }
  }

  @Test
  void marginsRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("margins.docx");

    Document original = Docx.create();
    original.section(0).margins(1440, 1800, 1440, 1800);
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Section section = opened.section(0);
      assertThat(section.marginTop()).isEqualTo(1440);
      assertThat(section.marginRight()).isEqualTo(1800);
      assertThat(section.marginBottom()).isEqualTo(1440);
      assertThat(section.marginLeft()).isEqualTo(1800);
    }
  }

  @Test
  void defaultSectionAlwaysPresent() {
    Document doc = Docx.create();
    assertThat(doc.sections()).hasSizeGreaterThanOrEqualTo(1);
    assertThat(doc.section(0)).isNotNull();
  }

  @Test
  void contentEqualityByPageProperties() {
    Document a = Docx.create();
    a.section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.PORTRAIT)
        .margins(1000, 1000, 1000, 1000);

    Document b = Docx.create();
    b.section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.PORTRAIT)
        .margins(1000, 1000, 1000, 1000);

    Document c = Docx.create();
    c.section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.LANDSCAPE)
        .margins(1000, 1000, 1000, 1000);

    Document d = Docx.create();
    d.section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.PORTRAIT)
        .margins(2000, 1000, 1000, 1000);

    // same page properties → equal (even though backed by distinct CTSectPr instances)
    assertThat(a.section(0)).isEqualTo(b.section(0));
    assertThat(a.section(0).hashCode()).isEqualTo(b.section(0).hashCode());

    // 不同的方向→不相等
    assertThat(a.section(0)).isNotEqualTo(c.section(0));

    // 不同的页边距→不相等
    assertThat(a.section(0)).isNotEqualTo(d.section(0));
  }

  @Test
  void sectionsIsLiveViewAcrossFreshWrappers() {
    Document doc = Docx.create();
    doc.section(0).paperSize(PaperSize.LETTER);

    // each sections().get(0) returns a fresh wrapper, but it reads the same live state
    assertThat(doc.sections().get(0).paperSize()).isEqualTo(PaperSize.LETTER);
    assertThat(doc.section(0).paperSize()).isEqualTo(PaperSize.LETTER);
  }

  @Test
  void sectionWithHeaderDiffersFromSectionWithoutHeader() {
    Document withHeader = Docx.create();
    withHeader
        .section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.PORTRAIT)
        .margins(1000, 1000, 1000, 1000);
    withHeader.section(0).header().addParagraph().addRun("Running title");

    Document withoutHeader = Docx.create();
    withoutHeader
        .section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.PORTRAIT)
        .margins(1000, 1000, 1000, 1000);

    // identical page properties, but one has a header paragraph and the other does not
    assertThat(withHeader.section(0)).isNotEqualTo(withoutHeader.section(0));
    assertThat(withoutHeader.section(0)).isNotEqualTo(withHeader.section(0));
  }

  @Test
  void sectionsWithSameHeaderContentAreEqual() {
    Document a = Docx.create();
    a.section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.PORTRAIT)
        .margins(1000, 1000, 1000, 1000);
    a.section(0).header().addParagraph().addRun("Same header");

    Document b = Docx.create();
    b.section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.PORTRAIT)
        .margins(1000, 1000, 1000, 1000);
    b.section(0).header().addParagraph().addRun("Same header");

    // same page properties AND same header paragraph content → equal across distinct instances
    assertThat(a.section(0)).isEqualTo(b.section(0));
    assertThat(a.section(0).hashCode()).isEqualTo(b.section(0).hashCode());
  }

  @Test
  void sectionsWithDifferentHeaderContentAreNotEqual() {
    Document a = Docx.create();
    a.section(0).header().addParagraph().addRun("Alpha");

    Document b = Docx.create();
    b.section(0).header().addParagraph().addRun("Beta");

    assertThat(a.section(0)).isNotEqualTo(b.section(0));
  }

  @Test
  void sectionWithFooterDiffersFromSectionWithoutFooter() {
    Document withFooter = Docx.create();
    withFooter.section(0).footer().addParagraph().addRun("Page 1");

    Document withoutFooter = Docx.create();

    assertThat(withFooter.section(0)).isNotEqualTo(withoutFooter.section(0));
  }

  @Test
  void sectionEqualityDoesNotMutateDocument() {
    // 只读 equals 不得创建页眉/页脚部分；比较前没有页眉的节
    // 在比较后仍解析为空（无页眉）节
    Document doc = Docx.create();
    Section before = doc.section(0);

    boolean ignored = before.equals(Docx.create().section(0));

    Section after = doc.section(0);
    assertThat(after.header().paragraphs()).isEmpty();
    assertThat(before).isEqualTo(after);
  }

  @Test
  void sectionEqualitySurvivesRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("section-eq.docx");

    Document original = Docx.create();
    original
        .section(0)
        .paperSize(PaperSize.A4)
        .orientation(Orientation.LANDSCAPE)
        .margins(1440, 1440, 1440, 1440);
    original.section(0).header().addParagraph().addRun("Round-trip header");
    original.section(0).footer().addParagraph().addRun("Round-trip footer");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.section(0)).isEqualTo(original.section(0));
    }
  }
}
