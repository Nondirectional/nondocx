package io.github.nondirectional.docx.core.api.header;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证节作用域的 {@link Header} / {@link Footer} 在保存→打开往返中存活， 且 {@code Document.header()} / {@code
 * Document.footer()} 委托给第一个节， 以及页眉/页脚内容相等性由其有序段落驱动（设计文档 §4.4、§7）。
 *
 * <p>MVP 仅暴露默认（奇数页）页眉/页脚。有意不在此处断言多节页眉/页脚 的区分性：POI 没有干净的公 共 API 用于在正文中间插入分节符， 设计文档主要通过每个 {@code
 * Section} 上的 {@code Section.header()} / {@code Section.footer()} 而非全局方式处理节作用域的页眉/页脚。
 * 下面充分测试了单节页眉/页脚。
 */
class HeaderFooterTest {

  @Test
  void headerParagraphRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header.docx");

    Document original = Docx.create();
    original.ensureHeader().addParagraph().addRun("Header text");
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
    original.ensureFooter().addParagraph().addRun("Footer text");
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
    doc.ensureHeader().addParagraph().addRun("Shared header");

    // Document.header() 是 section(0).header() 的便捷方法；两者解析相同的内容。
    assertThat(doc.header()).isEqualTo(doc.section(0).header());
    assertThat(doc.header().text()).isEqualTo(doc.section(0).header().text());
  }

  @Test
  void documentFooterIsFirstSectionFooter() {
    Document doc = Docx.create();
    doc.ensureFooter().addParagraph().addRun("Shared footer");

    assertThat(doc.footer()).isEqualTo(doc.section(0).footer());
    assertThat(doc.footer().text()).isEqualTo(doc.section(0).footer().text());
  }

  @Test
  void headerIsCreateOnce() {
    // 首次 ensureHeader 创建并附加一个空的默认页眉；后续调用返回同一个页眉。
    Document doc = Docx.create();
    Header first = doc.ensureHeader();
    first.addParagraph().addRun("Persistent");

    Header second = doc.header();
    assertThat(second.paragraphs()).hasSize(1);
    assertThat(second.text()).contains("Persistent");
    assertThat(first).isEqualTo(second);
  }

  @Test
  void footerIsCreateOnce() {
    Document doc = Docx.create();
    Footer first = doc.ensureFooter();
    first.addParagraph().addRun("Persistent");

    Footer second = doc.footer();
    assertThat(second.paragraphs()).hasSize(1);
    assertThat(second.text()).contains("Persistent");
    assertThat(first).isEqualTo(second);
  }

  @Test
  void headerContentEquality() {
    Document a = Docx.create();
    a.ensureHeader().addParagraph().addRun("Title");

    Document b = Docx.create();
    b.ensureHeader().addParagraph().addRun("Title");

    Document c = Docx.create();
    c.ensureHeader().addParagraph().addRun("Different");

    // 相同的段落内容→相等（即使由不同的 XWPFHeader 实例支持）
    assertThat(a.header()).isEqualTo(b.header());
    assertThat(a.header().hashCode()).isEqualTo(b.header().hashCode());

    // 不同的内容→不相等
    assertThat(a.header()).isNotEqualTo(c.header());
  }

  @Test
  void footerContentEquality() {
    Document a = Docx.create();
    a.ensureFooter().addParagraph().addRun("Page 1");

    Document b = Docx.create();
    b.ensureFooter().addParagraph().addRun("Page 1");

    Document c = Docx.create();
    c.ensureFooter().addParagraph().addRun("Page 2");

    assertThat(a.footer()).isEqualTo(b.footer());
    assertThat(a.footer().hashCode()).isEqualTo(b.footer().hashCode());
    assertThat(a.footer()).isNotEqualTo(c.footer());
  }

  @Test
  void emptyHeaderIsEqualToOtherEmptyHeader() {
    Document a = Docx.create();
    Document b = Docx.create();
    // 两个页眉都已显式创建，但未添加段落——读+写分离后用 ensureHeader() 创建空页眉。
    a.ensureHeader();
    b.ensureHeader();
    assertThat(a.header()).isEqualTo(b.header());
  }

  @Test
  void headerIsNullWhenAbsent() {
    // 读写分离契约：未创建页眉时 header() 返回 null（Document 便捷方法同 Section）。
    assertThat(Docx.create().header()).isNull();
    assertThat(Docx.create().footer()).isNull();
  }

  @Test
  void headerIsNotEqualToFooter() {
    Document doc = Docx.create();
    doc.ensureHeader().addParagraph().addRun("Same text");
    doc.ensureFooter().addParagraph().addRun("Same text");
    // 页眉和页脚是不同的类型，即使段落内容相同
    assertThat(doc.header()).isNotEqualTo(doc.footer());
    assertThat(doc.footer()).isNotEqualTo(doc.header());
  }

  @Test
  void headerRoundTripsMultipleParagraphs(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header-multi.docx");

    Document original = Docx.create();
    original.ensureHeader().addParagraph().addRun("First");
    original.ensureHeader().addParagraph().addRun("Second");
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
    original.ensureHeader().addParagraph().addRun("Top");
    original.ensureFooter().addParagraph().addRun("Bottom");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.header().text()).contains("Top");
      assertThat(opened.footer().text()).contains("Bottom");
      assertThat(opened.header()).isNotEqualTo(opened.footer());
    }
  }

  // ---------- 变体（首页 / 偶数页） ----------

  @Test
  void firstHeaderRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("first-header.docx");

    Document original = Docx.create();
    original.ensureHeader(HeaderFooterVariant.FIRST).addParagraph().addRun("封面页眉");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Header first = opened.header(HeaderFooterVariant.FIRST);
      assertThat(first).isNotNull();
      assertThat(first.text()).contains("封面页眉");
    }
  }

  @Test
  void evenFooterRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("even-footer.docx");

    Document original = Docx.create();
    original.ensureFooter(HeaderFooterVariant.EVEN).addParagraph().addRun("even footer");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Footer even = opened.footer(HeaderFooterVariant.EVEN);
      assertThat(even).isNotNull();
      assertThat(even.text()).contains("even footer");
    }
  }

  @Test
  void firstHeaderWritesTitlePgFlag() {
    // POI 的 createHeader(FIRST) 不自动写 titlePg；nondocx 的 ensureHeader(FIRST) 必须补。
    Document doc = Docx.create();
    doc.ensureHeader(HeaderFooterVariant.FIRST);

    assertThat(doc.section(0).raw().isSetTitlePg()).isTrue();
  }

  @Test
  void evenHeaderWritesSettingsFlag() {
    // POI 的 createHeader(EVEN) 不自动写 evenAndOddHeaders；nondocx 的 ensureHeader(EVEN) 必须补。
    Document doc = Docx.create();
    doc.ensureHeader(HeaderFooterVariant.EVEN);

    assertThat(doc.raw().getSettings().getCTSettings().isSetEvenAndOddHeaders()).isTrue();
  }

  @Test
  void threeVariantsCoexist(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("three.docx");

    Document original = Docx.create();
    original.ensureHeader().addParagraph().addRun("默认页眉");
    original.ensureHeader(HeaderFooterVariant.FIRST).addParagraph().addRun("首页页眉");
    original.ensureHeader(HeaderFooterVariant.EVEN).addParagraph().addRun("偶数页眉");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.header().text()).contains("默认页眉");
      assertThat(opened.header(HeaderFooterVariant.FIRST).text()).contains("首页页眉");
      assertThat(opened.header(HeaderFooterVariant.EVEN).text()).contains("偶数页眉");
    }
  }

  @Test
  void firstHeaderIsCreateOnce() {
    // 重复 ensure 同一变体返回同一 part（内容追加而非新建）。
    Document doc = Docx.create();
    Header first = doc.ensureHeader(HeaderFooterVariant.FIRST);
    first.addParagraph().addRun("第一段");

    Header second = doc.ensureHeader(HeaderFooterVariant.FIRST);
    second.addParagraph().addRun("第二段");

    Header read = doc.header(HeaderFooterVariant.FIRST);
    assertThat(read.paragraphs()).hasSize(2);
    assertThat(read.text()).contains("第一段");
    assertThat(read.text()).contains("第二段");
  }

  @Test
  void noArgHeaderEqualsDefaultVariant() {
    // 向后兼容：无参版本 == DEFAULT 变体。
    Document doc = Docx.create();
    doc.ensureHeader().addParagraph().addRun("同一内容");

    assertThat(doc.header()).isEqualTo(doc.header(HeaderFooterVariant.DEFAULT));
    assertThat(doc.header().text()).isEqualTo(doc.header(HeaderFooterVariant.DEFAULT).text());
  }

  @Test
  void variantHeaderIsNullWhenAbsent() {
    // 读写分离契约：未创建的变体 header(variant) 返回 null。
    assertThat(Docx.create().header(HeaderFooterVariant.FIRST)).isNull();
    assertThat(Docx.create().header(HeaderFooterVariant.EVEN)).isNull();
    assertThat(Docx.create().footer(HeaderFooterVariant.FIRST)).isNull();
    assertThat(Docx.create().footer(HeaderFooterVariant.EVEN)).isNull();
  }

  @Test
  void sectionEqualsIncludesVariants(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("equals.docx");

    Document original = Docx.create();
    original.ensureHeader(HeaderFooterVariant.FIRST).addParagraph().addRun("首页");
    original.ensureFooter(HeaderFooterVariant.EVEN).addParagraph().addRun("偶数");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      // 含变体的 section 在 round-trip 后内容相等（equals/hashCode 扩展生效）。
      assertThat(opened.section(0)).isEqualTo(original.section(0));
    }
  }
}
