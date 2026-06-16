package com.non.docx.core.api.text;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a paragraph's {@code inlineElements()} preserves the true reading order of runs and
 * hyperlinks, that {@code runs()} is the Run-only filtered view (hyperlinks excluded), and that the
 * order survives a round-trip.
 */
class InlineElementOrderTest {

    @Test
    void inlineElementsPreservesRunThenHyperlinkThenRunOrder() {
        Paragraph p = Docx.create().addParagraph();
        p.addRun("before");
        p.addHyperlink("link", "https://example.com/");
        p.addRun("after");

        List<InlineElement> inline = p.inlineElements();
        assertThat(inline).hasSize(3);

        assertThat(inline.get(0)).isInstanceOf(Run.class);
        assertThat(inline.get(1)).isInstanceOf(Hyperlink.class);
        assertThat(inline.get(2)).isInstanceOf(Run.class);

        assertThat(((Run) inline.get(0)).text()).isEqualTo("before");
        assertThat(((Hyperlink) inline.get(1)).text()).isEqualTo("link");
        assertThat(((Run) inline.get(2)).text()).isEqualTo("after");
    }

    @Test
    void runsIsRunOnlyFilteredViewExcludingHyperlinks() {
        Paragraph p = Docx.create().addParagraph();
        p.addRun("a");
        p.addHyperlink("link", "https://example.com/");
        p.addRun("b");

        assertThat(p.runs()).hasSize(2);
        assertThat(p.run(0).text()).isEqualTo("a");
        assertThat(p.run(1).text()).isEqualTo("b");
        assertThat(p.inlineElements()).hasSize(3);
    }

    @Test
    void orderSurvivesRoundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("order.docx");

        Document original = Docx.create();
        Paragraph p = original.addParagraph();
        p.addRun("r1");
        p.addHyperlink("hl", "https://example.com/order");
        p.addRun("r2");
        original.save(file);

        try (Document opened = Docx.open(file)) {
            List<InlineElement> inline = opened.paragraph(0).inlineElements();
            assertThat(inline).hasSize(3);

            assertThat(inline.get(0)).isInstanceOf(Run.class);
            assertThat(((Run) inline.get(0)).text()).isEqualTo("r1");

            assertThat(inline.get(1)).isInstanceOf(Hyperlink.class);
            assertThat(((Hyperlink) inline.get(1)).text()).isEqualTo("hl");
            assertThat(((Hyperlink) inline.get(1)).url()).isEqualTo("https://example.com/order");

            assertThat(inline.get(2)).isInstanceOf(Run.class);
            assertThat(((Run) inline.get(2)).text()).isEqualTo("r2");
        }
    }

    @Test
    void removingHyperlinkByInlineIndexKeepsRunOrderIntact() {
        Paragraph p = Docx.create().addParagraph();
        p.addRun("a");
        p.addHyperlink("link", "https://example.com/");
        p.addRun("b");

        p.removeInlineElement(1); // remove the hyperlink

        assertThat(p.inlineElements()).hasSize(2);
        assertThat(p.runs()).hasSize(2);
        assertThat(p.run(0).text()).isEqualTo("a");
        assertThat(p.run(1).text()).isEqualTo("b");
    }
}
