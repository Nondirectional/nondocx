package com.non.docx.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.exception.DocxFormatException;
import com.non.docx.core.api.exception.DocxIOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the {@link Docx} factory and {@link Document} open / save round-trip basics.
 *
 * <p>Phase 2 smoke tests: create → save → open does not throw, an added paragraph survives the
 * round trip, and error mapping distinguishes missing files (IO) from invalid bytes (format).
 */
class DocumentOpenSaveTest {

  @Test
  void createSaveOpenRoundTripsEmptyDoc(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("empty.docx");

    Document original = Docx.create();
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.bodyElements()).isEmpty();
    }
  }

  @Test
  void addParagraphPersistsAcrossRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("hello.docx");

    Document original = Docx.create();
    original.addParagraph("Hello");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraphs()).hasSize(1);
      assertThat(opened.paragraph(0).text()).isEqualTo("Hello");
    }
  }

  @Test
  void openInvalidBytesThrowsFormatException() {
    assertThatThrownBy(() -> Docx.open(new ByteArrayInputStream("not a docx".getBytes())))
        .isInstanceOf(DocxFormatException.class)
        .hasMessageContaining("Not a valid docx file");
  }

  @Test
  void openMissingFileThrowsIOException(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.docx");
    assertThatThrownBy(() -> Docx.open(missing))
        .isInstanceOf(DocxIOException.class)
        .hasMessageContaining("Failed to open document");
  }
}
