package com.non.docx.toolkit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * runtime 骨架最小闭环测试：open → run(空 plan) → DONE。
 *
 * <p>验证 {@link DocxOrchestrator} 的会话生命周期、状态机推进、空 plan 提交路径打通。
 */
class DocxOrchestratorTest {

  @Test
  void emptyPlanReachesDone(@TempDir Path tmp) throws Exception {
    // 构建一份最小文档并落盘
    Path docPath = tmp.resolve("a.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("hello");
      doc.save(docPath);
    }

    // orchestrator + 占位专家（总是 relevant，产出空 plan）
    DocxOrchestrator orchestrator = DocxOrchestrator.create();
    orchestrator.experts().register(new NoopExpert());

    String conv = orchestrator.open(docPath);
    assertThat(conv).startsWith("conv-");

    // 低层 plan：应到达 DONE（空 plan，commit 空提交成功）
    RouterResult result = orchestrator.plan(conv, "随便看看");
    assertThat(result.state()).isEqualTo(RouterState.DONE);
    assertThat(result.mergedPlan().operations()).isEmpty();
    assertThat(result.commitResult()).isNotNull();
    assertThat(result.commitResult().allSucceeded()).isTrue();

    // 高层 run：应返回摘要
    RunSummary summary = orchestrator.run(conv, "再看看");
    assertThat(summary.conversationId()).isEqualTo(conv);
    assertThat(summary.executedCount()).isZero();

    orchestrator.close(conv);
  }

  @Test
  void reopenBumpsSessionGeneration(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("b.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("hi");
      doc.save(docPath);
    }
    DocxOrchestrator orchestrator = DocxOrchestrator.create();
    String conv = orchestrator.open(docPath);

    // 低层 analyze 拿到第一代快照
    DocumentSnapshot snap1 = orchestrator.analyze(conv);
    assertThat(snap1.sessionGeneration()).isEqualTo(1L);

    // reopen 后代次递增
    orchestrator.reopen(conv);
    DocumentSnapshot snap2 = orchestrator.analyze(conv);
    assertThat(snap2.sessionGeneration()).isEqualTo(2L);
    // 旧快照对新代次失效
    assertThat(snap1.isValidFor(snap2.sessionGeneration())).isFalse();

    orchestrator.close(conv);
  }

  /** 占位专家：总是 relevant，产出空 plan。 */
  static final class NoopExpert implements ExpertAgent {
    @Override
    public String name() {
      return "NoopAgent";
    }

    @Override
    public boolean relevantTo(String intent, DocumentSnapshot snapshot) {
      return true;
    }

    @Override
    public ExpertPlan plan(
        com.non.docx.toolkit.orchestration.session.OrchestratorSession session,
        DocumentSnapshot snapshot,
        String intent,
        java.util.function.Consumer<com.non.docx.toolkit.orchestration.agent.LlmTraceEvent>
            traceCallback) {
      return new ExpertPlan(
          name(),
          "noop-plan-1",
          session.conversationId(),
          snapshot.snapshotVersion(),
          session.sessionGeneration(),
          List.of());
    }
  }
}
