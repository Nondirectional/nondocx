package com.non.docx.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import org.junit.jupiter.api.Test;

/**
 * 验证构建轨道（{@link DocumentBuilder}、{@link ParagraphBuilder}、{@link TableBuilder}） 以及活动对象 lambda
 * 便捷方法（{@link Table#row}、{@link Row#cell(String)}、 {@link Row#cell(java.util.function.Consumer)}）。
 *
 * <p>核心断言策略：{@code Document} 尚未实现 {@code equals}（这在阶段 7 与 {@code RoundTripTest}
 * 一起到达），因此构建器与手工构建的等价性以结构方式断言—— 正文元素计数匹配，且每个段落（{@link Paragraph#equals}）和表格（{@link Table#equals}）
 * 与其手工构建的对应项内容相等。这证明了构建器相对于直接使用活动对象 API 不添加、删除 或改变任何内容。
 */
class DocumentBuilderTest {

  @Test
  void builderDocumentEqualsHandBuilt() {
    // 通过构建轨道组装。
    Document built =
        DocumentBuilder.start()
            .heading(HeadingLevel.H1, "Title")
            .paragraph(p -> p.addRun("body").bold().fontSize(14))
            .table(t -> t.row(r -> r.cell("A1").cell("B1")).row(r -> r.cell("A2").cell("B2")))
            .build();

    // 通过活动对象 API 手工组装相同的内容。
    Document hand = Docx.create();
    hand.addParagraph().heading(HeadingLevel.H1).addRun("Title");
    hand.addParagraph().addRun("body").bold().fontSize(14);
    Table handTable = hand.addTable();
    Row handRow1 = handTable.addRow();
    handRow1.addCell().text("A1");
    handRow1.addCell().text("B1");
    Row handRow2 = handTable.addRow();
    handRow2.addCell().text("A2");
    handRow2.addCell().text("B2");

    // 结构等价性：相同的正文形状，且每个元素逐块内容相等。
    assertThat(built.bodyElements())
        .as("builder and hand-built documents have the same body element count")
        .hasSize(hand.bodyElements().size());

    assertThat(built.paragraph(0)).as("heading paragraph matches").isEqualTo(hand.paragraph(0));
    assertThat(built.paragraph(1)).as("styled paragraph matches").isEqualTo(hand.paragraph(1));
    assertThat(built.tables().get(0)).as("table matches").isEqualTo(hand.tables().get(0));
  }

  @Test
  void tableBuilderChainsCorrectly() {
    Document doc =
        DocumentBuilder.start()
            .table(t -> t.row(r -> r.cell("A1").cell("B1")).row(r -> r.cell("A2").cell("B2")))
            .build();

    assertThat(doc.tables()).hasSize(1);
    Table table = doc.tables().get(0);
    assertThat(table.rows()).hasSize(2);

    // 2x2 网格往返预期的单元格文本，证明 Table.row + Row.cell(String) 链。
    String[][] expected = {{"A1", "B1"}, {"A2", "B2"}};
    for (int r = 0; r < expected.length; r++) {
      Row row = table.row(r);
      assertThat(row.cells()).as("row %d cell count", r).hasSize(expected[r].length);
      for (int c = 0; c < expected[r].length; c++) {
        assertThat(row.cell(c).text()).as("cell [%d][%d] text", r, c).isEqualTo(expected[r][c]);
      }
    }
  }

  @Test
  void paragraphConsumerAppliesStyles() {
    Document doc =
        DocumentBuilder.start().paragraph(p -> p.addRun("hi").bold().fontSize(14)).build();

    assertThat(doc.paragraphs()).hasSize(1);
    Paragraph paragraph = doc.paragraph(0);
    assertThat(paragraph.runs()).hasSize(1);

    Run run = paragraph.run(0);
    assertThat(run.text()).isEqualTo("hi");
    assertThat(run.isBold()).as("bold applied via consumer").isTrue();
    assertThat(run.fontSize()).as("font size applied via consumer").isEqualTo(14);
  }

  @Test
  void paragraphConsumerSupportsHeadingAndPlainText() {
    Document doc =
        DocumentBuilder.start()
            .heading(HeadingLevel.H1, "Title")
            .paragraph("plain body")
            .paragraph(p -> p.heading(HeadingLevel.H2).addRun("section").italic())
            .build();

    assertThat(doc.paragraphs()).hasSize(3);
    assertThat(doc.paragraph(0).heading()).isEqualTo(HeadingLevel.H1);
    assertThat(doc.paragraph(0).text()).isEqualTo("Title");

    assertThat(doc.paragraph(1).text()).isEqualTo("plain body");

    assertThat(doc.paragraph(2).heading()).isEqualTo(HeadingLevel.H2);
    assertThat(doc.paragraph(2).run(0).text()).isEqualTo("section");
    assertThat(doc.paragraph(2).run(0).isItalic()).isTrue();
  }

  @Test
  void paragraphBuilderChainsRunStyles() {
    Paragraph paragraph = Docx.create().addParagraph();

    // ParagraphBuilder 是薄包装器：text() 返回活动 Run 以支持 run 样式链式调用。
    Run run = ParagraphBuilder.on(paragraph).heading(HeadingLevel.H2).text("Chapter 1").italic();

    assertThat(paragraph.heading()).isEqualTo(HeadingLevel.H2);
    assertThat(run.text()).isEqualTo("Chapter 1");
    assertThat(run.isItalic()).isTrue();
    // Run 包装器按需创建（每次调用一个），因此即使它们包装相同的底层 POI run，也从不
    // 引用相等。通过委托标识替代验证。
    assertThat(run.raw()).isSameAs(paragraph.run(0).raw());
  }

  @Test
  void tableBuilderWrapperChainsRows() {
    Table table = Docx.create().addTable();

    // TableBuilder 委托给活动 Table；row(Consumer) 天然支持链式调用。
    TableBuilder.on(table)
        .row(r -> r.cell("A1").cell("B1"))
        .row(r -> r.cell(c -> c.text("A2")).cell("B2"));

    assertThat(table.rows()).hasSize(2);
    assertThat(table.row(0).cell(0).text()).isEqualTo("A1");
    assertThat(table.row(0).cell(1).text()).isEqualTo("B1");
    assertThat(table.row(1).cell(0).text()).isEqualTo("A2");
    assertThat(table.row(1).cell(1).text()).isEqualTo("B2");
  }
}
