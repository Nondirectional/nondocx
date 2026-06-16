package com.non.docx.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
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
 * Cross-reference check: build a document with the nondocx API, then read the same file back with a
 * <em>raw</em> {@code XWPFDocument} and assert that nondocx's writes and reads agree with POI's own
 * native extraction. This guards against the "self-testing self" blind spot of a POI wrapper.
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

    // Read the same bytes with raw POI — independent of our wrappers.
    List<XWPFParagraph> poiParas;
    try (XWPFDocument poi = new XWPFDocument(Files.newInputStream(file))) {
      poiParas = poi.getParagraphs();

      // heading written by nondocx is the heading style id POI reads back
      assertThat(poiParas.get(0).getStyle()).isEqualTo("Heading1");
      assertThat(poiParas.get(0).getText()).isEqualTo("Chapter One");

      // inline style + alignment written by nondocx match POI's native extraction
      assertThat(poiParas.get(1).getRuns().get(0).isBold()).isTrue();
      assertThat(poiParas.get(1).getAlignment()).isEqualTo(ParagraphAlignment.CENTER);

      // hyperlink URL written by nondocx resolves through POI's relationship part
      XWPFHyperlinkRun poiLink = (XWPFHyperlinkRun) poiParas.get(2).getIRuns().get(0);
      assertThat(poiLink.getHyperlink(poi).getURL()).isEqualTo("https://example.com/cross");
    }

    // Now read via nondocx and assert the wrapper agrees with POI on the same fields.
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
    // For each paragraph, nondocx's concatenated text must equal what raw POI extracts from the
    // same file — an independent confirmation that our read path matches POI's.
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
      // multi-run paragraph concatenation matches POI
      assertThat(opened.paragraph(1).text()).isEqualTo("plain tail");
    }
  }

  @Test
  void nondocxTableMatchesPoiNativeTable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cross-table.docx");

    Document original = Docx.create();
    Table table = original.addTable();
    table.addRow(); // row 0
    table.row(0).addCell().text("A1");
    table.row(0).addCell().text("B1");
    table.addRow(); // row 1
    table.row(1).addCell().text("A2");
    table.row(1).addCell().text("B2");
    original.save(file);

    // Read the same bytes with raw POI — independent of our wrappers.
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

    // Our wrapper must agree with POI's native extraction on the same fields.
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
