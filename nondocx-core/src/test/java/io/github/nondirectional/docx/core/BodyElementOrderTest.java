package io.github.nondirectional.docx.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.api.BodyElement;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证文档正文保留段落和表格的真实交错顺序， 且 {@code paragraphs()} / {@code tables()} 是该顺序的一致过滤视图， 并验证该顺序在往返后存活。此测试守卫着
 * {@code BodyElement} 排序契约 （设计文档 §3.1）。
 */
class BodyElementOrderTest {

  @Test
  void bodyPreservesParagraphTableParagraphOrder(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("order.docx");

    Document original = Docx.create();
    original.addParagraph("P1");
    Table table = original.addTable();
    table.addRow().addCell().text("cell");
    original.addParagraph("P2");
    original.save(file);

    // 内存中的顺序
    assertInterleavedOrder(original.bodyElements(), original);

    try (Document opened = Docx.open(file)) {
      assertInterleavedOrder(opened.bodyElements(), opened);

      assertThat(((Paragraph) opened.bodyElement(0)).text()).isEqualTo("P1");
      assertThat(((Paragraph) opened.bodyElement(2)).text()).isEqualTo("P2");
    }
  }

  private static void assertInterleavedOrder(List<BodyElement> body, Document doc) {
    assertThat(body).hasSize(3);
    assertThat(body.get(0)).isInstanceOf(Paragraph.class);
    assertThat(body.get(1)).isInstanceOf(Table.class);
    assertThat(body.get(2)).isInstanceOf(Paragraph.class);

    // 过滤视图是同一顺序的一致投影
    assertThat(doc.paragraphs()).as("paragraphs() is the Paragraph-only filtered view").hasSize(2);
    assertThat(doc.tables()).as("tables() is the Table-only filtered view").hasSize(1);

    // 过滤后的段落正好是按顺序的两个正文段落
    assertThat(doc.paragraph(0)).isEqualTo(body.get(0));
    assertThat(doc.paragraph(1)).isEqualTo(body.get(2));
    assertThat(doc.tables().get(0)).isEqualTo(body.get(1));
  }

  @Test
  void appendingAtBodyEndPreservesOrder() {
    Document doc = Docx.create();
    doc.addParagraph("a");
    doc.addTable().addRow();
    doc.addParagraph("b");
    doc.addParagraph("c");
    doc.addTable().addRow();

    List<BodyElement> body = doc.bodyElements();
    assertThat(body).hasSize(5);
    // 预期的类型序列：P、T、P、P、T（按调用顺序）
    assertThat(body.get(0)).isInstanceOf(Paragraph.class);
    assertThat(body.get(1)).isInstanceOf(Table.class);
    assertThat(body.get(2)).isInstanceOf(Paragraph.class);
    assertThat(body.get(3)).isInstanceOf(Paragraph.class);
    assertThat(body.get(4)).isInstanceOf(Table.class);

    assertThat(doc.paragraphs()).hasSize(3); // a, b, c
    assertThat(doc.tables()).hasSize(2);

    // 过滤后的段落保持其正文顺序，而非重新排序
    assertThat(doc.paragraph(0).text()).isEqualTo("a");
    assertThat(doc.paragraph(1).text()).isEqualTo("b");
    assertThat(doc.paragraph(2).text()).isEqualTo("c");
  }
}
