package io.github.nondirectional.docx.examples.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.toolkit.result.ToolResultParser;
import org.junit.jupiter.api.Test;

class EvalRunnerTest {

  @Test
  void exposedToolCountMatchesDemoSurfaceWithoutSkills() {
    // 57 个普通工具 + 1 个只读复审 SubAgent；不含 6 条 Skill。
    assertThat(EvalRunner.exposedToolCount()).isEqualTo(58);
  }

  @Test
  void compilerSurfaceExposesSixEntriesOnly() {
    // compiler 面 = current_document + create/read/validate/edit_document + 1 个只读复审 SubAgent。
    // 工具数从 58 收敛到 6 是「文档 IR + 编译器」路线的核心假设，必须锁死。
    assertThat(EvalRunner.compilerToolCount()).isEqualTo(6);
  }

  @Test
  void currentDocumentToolReturnsParsedDocId() {
    EvalRunner.CurrentDocumentTool tool = new EvalRunner.CurrentDocumentTool("doc-1");

    String raw = tool.currentDocument();
    ToolResultParser.Snapshot parsed = ToolResultParser.parse(raw);

    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();
    assertThat(parsed.dataText()).isEqualTo("doc-1");
  }
}
