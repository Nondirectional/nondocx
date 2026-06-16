package com.non.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that hyperlinks created via nondocx preserve their text and URL across a save → open
 * round-trip, and that content equality compares text + URL.
 */
class HyperlinkTest {

  @Test
  void textAndUrlSurviveRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("hyperlink.docx");

    Document original = Docx.create();
    original.addParagraph().addHyperlink("Apache POI", "https://poi.apache.org/");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.paragraph(0).inlineElements()).hasSize(1);
      InlineElement element = opened.paragraph(0).inlineElement(0);

      assertThat(element).isInstanceOf(Hyperlink.class);
      Hyperlink link = (Hyperlink) element;
      assertThat(link.text()).isEqualTo("Apache POI");
      assertThat(link.url()).isEqualTo("https://poi.apache.org/");
    }
  }

  @Test
  void urlResolvesImmediatelyAfterCreation() {
    Document doc = Docx.create();
    Hyperlink link = doc.addParagraph().addHyperlink("click", "https://example.com/page");

    assertThat(link.text()).isEqualTo("click");
    assertThat(link.url()).isEqualTo("https://example.com/page");
  }

  @Test
  void addHyperlinkRejectsNullArgs() {
    Paragraph p = Docx.create().addParagraph();
    assertThatThrownBy(() -> p.addHyperlink(null, "https://example.com"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("text");
    assertThatThrownBy(() -> p.addHyperlink("x", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("url");
  }

  @Test
  void equalsByContentComparesTextAndUrl() {
    Document a = Docx.create();
    Document b = Docx.create();

    Hyperlink l1 = a.addParagraph().addHyperlink("site", "https://example.com/");
    Hyperlink l2 = b.addParagraph().addHyperlink("site", "https://example.com/");
    Hyperlink l3 = b.addParagraph().addHyperlink("site", "https://other.com/");
    Hyperlink l4 = b.addParagraph().addHyperlink("other", "https://example.com/");

    assertThat(l1).isEqualTo(l2);
    assertThat(l1.hashCode()).isEqualTo(l2.hashCode());
    assertThat(l1).isNotEqualTo(l3);
    assertThat(l1).isNotEqualTo(l4);
    assertThat(l1).isNotEqualTo(null);
  }

  @Test
  void rawReturnsSameDelegateInstance() {
    Hyperlink link = Docx.create().addParagraph().addHyperlink("x", "https://example.com/");
    assertThat(link.raw()).isSameAs(link.raw());
  }
}
