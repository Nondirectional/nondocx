package io.github.nondirectional.docx.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.exception.DocxFormatException;
import io.github.nondirectional.docx.core.api.exception.DocxIOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@link Docx} 工厂和 {@link Document} 打开/保存往返的基本功能。
 *
 * <p>阶段 2 冒烟测试：创建→保存→打开不抛出异常，添加的段落能在往返后存活， 且错误映射能区分缺失文件（IO）与无效字节（格式）。
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
