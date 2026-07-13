package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class VllmSubAgentIntegrationTest {

  private static final String VLLM_BASE_URL = "http://10.100.10.21:40002/v1";
  private static final String MODEL = "qwen3-14b";

  @Test
  @Timeout(90)
  void delegatesCenteredTitleCreationToSubAgent(@TempDir Path temp) throws Exception {
    assertModelServerReachable();
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    AtomicBoolean cancelled = new AtomicBoolean();
    DocumentExecutionState state = new DocumentExecutionState();
    DocumentSessionTools sessionTools =
        new DocumentSessionTools(toolkit, () -> docId, () -> file, () -> state, cancelled);
    ToolRegistry childTools =
        new ToolRegistry()
            .scan(sessionTools)
            .scan(toolkit.view)
            .scan(toolkit.body)
            .scan(toolkit.table)
            .scan(toolkit.headerFooterToc)
            .scan(toolkit.trackedChangeQuery)
            .scan(toolkit.trackedChangeAuthoring)
            .scan(toolkit.qualityCheck);
    ToolRegistry primaryTools = new ToolRegistry();
    primaryTools
        .registerSubAgent("invoke_subagent", "实施当前文档编辑任务")
        .systemPrompt(
            "你是文档实施者。先调用 current_document，再把任务写入该文档。"
                + "需要居中标题时调用 insert_paragraph，使用 body_index=0、heading_level=H1、alignment=CENTER。"
                + "最后调用 save_current_document。最终只输出 JSON。")
        .toolRegistry(childTools)
        .maxIterations(10)
        .build();
    LLM llm =
        new VLLM(VLLM_BASE_URL, MODEL)
            .enableThinking(false)
            .temperature(0.0)
            .maxCompletionTokens(2048);
    Agent primary =
        Agent.builder(llm, primaryTools)
            .executor(null)
            .maxIterations(4)
            .systemPrompt("你是主 Agent。明确编辑请求必须调用 invoke_subagent，不可直接编辑。")
            .build();
    List<String> tools = new ArrayList<>();

    var result =
        primary.run(
            "在文档开头添加一个居中标题，内容为项目周汇报。",
            event -> {
              if (event instanceof AgentEvent.ToolStart) {
                tools.add(((AgentEvent.ToolStart) event).toolName());
              }
            });

    assertTrue(
        tools.contains("invoke_subagent"),
        "模型未调用 invoke_subagent；请检查 VLLM 的 tool-call parser。最终回复: " + result.content());
    assertTrue(state.saved, "SubAgent 未保存文档；最终回复: " + result.content());
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
