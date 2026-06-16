package com.non.docx.core.api.header;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that section-scoped {@link Header} / {@link Footer} round-trip through save → open, that
 * {@code Document.header()} / {@code Document.footer()} delegate to the first section, and that
 * header / footer content equality is driven by their ordered paragraphs (design §4.4, §7).
 *
 * <p>The MVP exposes the default (odd-page) header / footer only. Multi-section header / footer
 * distinctness is intentionally not asserted here: POI has no clean public API for inserting a
 * mid-body section break, and the design treats section-scoped header/footer primarily via {@code
 * Section.header()} / {@code Section.footer()} on each {@code Section} rather than via a global.
 * Single-section header / footer is exercised thoroughly below.
 */
class HeaderFooterTest {

  @Test
  void headerParagraphRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header.docx");

    Document original = Docx.create();
    original.header().addParagraph().addRun("Header text");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Header header = opened.header();
      assertThat(header.paragraphs()).hasSize(1);
      assertThat(header.paragraph(0).text()).isEqualTo("Header text");
      assertThat(header.text()).contains("Header text");
    }
  }

  @Test
  void footerParagraphRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("footer.docx");

    Document original = Docx.create();
    original.footer().addParagraph().addRun("Footer text");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Footer footer = opened.footer();
      assertThat(footer.paragraphs()).hasSize(1);
      assertThat(footer.paragraph(0).text()).isEqualTo("Footer text");
      assertThat(footer.text()).contains("Footer text");
    }
  }

  @Test
  void documentHeaderIsFirstSectionHeader() {
    Document doc = Docx.create();
    doc.header().addParagraph().addRun("Shared header");

    // Document.header() is a convenience for section(0).header(); both resolve the same content.
    assertThat(doc.header()).isEqualTo(doc.section(0).header());
    assertThat(doc.header().text()).isEqualTo(doc.section(0).header().text());
  }

  @Test
  void documentFooterIsFirstSectionFooter() {
    Document doc = Docx.create();
    doc.footer().addParagraph().addRun("Shared footer");

    assertThat(doc.footer()).isEqualTo(doc.section(0).footer());
    assertThat(doc.footer().text()).isEqualTo(doc.section(0).footer().text());
  }

  @Test
  void headerIsCreateOnce() {
    // First access creates and attaches an empty default header; later calls return that header.
    Document doc = Docx.create();
    Header first = doc.header();
    first.addParagraph().addRun("Persistent");

    Header second = doc.header();
    assertThat(second.paragraphs()).hasSize(1);
    assertThat(second.text()).contains("Persistent");
    assertThat(first).isEqualTo(second);
  }

  @Test
  void footerIsCreateOnce() {
    Document doc = Docx.create();
    Footer first = doc.footer();
    first.addParagraph().addRun("Persistent");

    Footer second = doc.footer();
    assertThat(second.paragraphs()).hasSize(1);
    assertThat(second.text()).contains("Persistent");
    assertThat(first).isEqualTo(second);
  }

  @Test
  void headerContentEquality() {
    Document a = Docx.create();
    a.header().addParagraph().addRun("Title");

    Document b = Docx.create();
    b.header().addParagraph().addRun("Title");

    Document c = Docx.create();
    c.header().addParagraph().addRun("Different");

    // same paragraph content → equal (even though backed by distinct XWPFHeader instances)
    assertThat(a.header()).isEqualTo(b.header());
    assertThat(a.header().hashCode()).isEqualTo(b.header().hashCode());

    // differing content → not equal
    assertThat(a.header()).isNotEqualTo(c.header());
  }

  @Test
  void footerContentEquality() {
    Document a = Docx.create();
    a.footer().addParagraph().addRun("Page 1");

    Document b = Docx.create();
    b.footer().addParagraph().addRun("Page 1");

    Document c = Docx.create();
    c.footer().addParagraph().addRun("Page 2");

    assertThat(a.footer()).isEqualTo(b.footer());
    assertThat(a.footer().hashCode()).isEqualTo(b.footer().hashCode());
    assertThat(a.footer()).isNotEqualTo(c.footer());
  }

  @Test
  void emptyHeaderIsEqualToOtherEmptyHeader() {
    Document a = Docx.create();
    Document b = Docx.create();
    // both headers created but no paragraphs added
    a.header();
    b.header();
    assertThat(a.header()).isEqualTo(b.header());
  }

  @Test
  void headerIsNotEqualToFooter() {
    Document doc = Docx.create();
    doc.header().addParagraph().addRun("Same text");
    doc.footer().addParagraph().addRun("Same text");
    // a header and a footer are different types even with identical paragraph content
    assertThat(doc.header()).isNotEqualTo(doc.footer());
    assertThat(doc.footer()).isNotEqualTo(doc.header());
  }

  @Test
  void headerRoundTripsMultipleParagraphs(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header-multi.docx");

    Document original = Docx.create();
    original.header().addParagraph().addRun("First");
    original.header().addParagraph().addRun("Second");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Header header = opened.header();
      assertThat(header.paragraphs()).hasSize(2);
      assertThat(header.paragraph(0).text()).isEqualTo("First");
      assertThat(header.paragraph(1).text()).isEqualTo("Second");
    }
  }

  @Test
  void headerAndFooterCoexist(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("both.docx");

    Document original = Docx.create();
    original.header().addParagraph().addRun("Top");
    original.footer().addParagraph().addRun("Bottom");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.header().text()).contains("Top");
      assertThat(opened.footer().text()).contains("Bottom");
      assertThat(opened.header()).isNotEqualTo(opened.footer());
    }
  }
}
