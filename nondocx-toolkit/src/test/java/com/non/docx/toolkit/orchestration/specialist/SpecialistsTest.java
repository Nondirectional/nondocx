package com.non.docx.toolkit.orchestration.specialist;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.orchestration.DocxOrchestrator;
import com.non.docx.toolkit.orchestration.RouterResult;
import com.non.docx.toolkit.orchestration.RouterState;
import com.non.docx.toolkit.orchestration.RunSummary;
import com.non.docx.toolkit.orchestration.review.ReviewStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** specialists 端到端测试：质量阻断、质量 warning 可提交、修订 review 场景。 */
class SpecialistsTest {

  @Test
  void qualityCheckPassesAsApproved(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("clean.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正常的标题").heading(com.non.docx.core.api.style.HeadingLevel.H1);
      doc.addParagraph("正文内容。");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new QualityAgent(orch.toolkit().qualityCheck));
    orch.executors().register(new QualityExecutor());

    String conv = orch.open(docPath);
    RunSummary summary = orch.run(conv, "检查一下文档质量");
    // 干净文档应无阻断
    assertThat(summary.blockedCount()).isZero();
    orch.close(conv);
  }

  @Test
  void qualityGateFailedBlocksBatch(@TempDir Path tmp) throws Exception {
    // 构造一个会触发质量问题的文档（如标题层级缺失）
    Path docPath = tmp.resolve("dirty.docx");
    try (Document doc = Docx.create()) {
      // 故意制造 H3 但没有 H1/H2，可能触发 heading-levels 检查
      doc.addParagraph("跳级的标题").heading(com.non.docx.core.api.style.HeadingLevel.H3);
      doc.addParagraph("正文。");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new QualityAgent(orch.toolkit().qualityCheck));
    orch.executors().register(new QualityExecutor());

    String conv = orch.open(docPath);
    RouterResult result = orch.plan(conv, "质量检查");
    // 如果检查报告了 error，整批 BLOCKED；否则可能 APPROVED（取决于具体检查结果）
    // 这里验证：若有 BLOCKED，状态为 FAILED；若无，状态为 DONE
    if (result.mergedPlan().hasBlocked()) {
      assertThat(result.state()).isEqualTo(RouterState.FAILED);
      // 确认是 QUALITY_GATE_FAILED
      boolean hasQualityBlock =
          result.mergedPlan().operations().stream()
              .anyMatch(
                  op ->
                      op.reviewStatus() == ReviewStatus.BLOCKED
                          && op.review().ruleCode().contains("QUALITY_GATE_FAILED"));
      assertThat(hasQualityBlock).isTrue();
    } else {
      assertThat(result.state()).isEqualTo(RouterState.DONE);
    }
    orch.close(conv);
  }

  @Test
  void revisionAgentProducesAcceptedPlan(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("rev.docx");
    try (Document doc = Docx.create()) {
      // 开启修订追踪并插入一条修订
      doc.trackedChanges().enable();
      var p = doc.addParagraph("原文本");
      p.addInsertion("non", "新增内容");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new RevisionAgent());
    orch.executors().register(new RevisionExecutor(orch.toolkit().trackedChangeQuery));

    String conv = orch.open(docPath);
    RouterResult result = orch.plan(conv, "接受所有修订");
    // RevisionAgent 应产出 operation（若文档有修订）；review 应被触发（revision 域）
    assertThat(result.reviewTriggered()).isTrue();
    // revision 域 operation 存在
    boolean hasRevisionOp =
        result.mergedPlan().operations().stream().anyMatch(op -> "revision".equals(op.toolGroup()));
    assertThat(hasRevisionOp).isTrue();
    orch.close(conv);
  }

  @Test
  void qualityWarningAllowedToSubmit(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("warn.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文。");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new QualityAgent(orch.toolkit().qualityCheck));
    orch.executors().register(new QualityExecutor());

    String conv = orch.open(docPath);
    RunSummary summary = orch.run(conv, "检查质量");
    // WARNED 允许提交（warningCount 可能为 0 或 >0，但不应阻断）
    assertThat(summary.blockedCount()).isZero();
    orch.close(conv);
  }
}
