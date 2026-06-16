package com.non.docx.core.api.text;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies that {@code Paragraph.lineSpacing(double)} round-trips through save → open.
 */
class ParagraphSpacingTest {

    @Test
    void lineSpacingRoundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("spacing.docx");

        Document original = Docx.create();
        Paragraph p = original.addParagraph();
        p.lineSpacing(1.5);
        p.addRun("lines");
        original.save(file);

        try (Document opened = Docx.open(file)) {
            assertThat(opened.paragraph(0).lineSpacing())
                    .isCloseTo(1.5, within(1e-9));
        }
    }

    @Test
    void lineSpacingDefaultsToUnsetSentinel() {
        Paragraph p = Docx.create().addParagraph();
        // POI reports -1.0 when line spacing is not explicitly set; documented in the getter Javadoc.
        assertThat(p.lineSpacing()).isEqualTo(-1.0);
    }

    @Test
    void lineSpacingDoubleSurvivesRoundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("spacing2.docx");

        Document original = Docx.create();
        Paragraph p = original.addParagraph();
        p.lineSpacing(2.0);
        p.addRun("lines");
        original.save(file);

        try (Document opened = Docx.open(file)) {
            assertThat(opened.paragraph(0).lineSpacing()).isCloseTo(2.0, within(1e-9));
        }
    }
}
