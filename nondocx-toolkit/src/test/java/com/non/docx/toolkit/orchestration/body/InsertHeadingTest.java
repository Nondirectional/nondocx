package com.non.docx.toolkit.orchestration.body;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.toolkit.orchestration.ConflictKey;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.DocxOrchestrator;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.RouterResult;
import com.non.docx.toolkit.orchestration.RouterState;
import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * insert_heading 端到端验证：模拟 LLM 产出「插入标题」operation（含 heading_level + alignment + font_size），验证直接操作
 * core 活文档的正确性。
 */
class InsertHeadingTest {

  @Test
  void insertHeadingAtStartWithAlignmentAndFontSize(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("heading.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文一");
      doc.addParagraph("正文二");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new HeadingExpert());
    orch.executors().register(new BodyExecutor(orch.toolkit().body, orch.toolkit()));

    String conv = orch.open(docPath);
    RouterResult result = orch.plan(conv, "加标题");
    assertThat(result.state()).isEqualTo(RouterState.DONE);
    assertThat(result.commitResult().executed()).hasSize(1);

    // 保存并重读验证
    Path out = tmp.resolve("heading-out.docx");
    orch.save(conv, out);
    try (Document reread = Docx.open(out)) {
      // 应有 3 段（标题 + 原有 2 段）
      assertThat(reread.paragraphs()).hasSize(3);
      // 第 0 段是标题
      var heading = reread.paragraphs().get(0);
      assertThat(heading.text()).isEqualTo("项目周报");
      assertThat(heading.heading()).isEqualTo(HeadingLevel.H1);
      // 验证对齐（CENTER）
      assertThat(heading.alignment()).isEqualTo(com.non.docx.core.api.style.Alignment.CENTER);
    }
    orch.close(conv);
  }

  @Test
  void insertHeadingWithoutToolkitInjectionFailsCleanly(@TempDir Path tmp) throws Exception {
    Path docPath = tmp.resolve("h2.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("内容");
      doc.save(docPath);
    }

    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new HeadingExpert());
    // 只用单参构造（不注入 toolkit）→ insert_heading 应失败报错
    orch.executors().register(new BodyExecutor(orch.toolkit().body));

    String conv = orch.open(docPath);
    RouterResult result = orch.plan(conv, "加标题");
    assertThat(result.state()).isEqualTo(RouterState.FAILED);
    assertThat(result.commitResult().failureMessage()).contains("DocxToolkit");
    orch.close(conv);
  }

  /** 固定产出一条 insert_heading operation（模拟 LLM 产出）。 */
  static class HeadingExpert implements ExpertAgent {
    @Override
    public String name() {
      return "HeadingExpert";
    }

    @Override
    public boolean relevantTo(String intent, DocumentSnapshot snapshot) {
      return true;
    }

    @Override
    public ExpertPlan plan(OrchestratorSession session, DocumentSnapshot snapshot, String intent) {
      // 模拟 LLM 产出的 payload（含 style=Heading1, alignment=center, font_size=28）
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("text", "项目周报");
      payload.put("heading_level", "H1");
      payload.put("alignment", "CENTER");
      payload.put("font_size", 28);
      payload.put("position", "start");
      Operation op =
          Operation.of(
              "heading-op-1",
              "body",
              "insert_heading",
              "heading:1",
              payload,
              new ConflictKey("body", "insert_heading", "heading:1"),
              "插入大标题",
              "用户要求加标题",
              "");
      return new ExpertPlan(
          name(),
          "heading-plan-1",
          session.conversationId(),
          snapshot.snapshotVersion(),
          session.sessionGeneration(),
          List.of(op));
    }
  }
}
