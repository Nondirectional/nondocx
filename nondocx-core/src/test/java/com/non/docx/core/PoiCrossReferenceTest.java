package com.non.docx.core;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
