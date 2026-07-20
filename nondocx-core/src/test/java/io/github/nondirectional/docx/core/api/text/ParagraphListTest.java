package io.github.nondirectional.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.ListKind;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@code Paragraph.list(ListKind, int)} / {@code clearList()} 在保存→打开往返中存活，
 * 嵌套级别存活，且列表成员资格参与内容相等性。
 */
class ParagraphListTest {

  @Test
  void bulletListRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("bullet.docx");

    Document original = Docx.create();
    original.addParagraph().list(ListKind.BULLET, 0).addRun("item");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Paragraph p = opened.paragraph(0);
      assertThat(p.listKind()).isEqualTo(ListKind.BULLET);
      assertThat(p.listLevel()).isEqualTo(0);
      assertThat(p.text()).isEqualTo("item");
    }
  }

  @Test
  void numberedListRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("numbered.docx");

    Document original = Docx.create();
    original.addParagraph().list(ListKind.NUMBERED, 0).addRun("first");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Paragraph p = opened.paragraph(0);
      assertThat(p.listKind()).isEqualTo(ListKind.NUMBERED);
      assertThat(p.listLevel()).isEqualTo(0);
      assertThat(p.text()).isEqualTo("first");
    }
  }

  @Test
  void nestedLevelRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("nested.docx");

    Document original = Docx.create();
    original.addParagraph().list(ListKind.BULLET, 0).addRun("l0");
    original.addParagraph().list(ListKind.BULLET, 1).addRun("l1");
    original.addParagraph().list(ListKind.BULLET, 2).addRun("l2");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraph(0).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(opened.paragraph(1).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(opened.paragraph(2).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(opened.paragraph(0).listLevel()).isEqualTo(0);
      assertThat(opened.paragraph(1).listLevel()).isEqualTo(1);
      assertThat(opened.paragraph(2).listLevel()).isEqualTo(2);
    }
  }

  @Test
  void clearListRemovesMembership(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("clear.docx");

    Document original = Docx.create();
    Paragraph p = original.addParagraph();
    p.list(ListKind.BULLET, 0);
    p.addRun("x");
    p.clearList();
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraph(0).listKind()).isNull();
      assertThat(opened.paragraph(0).listLevel()).isNull();
    }
  }

  @Test
  void mixedBulletAndNumberedCoexist(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("mixed.docx");

    Document original = Docx.create();
    original.addParagraph().list(ListKind.BULLET, 0).addRun("b");
    original.addParagraph().list(ListKind.NUMBERED, 0).addRun("n");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraph(0).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(opened.paragraph(1).listKind()).isEqualTo(ListKind.NUMBERED);
      assertThat(opened.paragraph(0).listLevel()).isEqualTo(0);
      assertThat(opened.paragraph(1).listLevel()).isEqualTo(0);
    }
  }

  @Test
  void repeatedListCallsReuseSingleNumberingDefinition() {
    // 在同一文档上重复调用 list(...) 不应抛出异常，且应保持所有段落为列表成员。
    Document doc = Docx.create();
    doc.addParagraph().list(ListKind.BULLET, 0).addRun("a");
    doc.addParagraph().list(ListKind.BULLET, 0).addRun("b");
    doc.addParagraph().list(ListKind.BULLET, 0).addRun("c");

    assertThat(doc.paragraph(0).listKind()).isEqualTo(ListKind.BULLET);
    assertThat(doc.paragraph(1).listKind()).isEqualTo(ListKind.BULLET);
    assertThat(doc.paragraph(2).listKind()).isEqualTo(ListKind.BULLET);
  }

  @Test
  void listMembershipParticipatesInContentEquality() {
    Paragraph bullet = Docx.create().addParagraph().list(ListKind.BULLET, 0);
    Paragraph numbered = Docx.create().addParagraph().list(ListKind.NUMBERED, 0);
    Paragraph plain = Docx.create().addParagraph();
    Paragraph bulletAgain = Docx.create().addParagraph().list(ListKind.BULLET, 0);

    // 不同类型不同；列表与非列表不同；相同类型+级别相等。
    assertThat(bullet).isNotEqualTo(numbered);
    assertThat(bullet).isNotEqualTo(plain);
    assertThat(numbered).isNotEqualTo(plain);
    assertThat(bullet).isEqualTo(bulletAgain);
    assertThat(bullet.hashCode()).isEqualTo(bulletAgain.hashCode());

    // 不同的嵌套级别不同。
    Paragraph deeper = Docx.create().addParagraph().list(ListKind.BULLET, 2);
    assertThat(bullet).isNotEqualTo(deeper);
  }
}
