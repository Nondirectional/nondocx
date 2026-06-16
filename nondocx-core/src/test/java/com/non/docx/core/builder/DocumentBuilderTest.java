package com.non.docx.core.builder;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the construction track ({@link DocumentBuilder}, {@link ParagraphBuilder},
 * {@link TableBuilder}) and the live-object lambda conveniences ({@link Table#row},
 * {@link Row#cell(String)}, {@link Row#cell(java.util.function.Consumer)}).
 *
 * <p>The core assertion strategy: {@code Document} does not yet implement {@code equals} (that
 * arrives in Phase 7 with {@code RoundTripTest}), so builder-vs-hand-built equivalence is asserted
 * structurally — the body element count matches, and each paragraph ({@link Paragraph#equals}) and
 * table ({@link Table#equals}) is content-equal to its hand-built counterpart. This proves the
 * builder adds, drops, or alters nothing relative to direct use of the live-object API.
 */
class DocumentBuilderTest {

    @Test
    void builderDocumentEqualsHandBuilt() {
        // Assemble via the builder track.
        Document built = DocumentBuilder.start()
                .heading(HeadingLevel.H1, "Title")
                .paragraph(p -> p.addRun("body").bold().fontSize(14))
                .table(t -> t.row(r -> r.cell("A1").cell("B1"))
                        .row(r -> r.cell("A2").cell("B2")))
                .build();

        // Assemble the identical content by hand via the live-object API.
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

        // Structural equivalence: same body shape, and each element content-equal piece by piece.
        assertThat(built.bodyElements())
                .as("builder and hand-built documents have the same body element count")
                .hasSize(hand.bodyElements().size());

        assertThat(built.paragraph(0))
                .as("heading paragraph matches")
                .isEqualTo(hand.paragraph(0));
        assertThat(built.paragraph(1))
                .as("styled paragraph matches")
                .isEqualTo(hand.paragraph(1));
        assertThat(built.tables().get(0))
                .as("table matches")
                .isEqualTo(hand.tables().get(0));
    }

    @Test
    void tableBuilderChainsCorrectly() {
        Document doc = DocumentBuilder.start()
                .table(t -> t.row(r -> r.cell("A1").cell("B1"))
                        .row(r -> r.cell("A2").cell("B2")))
                .build();

        assertThat(doc.tables()).hasSize(1);
        Table table = doc.tables().get(0);
        assertThat(table.rows()).hasSize(2);

        // 2x2 grid round-trips the expected cell text, proving Table.row + Row.cell(String) chain.
        String[][] expected = {{"A1", "B1"}, {"A2", "B2"}};
        for (int r = 0; r < expected.length; r++) {
            Row row = table.row(r);
            assertThat(row.cells()).as("row %d cell count", r).hasSize(expected[r].length);
            for (int c = 0; c < expected[r].length; c++) {
                assertThat(row.cell(c).text())
                        .as("cell [%d][%d] text", r, c)
                        .isEqualTo(expected[r][c]);
            }
        }
    }

    @Test
    void paragraphConsumerAppliesStyles() {
        Document doc = DocumentBuilder.start()
                .paragraph(p -> p.addRun("hi").bold().fontSize(14))
                .build();

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
        Document doc = DocumentBuilder.start()
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

        // ParagraphBuilder is a thin wrapper: text() returns the live Run for run-style chaining.
        Run run = ParagraphBuilder.on(paragraph)
                .heading(HeadingLevel.H2)
                .text("Chapter 1")
                .italic();

        assertThat(paragraph.heading()).isEqualTo(HeadingLevel.H2);
        assertThat(run.text()).isEqualTo("Chapter 1");
        assertThat(run.isItalic()).isTrue();
        // Run wrappers are created on demand (one per call), so they are never reference-equal even
        // when they wrap the same underlying POI run. Verify by delegate identity instead.
        assertThat(run.raw()).isSameAs(paragraph.run(0).raw());
    }

    @Test
    void tableBuilderWrapperChainsRows() {
        Table table = Docx.create().addTable();

        // TableBuilder delegates to the live Table; row(Consumer) chains naturally.
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
