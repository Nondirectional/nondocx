package com.non.docx.toolkit.orchestration.body;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.orchestration.DocxOrchestrator;
import com.non.docx.toolkit.orchestration.RouterResult;
import com.non.docx.toolkit.orchestration.RouterState;
import com.non.docx.toolkit.orchestration.RunSummary;
import com.non.docx.toolkit.orchestration.table.TableAgent;
import com.non.docx.toolkit.orchestration.table.TableExecutor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BodyAgent 端到端测试：正文修改闭环（替换、插入），经 DocxOrchestrator 走完整 analyze/plan/commit。 */
class BodyAgentTest {

  @Test
  void replaceRunTextClosesLoop(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("body.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("你好世界");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new BodyAgent());
    orch.executors().register(new BodyExecutor(orch.toolkit().body));

    String conv = orch.open(docPath);
    RunSummary summary = orch.run(conv, "把「你好」改成「Hello」");

    // 应到达 DONE，执行 1 项
    assertThat(summary.executedCount()).isEqualTo(1);
    assertThat(summary.blockedCount()).isZero();

    // 落盘后重读验证文本已改
    Path out = tmp.resolve("out.docx");
    orch.save(conv, out);
    try (Document reread = Docx.open(out)) {
      assertThat(reread.paragraphs().get(0).text()).contains("Hello");
    }
    orch.close(conv);
  }

  @Test
  void insertParagraphAppendsToEnd(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("body2.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("第一段");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new BodyAgent());
    orch.executors().register(new BodyExecutor(orch.toolkit().body));

    String conv = orch.open(docPath);
    RunSummary summary = orch.run(conv, "在末尾插入段落「新段落」");
    assertThat(summary.executedCount()).isEqualTo(1);

    Path out = tmp.resolve("out2.docx");
    orch.save(conv, out);
    try (Document reread = Docx.open(out)) {
      assertThat(reread.paragraphs()).hasSize(2);
      assertThat(reread.paragraphs().get(1).text()).isEqualTo("新段落");
    }
    orch.close(conv);
  }

  @Test
  void duplicateOperationGetsSkippedViaMerge(@TempDir Path tmp) throws Exception {
    // 两个 BodyAgent 实例都对同一意图产出操作 → 同 conflictKey → 第二个被去重 SKIPPED
    Path docPath = tmp.resolve("dup.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("你好世界");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    // 注册两个 BodyAgent → 都会 relevant，都产出同 conflictKey 的 operation
    orch.experts().register(new BodyAgent());
    orch.experts().register(new BodyAgent());
    orch.executors().register(new BodyExecutor(orch.toolkit().body));

    String conv = orch.open(docPath);
    RouterResult result = orch.plan(conv, "把「你好」改成「Hello」");

    // 应触发 review（跨专家 > 1）
    assertThat(result.reviewTriggered()).isTrue();
    // 第二个 BodyAgent 的 operation 被去重 SKIPPED
    assertThat(result.mergedPlan().hasSkipped()).isTrue();
    // 仍到达 DONE（SKIPPED 不阻断）
    assertThat(result.state()).isEqualTo(RouterState.DONE);
    orch.close(conv);
  }

  @Test
  void bodyAndTableMixedRequest(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("mixed.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("标题");
      var table = doc.addTable();
      table.row(r -> r.cell("A").cell("B"));
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new BodyAgent());
    orch.experts().register(new TableAgent());
    orch.executors().register(new BodyExecutor(orch.toolkit().body));
    orch.executors().register(new TableExecutor(orch.toolkit().table));

    String conv = orch.open(docPath);
    // 混合意图：先改正文，再改表格（用「；」分隔两种指令）
    RunSummary summary = orch.run(conv, "把「标题」改成「报告」；表格0行0列0写成「名称」");

    // 至少有一项被执行（取决于哪个 agent 命中）
    assertThat(summary.executedCount()).isGreaterThanOrEqualTo(1);
    orch.close(conv);
  }
}
