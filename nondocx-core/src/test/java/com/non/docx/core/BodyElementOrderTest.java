package com.non.docx.core;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the document body preserves the true interleaved order of paragraphs and tables,
 * that {@code paragraphs()} / {@code tables()} are consistent filtered views of that order, and that
 * the order survives a round trip. This guards the {@code BodyElement} ordering contract (design §3.1).
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

        // in-memory order
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

        // filtered views are consistent projections of the same order
        assertThat(doc.paragraphs())
                .as("paragraphs() is the Paragraph-only filtered view")
                .hasSize(2);
        assertThat(doc.tables())
                .as("tables() is the Table-only filtered view")
                .hasSize(1);

        // the filtered paragraphs are exactly the two body paragraphs, in order
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
        // expected type sequence: P, T, P, P, T (in call order)
        assertThat(body.get(0)).isInstanceOf(Paragraph.class);
        assertThat(body.get(1)).isInstanceOf(Table.class);
        assertThat(body.get(2)).isInstanceOf(Paragraph.class);
        assertThat(body.get(3)).isInstanceOf(Paragraph.class);
        assertThat(body.get(4)).isInstanceOf(Table.class);

        assertThat(doc.paragraphs()).hasSize(3);   // a, b, c
        assertThat(doc.tables()).hasSize(2);

        // the filtered paragraphs preserve their body order, not a re-sort
        assertThat(doc.paragraph(0).text()).isEqualTo("a");
        assertThat(doc.paragraph(1).text()).isEqualTo("b");
        assertThat(doc.paragraph(2).text()).isEqualTo("c");
    }
}
