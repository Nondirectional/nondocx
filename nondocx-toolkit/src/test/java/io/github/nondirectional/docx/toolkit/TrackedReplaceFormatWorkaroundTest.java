package io.github.nondirectional.docx.toolkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.track.TextChangeDetails;
import io.github.nondirectional.docx.core.api.track.TrackedChange;
import io.github.nondirectional.docx.core.api.track.TrackedChangeType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 实证 full 面对 eval 任务 tracked-replace-format 的 workaround 链（docs/11 §6.14）。
 *
 * <p>背景（runId {@code 20261030-220341-436}）：full 面 {@code insert_tracked_run} 只能插在段落末尾、 {@code
 * delete_run_tracked} 支持 target_text 外科手术式删除——模型走了「del 精确 + ins 落段尾」的 语义最忠实路径，最终可见文本「结论：。通过」不满足
 * check（期望「结论：通过。」）。
 *
 * <p>本测试锁定另一条通路：{@code update_run_style} 先加粗源 run → {@code replace_run_tracked} 整 run 替换（新 run 复制源
 * run 的完整 rPr，含 bold）。 按 eval 口径（save+reopen 读落盘文档）逐条复验 4 项 check 全过——证明该任务在 full 面
 * 物理可达，属「可过而未过」的行为失败，不 flag capabilityGap。
 */
class TrackedReplaceFormatWorkaroundTest {

  @Test
  void wholeRunReplaceInheritsBoldAndPassesEvalChecks(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("seed.docx");
    try (Document seed = Docx.create()) {
      seed.addParagraph("结论：待定。");
      seed.save(file);
    }

    DocxToolkit tk = new DocxToolkit();
    String docId =
        ToolTestSupport.extractDocId(tk.session.openDocx(file.toAbsolutePath().toString()));
    assertThat(docId).startsWith("doc-");

    // workaround 第一步：加粗源 run（ins 的格式从这里继承——replace_run_tracked 复制源 rPr）
    String styleResult =
        tk.body.updateRunStyle(
            docId, List.of(Map.of("paragraph_index", 0, "run_index", 0, "bold", true)), null, null);
    assertThat(ToolTestSupport.isFailure(styleResult))
        .as("update_run_style 应成功: %s", styleResult)
        .isFalse();

    // workaround 第二步：整 run 修订替换，new_text 承载「结论：通过。」全文
    String replaceResult =
        tk.trackedChangeAuthoring.replaceRunTracked(
            docId,
            "审查员",
            List.of(Map.of("paragraph_index", 0, "run_index", 0, "new_text", "结论：通过。")),
            null,
            null);
    assertThat(ToolTestSupport.isFailure(replaceResult))
        .as("replace_run_tracked 应成功: %s", replaceResult)
        .isFalse();

    Path out = tmp.resolve("out.docx");
    String saveResult = tk.session.saveDocx(docId, out.toAbsolutePath().toString());
    assertThat(ToolTestSupport.isFailure(saveResult)).as("save_docx 应成功").isFalse();

    // eval 口径：save+reopen 后按 tracked-replace-format 的 4 项 check 断言
    try (Document doc = Docx.open(out)) {
      boolean hasDel = false;
      TrackedChange ins = null;
      for (TrackedChange change : doc.trackedChanges().list()) {
        if (change.type() == TrackedChangeType.DEL && "审查员".equals(change.author())) {
          hasDel = true;
        }
        if (change.type() == TrackedChangeType.INS && "审查员".equals(change.author())) {
          ins = change;
        }
      }
      // check 1：存在作者=审查员的删除修订
      assertThat(hasDel).as("存在作者=审查员的删除修订").isTrue();
      // check 2：插入修订文本正确（含「通过」、无字面星号）
      assertThat(ins).as("存在作者=审查员的插入修订").isNotNull();
      String insText = ((TextChangeDetails) ins.details()).text();
      assertThat(insText).contains("通过").doesNotContain("*");
      // check 3：插入修订的 run 全部加粗（rPr 复制自被加粗的源 run）
      var insRuns = ins.raw().getRList();
      assertThat(insRuns).as("插入修订内应有 run").isNotEmpty();
      for (var run : insRuns) {
        assertThat(run.isSetRPr() && run.getRPr().sizeOfBArray() > 0).as("插入修订的 run 应为粗体").isTrue();
      }
      // check 4：正文可见文本未被非修订修改（del 不进 text()，ins 进）
      Paragraph p = doc.paragraphs().get(0);
      assertThat(p.text().trim()).as("第 0 段可见文本").isEqualTo("结论：通过。");
    }
  }
}
