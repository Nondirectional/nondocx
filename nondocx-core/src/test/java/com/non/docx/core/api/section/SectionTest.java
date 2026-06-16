package com.non.docx.core.api.section;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that section page properties (paper size, orientation, margins) round-trip through
 * save → open and that {@link Section} content equality is driven by those page properties (design
 * §4.4, §7).
 */
class SectionTest {

    @Test
    void paperSizeRoundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("paper.docx");

        Document original = Docx.create();
        original.section(0).paperSize(PaperSize.A4);
        original.save(file);

        try (Document opened = Docx.open(file)) {
            assertThat(opened.section(0).paperSize()).isEqualTo(PaperSize.A4);
        }
    }

    @Test
    void paperSizeRecognizesMultipleSizes() {
        for (PaperSize size : PaperSize.values()) {
            Document doc = Docx.create();
            doc.section(0).paperSize(size);
            assertThat(doc.section(0).paperSize())
                    .as("paper size %s should round-trip in-memory", size)
                    .isEqualTo(size);
        }
    }

    @Test
    void orientationRoundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("orientation.docx");

        Document original = Docx.create();
        original.section(0).orientation(Orientation.LANDSCAPE);
        original.save(file);

        try (Document opened = Docx.open(file)) {
            assertThat(opened.section(0).orientation()).isEqualTo(Orientation.LANDSCAPE);
        }

        // switch back to portrait and round-trip again
        try (Document reopened = Docx.open(file)) {
            reopened.section(0).orientation(Orientation.PORTRAIT);
            Path portraitFile = tmp.resolve("orientation-portrait.docx");
            reopened.save(portraitFile);

            try (Document opened = Docx.open(portraitFile)) {
                assertThat(opened.section(0).orientation()).isEqualTo(Orientation.PORTRAIT);
            }
        }
    }

    @Test
    void marginsRoundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("margins.docx");

        Document original = Docx.create();
        original.section(0).margins(1440, 1800, 1440, 1800);
        original.save(file);

        try (Document opened = Docx.open(file)) {
            Section section = opened.section(0);
            assertThat(section.marginTop()).isEqualTo(1440);
            assertThat(section.marginRight()).isEqualTo(1800);
            assertThat(section.marginBottom()).isEqualTo(1440);
            assertThat(section.marginLeft()).isEqualTo(1800);
        }
    }

    @Test
    void defaultSectionAlwaysPresent() {
        Document doc = Docx.create();
        assertThat(doc.sections()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(doc.section(0)).isNotNull();
    }

    @Test
    void contentEqualityByPageProperties() {
        Document a = Docx.create();
        a.section(0).paperSize(PaperSize.A4).orientation(Orientation.PORTRAIT).margins(1000, 1000, 1000, 1000);

        Document b = Docx.create();
        b.section(0).paperSize(PaperSize.A4).orientation(Orientation.PORTRAIT).margins(1000, 1000, 1000, 1000);

        Document c = Docx.create();
        c.section(0).paperSize(PaperSize.A4).orientation(Orientation.LANDSCAPE).margins(1000, 1000, 1000, 1000);

        Document d = Docx.create();
        d.section(0).paperSize(PaperSize.A4).orientation(Orientation.PORTRAIT).margins(2000, 1000, 1000, 1000);

        // same page properties → equal (even though backed by distinct CTSectPr instances)
        assertThat(a.section(0)).isEqualTo(b.section(0));
        assertThat(a.section(0).hashCode()).isEqualTo(b.section(0).hashCode());

        // differing orientation → not equal
        assertThat(a.section(0)).isNotEqualTo(c.section(0));

        // differing margins → not equal
        assertThat(a.section(0)).isNotEqualTo(d.section(0));
    }

    @Test
    void sectionsIsLiveViewAcrossFreshWrappers() {
        Document doc = Docx.create();
        doc.section(0).paperSize(PaperSize.LETTER);

        // each sections().get(0) returns a fresh wrapper, but it reads the same live state
        assertThat(doc.sections().get(0).paperSize()).isEqualTo(PaperSize.LETTER);
        assertThat(doc.section(0).paperSize()).isEqualTo(PaperSize.LETTER);
    }
}
