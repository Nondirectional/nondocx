package com.non.docx.core.api.header;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
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

    // Document.header() 是 section(0).header() 的便捷方法；两者解析相同的内容。
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
    // 首次访问创建并附加一个空的默认页眉；后续调用返回该页眉。
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

    // 相同的段落内容→相等（即使由不同的 XWPFHeader 实例支持）
    assertThat(a.header()).isEqualTo(b.header());
    assertThat(a.header().hashCode()).isEqualTo(b.header().hashCode());

    // 不同的内容→不相等
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
    // 两个页眉都已创建，但未添加段落
    a.header();
    b.header();
    assertThat(a.header()).isEqualTo(b.header());
  }

  @Test
  void headerIsNotEqualToFooter() {
    Document doc = Docx.create();
    doc.header().addParagraph().addRun("Same text");
    doc.footer().addParagraph().addRun("Same text");
    // 页眉和页脚是不同的类型，即使段落内容相同
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
