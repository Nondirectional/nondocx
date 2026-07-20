package io.github.nondirectional.docx.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Hyperlink;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 交叉引用检查：使用 nondocx API 构建文档，然后用原始 {@code XWPFDocument} 读取同一文件， 并断言 nondocx 的写入和读取与 POI
 * 自身的原生提取一致。这验证了 nondocx 的 POI 包装器不引入层间不匹配。
 */
class PoiCrossReferenceTest {

  @Test
  void nondocxWritesMatchPoiNativeReads(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cross-ref.docx");

    Document original = Docx.create();
    original.addParagraph().heading(HeadingLevel.H1).addRun("Chapter One");
    Paragraph styled = original.addParagraph().alignment(Alignment.CENTER);
    styled.addRun("centered bold").bold();
    original.addParagraph().addHyperlink("site", "https://example.com/cross");
    original.save(file);

    // 用原始 POI 读取同一字节——独立于我们的包装器。
    List<XWPFParagraph> poiParas;
    try (XWPFDocument poi = new XWPFDocument(Files.newInputStream(file))) {
      poiParas = poi.getParagraphs();

      // nondocx 写入的标题是 POI 读回的标题样式 ID
      assertThat(poiParas.get(0).getStyle()).isEqualTo("Heading1");
      assertThat(poiParas.get(0).getText()).isEqualTo("Chapter One");

      // nondocx 写入的内联样式 + 对齐方式与 POI 的原生提取一致
      assertThat(poiParas.get(1).getRuns().get(0).isBold()).isTrue();
      assertThat(poiParas.get(1).getAlignment()).isEqualTo(ParagraphAlignment.CENTER);

      // nondocx 写入的超链接 URL 通过 POI 的关系部分解析
      XWPFHyperlinkRun poiLink = (XWPFHyperlinkRun) poiParas.get(2).getIRuns().get(0);
      assertThat(poiLink.getHyperlink(poi).getURL()).isEqualTo("https://example.com/cross");
    }

    // 现在通过 nondocx 读取，并断言包装器在与 POI 相同的字段上一致。
    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraph(0).heading()).isEqualTo(HeadingLevel.H1);
      assertThat(opened.paragraph(0).text()).isEqualTo(poiParas.get(0).getText());

      assertThat(opened.paragraph(1).run(0).isBold())
          .isEqualTo(poiParas.get(1).getRuns().get(0).isBold());
      assertThat(opened.paragraph(1).alignment()).isEqualTo(Alignment.CENTER);

      Hyperlink link = (Hyperlink) opened.paragraph(2).inlineElement(0);
      assertThat(link.url()).isEqualTo("https://example.com/cross");
    }
  }

  @Test
  void nondocxReadMatchesPoiNativeTextPerParagraph(@TempDir Path tmp) throws Exception {
    // 对于每个段落，nondocx 的拼接文本必须等于原始 POI 从同一文件中提取的内容——
    // 这是对我们的读取路径与 POI 一致的独立确认。
    Path file = tmp.resolve("cross-read.docx");

    Document original = Docx.create();
    original.addParagraph().heading(HeadingLevel.H2).addRun("A title");
    Paragraph body = original.addParagraph();
    body.addRun("plain ");
    body.addRun("tail");
    original.save(file);

    List<XWPFParagraph> poiParas;
    try (InputStream in = Files.newInputStream(file);
        XWPFDocument poi = new XWPFDocument(in)) {
      poiParas = poi.getParagraphs();
    }

    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraphs()).hasSameSizeAs(poiParas);
      for (int i = 0; i < poiParas.size(); i++) {
        assertThat(opened.paragraph(i).text())
            .as("paragraph %d text matches POI native", i)
            .isEqualTo(poiParas.get(i).getText());
      }
      // 多 run 段落拼接与 POI 一致
      assertThat(opened.paragraph(1).text()).isEqualTo("plain tail");
    }
  }

  @Test
  void nondocxTableMatchesPoiNativeTable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cross-table.docx");

    Document original = Docx.create();
    Table table = original.addTable();
    table.addRow(); // 第 0 行
    table.row(0).addCell().text("A1");
    table.row(0).addCell().text("B1");
    table.addRow(); // 第 1 行
    table.row(1).addCell().text("A2");
    table.row(1).addCell().text("B2");
    original.save(file);

    // 用原始 POI 读取同一字节——独立于我们的包装器。
    String p00, p01, p10, p11;
    try (XWPFDocument poi = new XWPFDocument(Files.newInputStream(file))) {
      XWPFTable poiTable = poi.getTables().get(0);
      assertThat(poiTable.getRows()).hasSize(2);
      assertThat(poiTable.getRow(0).getTableCells()).hasSize(2);
      p00 = poiTable.getRow(0).getCell(0).getText();
      p01 = poiTable.getRow(0).getCell(1).getText();
      p10 = poiTable.getRow(1).getCell(0).getText();
      p11 = poiTable.getRow(1).getCell(1).getText();
    }

    // 我们的包装器必须与 POI 在同一字段上的原生提取一致。
    try (Document opened = Docx.open(file)) {
      Table our = opened.tables().get(0);
      assertThat(our.rows()).hasSize(2);
      assertThat(our.row(0).cells()).hasSize(2);
      assertThat(our.row(0).cell(0).text()).isEqualTo(p00).isEqualTo("A1");
      assertThat(our.row(0).cell(1).text()).isEqualTo(p01).isEqualTo("B1");
      assertThat(our.row(1).cell(0).text()).isEqualTo(p10).isEqualTo("A2");
      assertThat(our.row(1).cell(1).text()).isEqualTo(p11).isEqualTo("B2");
    }
  }
}
