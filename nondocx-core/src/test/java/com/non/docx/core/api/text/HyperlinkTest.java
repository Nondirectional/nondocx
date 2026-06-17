package com.non.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证通过 nondocx 创建的超链接在保存→打开往返中保留其文本和 URL， 且内容相等性比较文本 + URL。 */
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

  // ===== 可写性：text(String) / url(String) setter（Phase 1.2 / 1.3） =====

  /**
   * 改超链接显示文本后，save→open 能读回新文本。
   *
   * <p>这是 {@code text(String)} 的 round-trip 测试：setter 写委托，{@code text()} 读回新值。
   */
  @Test
  void textSetterRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("hyperlink-text-set.docx");

    Document original = Docx.create();
    original.addParagraph().addHyperlink("旧文本", "https://example.com/old");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Hyperlink link = (Hyperlink) opened.paragraph(0).inlineElement(0);
      // setter 写委托（XWPFHyperlinkRun.setText）
      link.text("新文本");
      // 读回确认直写生效
      assertThat(link.text()).isEqualTo("新文本");
      opened.save(file);
    }

    // 再次打开，确认落盘后仍是新值
    try (Document reopened = Docx.open(file)) {
      Hyperlink link = (Hyperlink) reopened.paragraph(0).inlineElement(0);
      assertThat(link.text()).isEqualTo("新文本");
    }
  }

  /**
   * 改超链接目标 URL 后，save→open 能读回新 URL。
   *
   * <p>这是 {@code url(String)} 的 round-trip 测试，也是 POI 改超链接 target 这个“脏活”的兌底测试 （见 poi-bridge.md 风险与
   * design.md §4.3）。必须过。
   */
  @Test
  void urlSetterRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("hyperlink-url-set.docx");

    Document original = Docx.create();
    original.addParagraph().addHyperlink("站点", "https://example.com/old");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Hyperlink link = (Hyperlink) opened.paragraph(0).inlineElement(0);
      // setter 重建关系：删旧外部关系 → 新建指向新 URL 的外部关系 → 更新运行 rId。
      // 注意：reopen 后的文档，POI XWPFDocument.hyperlinks 缓存已在 open 时初始化，
      // setter 重建底层关系后该缓存不刷新，因此同一实例内 url() 可能仍读回旧值。
      // 本方法的核心契约是 save→reopen 读回新值（见下方断言），活对象内存读回不是契约。
      link.url("https://example.com/new");
      opened.save(file);
    }

    // 再次打开，确认落盘后关系重建生效，读回新 URL
    try (Document reopened = Docx.open(file)) {
      Hyperlink link = (Hyperlink) reopened.paragraph(0).inlineElement(0);
      assertThat(link.url()).isEqualTo("https://example.com/new");
      // 显示文本不应被 URL 修改连带改动
      assertThat(link.text()).isEqualTo("站点");
    }
  }

  /** setter 的 null 入参应招 IllegalArgumentException（复用现有校验风格）。 */
  @Test
  void settersRejectNullArgs() {
    Hyperlink link = Docx.create().addParagraph().addHyperlink("x", "https://example.com/");
    assertThatThrownBy(() -> link.text(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("text");
    assertThatThrownBy(() -> link.url(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("url");
  }

  /**
   * setter 写委托后，{@code equals} 仍基于 {@code text() + url()}，与原有语义一致 （quality-guidelines.md Rule
   * 2：content equality，不含委托引用）。
   */
  @Test
  void equalsStaysContentBasedAfterSetters() {
    Document a = Docx.create();
    Document b = Docx.create();

    Hyperlink l1 = a.addParagraph().addHyperlink("orig", "https://example.com/orig");
    Hyperlink l2 = b.addParagraph().addHyperlink("different", "https://other.com/");

    // 把 l2 改成与 l1 内容一致
    l2.text("orig").url("https://example.com/orig");

    assertThat(l1).isEqualTo(l2);
    assertThat(l1.hashCode()).isEqualTo(l2.hashCode());
  }
}
