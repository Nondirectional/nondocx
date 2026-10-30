package io.github.nondirectional.docx.toolkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.toolkit.result.ToolResultCode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * move 成对修订「按 id 批量应用 + 随后 live view」的锁定测试(TODO #009 复现步骤,docs/11 §6.14)。
 *
 * <p><b>背景(runId {@code 20261030-220341-436})。</b> live 会话里 {@code move_run_tracked} 后按 ids 应用 move
 * 对:第一端成功即联动两端,第二端报「找不到 id」→ 返回 PARTIAL_FAILURE;此后 {@code view_outline} / {@code view_text}
 * 等读工具连续失败——模型因此失去复核能力。
 *
 * <p><b>本测试正面锁定 live 会话崩溃修复</b>:应用 move 对之后(修复前任何 view 均抛 XmlValueDisconnectedException),view
 * 工具正常返回且含预期文本。同时锁定成对语义的返回串说明 (R4,只加文案不改行为):PARTIAL_FAILURE、失败 1 条计数与异常消息零变化,失败行追加成对说明。
 */
class TrackedMovePairApplyTest {

  @Test
  void movePairApplyKeepsPartialFailureAndViewsAlive(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move-apply.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("被移走的文字");
      doc.addParagraph("目标段");
      doc.save(file);
    }

    DocxToolkit tk = new DocxToolkit();
    String docId =
        ToolTestSupport.extractDocId(tk.session.openDocx(file.toAbsolutePath().toString()));

    // live 会话里创作 move 对(源段 0 的 run 0 → 目标段 1)
    assertThat(tk.trackedChangeAuthoring.moveRunTracked(docId, 0, 0, 1, "甲")).contains("moveFrom");

    String listOutput = tk.trackedChangeQuery.listTrackedChanges(docId);
    assertThat(listOutput).contains("MOVE_FROM").contains("MOVE_TO");
    String fromId = extractId(listOutput, "move_from:");
    String toId = extractId(listOutput, "move_to:");

    // 按 ids 应用 move 对(先 to 后 from——第一端成功即联动两端,第二端报找不到属预期)
    String result =
        tk.trackedChangeQuery.applyTrackedChanges(
            docId, "ACCEPT", "TEXT_OR_MOVE", List.of(toId, fromId));

    // 行为零变化:PARTIAL_FAILURE + 成功 1 条、失败 1 条(该端报「找不到 id 为 ... 的修订」)
    assertThat(ToolTestSupport.parse(result).code()).isEqualTo(ToolResultCode.PARTIAL_FAILURE);
    assertThat(result).contains("成功 1 条,失败 1 条");
    assertThat(result).contains("错误(找不到 id 为 " + fromId + " 的修订)");
    // R4 新增:失败行追加成对语义说明(仅文案,不影响计数与返回码)
    assertThat(result).contains("move 成对修订,应用一半即整对完成,该端已随配对端应用,属预期");

    // 修复前:下面两个 view 均抛 XmlValueDisconnectedException(fill-template 尾部 5 个读工具连败的根因)。
    // 修复后:正常返回且含预期段落文本。
    String outline = tk.view.viewOutline(docId, null, null);
    assertThat(ToolTestSupport.isFailure(outline))
        .as("apply 后 view_outline 应正常返回: %s", outline)
        .isFalse();
    assertThat(outline).contains("目标段");

    String text = tk.view.viewText(docId, null, null);
    assertThat(ToolTestSupport.isFailure(text)).as("apply 后 view_text 应正常返回: %s", text).isFalse();
    assertThat(text).contains("目标段");

    // 落盘终态(eval 口径):文本移走、零残留修订
    Path out = tmp.resolve("move-apply-out.docx");
    assertThat(
            ToolTestSupport.isFailure(tk.session.saveDocx(docId, out.toAbsolutePath().toString())))
        .as("save_docx 应成功")
        .isFalse();
    try (Document reopened = Docx.open(out)) {
      assertThat(reopened.paragraph(0).text()).isEmpty();
      assertThat(reopened.paragraph(1).text()).isEqualTo("目标段被移走的文字");
      assertThat(reopened.trackedChanges().list()).isEmpty();
    }
  }

  /** 从 list 输出里抽取首个以 prefix 开头的 id(到行尾)。 */
  private static String extractId(String listOutput, String prefix) {
    int i = listOutput.indexOf(prefix);
    if (i < 0) {
      return "";
    }
    int end = listOutput.indexOf('\n', i);
    if (end < 0) {
      end = listOutput.length();
    }
    return listOutput.substring(i, end);
  }
}
