package com.non.docx.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 样例输入文档结构自检（implement.md Phase 3.3）。
 *
 * <p>确保 {@code sample-agent-input.docx} 的结构与 design.md §5 的约束一致—— 这也是 {@code DocxAgentExample} 里预置给
 * Agent 的索引的前提。若样例被重新生成，本测试会捕获结构漂移。
 */
final class SampleDocStructureTest {

  // surefire 的工作目录是模块根（nondocx-examples/），故路径相对模块根。
  private static final Path SAMPLE = Path.of("src/main/resources/document/sample-agent-input.docx");

  @Test
  void sampleDocumentHasExpectedStructure() throws Exception {
    assertThat(Files.exists(SAMPLE)).as("样例文档已入库: %s", SAMPLE).isTrue();

    try (Document doc = Docx.open(SAMPLE)) {
      // 正文段落：标题、多 run 段、超链接段、普通段 = 4 段
      assertThat(doc.paragraphs()).hasSize(4);

      // 段落 1 含至少 2 个 run（演示 replace_run_text）
      Paragraph multiRunPara = doc.paragraph(1);
      assertThat(multiRunPara.runs().size()).isGreaterThanOrEqualTo(2);

      // 存在一个含超链接的段落（演示超链接读写）
      long hyperlinkCount =
          doc.paragraphs().stream()
              .map(Paragraph::inlineElements)
              .mapToLong(ies -> ies.stream().filter(e -> e instanceof Hyperlink).count())
              .sum();
      assertThat(hyperlinkCount).as("恰好 1 个超链接（避免 Agent 示例里的索引歧义）").isEqualTo(1);

      // 1 个表格
      assertThat(doc.tables()).hasSize(1);

      // 表格：表头 + 2 数据行；(1,1) 单元格含 2 个 run
      var table = doc.tables().get(0);
      assertThat(table.rows()).hasSize(3);
      var twoRunCell = table.row(2).cell(1);
      assertThat(twoRunCell.paragraph(0).runs()).hasSize(2);
    }
  }
}
