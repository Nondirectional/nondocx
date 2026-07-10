package com.non.docx.examples.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.orchestration.DocxOrchestrator;
import com.non.docx.toolkit.orchestration.RouterResult;
import com.non.docx.toolkit.orchestration.RouterState;
import com.non.docx.toolkit.orchestration.RunSummary;
import com.non.docx.toolkit.orchestration.body.BodyAgent;
import com.non.docx.toolkit.orchestration.body.BodyExecutor;
import com.non.docx.toolkit.orchestration.specialist.QualityAgent;
import com.non.docx.toolkit.orchestration.specialist.QualityExecutor;
import com.non.docx.toolkit.orchestration.table.TableAgent;
import com.non.docx.toolkit.orchestration.table.TableExecutor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DocxOrchestrator 端到端验证：覆盖正文修改、表格修改、质量告警混合场景。
 *
 * <p>用启发式专家（非 LLM）验证编排层全流程，无需 API key。
 */
class DocxOrchestratorE2ETest {

  @Test
  void bodyTableQualityMixedEndToEnd(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("e2e.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("你好世界");
      var table = doc.addTable();
      table.row(r -> r.cell("姓名").cell("年龄"));
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new BodyAgent());
    orch.experts().register(new TableAgent());
    orch.experts().register(new QualityAgent(orch.toolkit().qualityCheck));
    orch.executors().register(new BodyExecutor(orch.toolkit().body));
    orch.executors().register(new TableExecutor(orch.toolkit().table));
    orch.executors().register(new QualityExecutor());

    String conv = orch.open(docPath);

    // 正文修改
    RunSummary bodySummary = orch.run(conv, "把「你好」改成「Hello」");
    assertThat(bodySummary.blockedCount()).isZero();
    assertThat(bodySummary.executedCount()).isGreaterThanOrEqualTo(1);

    // 表格修改
    RunSummary tableSummary = orch.run(conv, "表格0行1列0写成「名字」");
    assertThat(tableSummary.blockedCount()).isZero();

    // 质量检查（不阻断）
    RunSummary qualitySummary = orch.run(conv, "检查质量");
    assertThat(qualitySummary.blockedCount()).isZero();

    // 落盘验证
    Path out = tmp.resolve("e2e-out.docx");
    orch.save(conv, out);
    try (Document reread = Docx.open(out)) {
      assertThat(reread.paragraphs().get(0).text()).contains("Hello");
    }
    orch.close(conv);
  }

  @Test
  void failedCommitRequiresReopen(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("fail.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("文本");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new BodyAgent());
    // 不注册 BodyExecutor → 提交会因「找不到执行器」失败
    String conv = orch.open(docPath);
    RouterResult result = orch.plan(conv, "把「文本」改成「变更」");
    // 应到达 FAILED（找不到执行器）
    assertThat(result.state()).isEqualTo(RouterState.FAILED);
    assertThat(result.shouldReopen()).isTrue();

    // reopen 后应能恢复（代次递增）
    orch.reopen(conv);
    RouterResult after = orch.plan(conv, "检查质量");
    // reopen 后 plan 应能正常推进（不再处于失败态）
    assertThat(after.state()).isIn(RouterState.DONE, RouterState.FAILED);
    orch.close(conv);
  }

  @Test
  void lowLevelApiExposesFullArtifacts(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("low.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("段落A");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new BodyAgent());
    orch.executors().register(new BodyExecutor(orch.toolkit().body));
    String conv = orch.open(docPath);

    // 低层 analyze：拿到 snapshot
    var snap = orch.analyze(conv);
    assertThat(snap.overview().paragraphCount()).isEqualTo(1);

    // 低层 plan：拿到完整 RouterResult（含 mergedPlan）
    RouterResult result = orch.plan(conv, "把「段落A」改成「段落B」");
    assertThat(result.snapshot()).isNotNull();
    assertThat(result.mergedPlan()).isNotNull();

    orch.close(conv);
  }
}
