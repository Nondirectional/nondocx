package com.non.docx.examples.agent;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.examples.ExamplePaths;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.DocxOrchestrator;
import com.non.docx.toolkit.orchestration.RouterResult;
import com.non.docx.toolkit.orchestration.RunSummary;
import com.non.docx.toolkit.orchestration.body.BodyAgent;
import com.non.docx.toolkit.orchestration.body.BodyExecutor;
import com.non.docx.toolkit.orchestration.table.TableAgent;
import com.non.docx.toolkit.orchestration.table.TableExecutor;
import java.nio.file.Path;

/**
 * DocxOrchestrator 多子代理编排示例：展示新编排体系的高层 run / 低层 plan / debug 三条路径。
 *
 * <p><b>它演示了什么</b>
 *
 * <ol>
 *   <li>新架构入口 {@link DocxOrchestrator}——替代旧的单 Agent 直连 toolkit 方式。
 *   <li>高层 {@code run(...)}——一句话跑完 analyze/plan/review/commit，返回 {@link RunSummary} 摘要。
 *   <li>低层 {@code analyze(...)} / {@code plan(...)}——拿到完整阶段产物（snapshot/mergedPlan）用于调试。
 *   <li>会话模型——{@code conversationId} 绑定单文档，reopen 递增代次。
 * </ol>
 *
 * <p><b>与旧 DocxAgentExample 的区别。</b> 旧版让 LLM 直接调用 open/read/edit/save 工具； 新版让编排层（RouterAgent + 专家 +
 * CommitCoordinator）统一管控：专家只产出计划，写入经唯一串行通道。 本示例用启发式专家（BodyAgent/TableAgent，非 LLM），无需 API key 即可运行。
 *
 * <pre>{@code
 * mvn -q -pl nondocx-examples exec:java \
 *   -Dexec.mainClass=com.non.docx.examples.agent.DocxOrchestratorExample
 * }</pre>
 */
public final class DocxOrchestratorExample {

  public static void main(String[] args) throws Exception {
    // 1. 准备一份样例文档
    Path docPath = ExamplePaths.outputDir().resolve("orchestrator-sample.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("你好世界");
      var table = doc.addTable();
      table.row(r -> r.cell("姓名").cell("年龄"));
      table.row(r -> r.cell("小明").cell("18"));
      doc.save(docPath);
    }
    System.out.println("[示例] 样例文档已生成: " + docPath);

    // 2. 构建 orchestrator，注册正文/表格专家与执行器
    DocxOrchestrator orch = DocxOrchestrator.create();
    orch.experts().register(new BodyAgent());
    orch.experts().register(new TableAgent());
    orch.executors().register(new BodyExecutor(orch.toolkit().body));
    orch.executors().register(new TableExecutor(orch.toolkit().table));

    // 3. 打开文档，拿到 conversationId（对外标识，底层 docId 不外露）
    String conv = orch.open(docPath);
    System.out.println("[示例] 会话已开启: " + conv);

    // —— 低层 analyze：只看快照，不进 plan/commit ——
    System.out.println("\n=== 低层 analyze ===");
    DocumentSnapshot snap = orch.analyze(conv);
    System.out.println("  段落数: " + snap.overview().paragraphCount());
    System.out.println("  表格数: " + snap.overview().tableCount());
    System.out.println("  会话代次: " + snap.sessionGeneration());

    // —— 低层 plan：拿到完整 RouterResult（含 mergedPlan/review）——
    System.out.println("\n=== 低层 plan ===");
    RouterResult result = orch.plan(conv, "把「你好」改成「Hello」");
    System.out.println("  状态: " + result.state());
    System.out.println("  review 触发: " + result.reviewTriggered());
    System.out.println("  操作数: " + result.mergedPlan().operations().size());

    // —— 高层 run：一句话跑完，返回摘要 ——
    System.out.println("\n=== 高层 run ===");
    RunSummary summary = orch.run(conv, "把「小明」改成「大明」");
    System.out.println("  总结: " + summary.summaryText());
    System.out.println(
        "  执行: "
            + summary.executedCount()
            + "  警告: "
            + summary.warnedCount()
            + "  跳过: "
            + summary.skippedCount()
            + "  阻断: "
            + summary.blockedCount());

    // 4. 保存并关闭
    Path out = ExamplePaths.outputDir().resolve("orchestrator-output.docx");
    System.out.println("\n[示例] 保存到: " + orch.save(conv, out));
    System.out.println("[示例] 关闭: " + orch.close(conv));
  }

  private DocxOrchestratorExample() {}
}
