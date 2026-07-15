package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.result.ToolResultParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 单 Agent 真实模型集成测试（替代原 {@code VllmSubAgentIntegrationTest}）。
 *
 * <p>断言从"主 Agent 调 invoke_subagent 委派"改为"单 Agent 直接调写工具"——验证回归后模型能直接编排写工具 + {@code
 * check_quality}，无需 SubAgent 委派层。
 */
class VllmSingleAgentIntegrationTest {

  private static final String VLLM_BASE_URL = "http://10.100.10.21:40002/v1";
  private static final String MODEL = "qwen3-14b";

  @Test
  @Timeout(90)
  void singleAgentEditsCenteredTitleDirectly(@TempDir Path temp) throws Exception {
    assertModelServerReachable();
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    DocumentTools documentTools = new DocumentTools(toolkit, () -> docId, () -> file);
    ToolRegistry tools =
        new ToolRegistry()
            .scan(documentTools)
            .scan(toolkit.view)
            .scan(toolkit.body)
            .scan(toolkit.table)
            .scan(toolkit.headerFooterToc)
            .scan(toolkit.trackedChangeQuery)
            .scan(toolkit.trackedChangeAuthoring)
            .scan(toolkit.qualityCheck);
    LLM llm =
        new VLLM(VLLM_BASE_URL, MODEL)
            .enableThinking(false)
            .temperature(0.0)
            .maxCompletionTokens(2048);
    Agent agent =
        Agent.builder(llm, tools)
            .executor(null)
            .maxIterations(10)
            .systemPrompt(
                "你是文档 Agent。先调用 current_document 获取 doc_id，再把任务写入文档。"
                    + "需要居中标题时调用 insert_paragraph，使用 body_index=0、heading_level=H1、alignment=CENTER。"
                    + "完成后调用 check_quality。没有保存工具——保存由系统自动完成。")
            .build();
    List<String> calledTools = new ArrayList<>();

    agent.run(
        "在文档开头添加一个居中标题，内容为项目周汇报。",
        event -> {
          if (event instanceof AgentEvent.ToolStart) {
            calledTools.add(((AgentEvent.ToolStart) event).toolName());
          }
        });

    // 单 Agent 应直接调写工具，不再有 invoke_subagent 委派层
    assertFalse(calledTools.contains("invoke_subagent"), "单 Agent 模式不应有 SubAgent 委派");
    assertTrue(
        calledTools.stream().anyMatch(t -> !AgentBridge.isReadonly(t)),
        "应至少调用一个写工具；实际调用: " + calledTools);
    assertTrue(calledTools.contains("insert_paragraph"), "应调用 insert_paragraph；实际: " + calledTools);

    // 模拟 AgentBridge 的 Complete flush：保存落盘（真实 Agent 不带 flush，由本测试显式触发）
    DocumentTools.SaveOutcome outcome = documentTools.saveCurrentDocument(false);
    assertTrue(outcome.saved, "保存应成功: " + outcome.error);
    try (Document reopened = Docx.open(file)) {
      assertEquals("项目周汇报", reopened.paragraph(0).text());
      assertEquals(Alignment.CENTER, reopened.paragraph(0).alignment());
    }
  }

  private static String data(String raw) {
    ToolResultParser.Snapshot result = ToolResultParser.parse(raw);
    if (result == null || !result.success() || result.dataText() == null) {
      throw new AssertionError("工具调用失败: " + raw);
    }
    return result.dataText();
  }

  private static void assertModelServerReachable() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(VLLM_BASE_URL + "/models"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
    assertEquals(200, response.statusCode(), "VLLM 模型服务不可用");
  }
}
