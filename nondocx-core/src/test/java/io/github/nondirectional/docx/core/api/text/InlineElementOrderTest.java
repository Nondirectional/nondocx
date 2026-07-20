package io.github.nondirectional.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.InlineElement;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证段落的 {@code inlineElements()} 保留 run 和超链接的真实阅读顺序， {@code runs()} 是仅 run 的过滤视图（排除了超链接），
 * 且该顺序在往返后存活。
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

    p.removeInlineElement(1); // 移除超链接

    assertThat(p.inlineElements()).hasSize(2);
    assertThat(p.runs()).hasSize(2);
    assertThat(p.run(0).text()).isEqualTo("a");
    assertThat(p.run(1).text()).isEqualTo("b");
  }
}
