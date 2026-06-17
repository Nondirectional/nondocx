package com.non.docx.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.toc.TableOfContents;
import com.non.docx.core.api.toc.TocEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code sample-toc-input.docx} 的结构自检——确保它含一个解析正常的目录,且条目结构与生成器输出一致。
 *
 * <p>若样例被重新生成({@code SampleTocDocGenerator})、结构漂移,本测试会捕获。同时它也是 {@code read_toc} 工具所依赖的 core 解析能力在
 * examples 侧的端到端回归。
 */
final class SampleTocDocStructureTest {

  // surefire 工作目录是模块根(nondocx-examples/),故路径相对模块根。
  private static final Path SAMPLE = Path.of("src/main/resources/document/sample-toc-input.docx");

  @Test
  void sampleTocDocumentHasExpectedStructure() throws Exception {
    assertThat(Files.exists(SAMPLE)).as("TOC 样例文档已入库: %s", SAMPLE).isTrue();

    try (Document doc = Docx.open(SAMPLE)) {
      TableOfContents toc = doc.toc();
      assertThat(toc).as("样例文档应含目录(TOC 域)").isNotNull();

      // 生成器写入的 6 条两级标题
      assertThat(toc.entries())
          .containsExactly(
              new TocEntry("一、项目概述", 1, "1", "_Toc200001"),
              new TocEntry("1.1 背景", 2, "1", "_Toc200002"),
              new TocEntry("1.2 目标", 2, "2", "_Toc200003"),
              new TocEntry("二、技术方案", 1, "3", "_Toc200004"),
              new TocEntry("2.1 架构", 2, "3", "_Toc200005"),
              new TocEntry("三、总结", 1, "5", "_Toc200006"));
    }
  }
}
