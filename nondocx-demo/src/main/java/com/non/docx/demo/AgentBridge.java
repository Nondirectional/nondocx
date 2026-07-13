package com.non.docx.demo;

import com.non.chain.ChatResult;
import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.agent.BeforeResult;
import com.non.chain.memory.MessageWindowChatMemory;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.result.ToolResultParser;
import io.javalin.http.Context;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 主 Agent 通过工具调用无状态 SubAgent 的 demo 对话入口。 */
final class AgentBridge {

  private static final Logger log = LoggerFactory.getLogger(AgentBridge.class);
  private static final String SUB_AGENT_TOOL = "invoke_subagent";

  private final boolean enabled;
  private final DocxToolkit toolkit;
  private final LLM llm;
  private final Path currentDocPath;
  private final Agent primaryAgent;
  private final MessageWindowChatMemory primaryMemory;
  private final AtomicBoolean cancelRequested = new AtomicBoolean();
  private final AtomicLong turnSeq = new AtomicLong();
  private final TraceJournal journal = new TraceJournal(Path.of("target", "demo-work"));
  private final DocumentSessionTools documentSessionTools;

  private String docId;
  private DocumentExecutionState activeExecution;

  AgentBridge(String apiKey, String currentDocPath) {
    this.currentDocPath = Path.of(currentDocPath);
    if (apiKey == null || apiKey.isBlank()) {
      enabled = false;
      toolkit = null;
      llm = null;
      primaryAgent = null;
      primaryMemory = null;
      documentSessionTools = null;
      return;
    }
    enabled = true;
    llm = new DashscopeLLM("qwen3.7-plus").maxCompletionTokens(65536).thinkingBudget(512);
    toolkit = new DocxToolkit();
    openCurrentDocument();
    primaryMemory =
        MessageWindowChatMemory.builder().conversationId("demo-document").maxMessages(24).build();
    documentSessionTools =
        new DocumentSessionTools(
            toolkit,
            () -> docId,
            () -> this.currentDocPath,
            () -> activeExecution,
            cancelRequested);

    ToolRegistry childTools =
        new ToolRegistry()
            .scan(documentSessionTools)
            .scan(toolkit.view)
            .scan(toolkit.body)
            .scan(toolkit.table)
            .scan(toolkit.headerFooterToc)
            .scan(toolkit.trackedChangeQuery)
            .scan(toolkit.trackedChangeAuthoring)
            .scan(toolkit.qualityCheck);
    ToolRegistry primaryTools = new ToolRegistry().scan(toolkit.view);
    primaryTools
        .registerSubAgent(SUB_AGENT_TOOL, "实施一项明确的当前文档编辑任务")
        .systemPrompt(
            "你是文档实施 SubAgent。每次任务无历史记忆。先调用 current_document 获取唯一允许使用的 doc_id；"
                + "再按任务需要读取并修改文档。不得调用或寻找 open_docx、close_docx、任意路径保存工具。"
                + "完成修改后运行 check_quality，最后调用 save_current_document。"
                + "若任一工具失败，停止实施并说明失败。最终只输出 JSON："
                + "{\"success\":boolean,\"summary\":string,\"changed\":boolean,\"qualityReport\":string,\"error\":string}。")
        .toolRegistry(childTools)
        .maxIterations(12)
        .addBeforeToolCall(
            context -> {
              if (!cancelRequested.get()) return BeforeResult.pass();
              if (activeExecution != null) activeExecution.cancelled = true;
              return BeforeResult.block("用户已取消，禁止继续调用文档工具");
            })
        .build();
    primaryAgent =
        Agent.builder(llm, primaryTools)
            .memory(primaryMemory)
            .maxIterations(8)
            .executor(null)
            .systemPrompt(
                "你是主文档 Agent。普通咨询只可调用只读 view 工具。"
                    + "用户提出明确编辑请求时，立即调用 invoke_subagent，并在它返回后只依据返回结果答复。"
                    + "你没有写工具，不得声称已修改或保存，除非 SubAgent 的结果明确成功。"
                    + "不要等待按钮授权，不要调用或描述 Dispatcher、计划、专家分派或提交阶段。")
            .build();
  }

  boolean enabled() {
    return enabled;
  }

  void clearMemory() {
    if (!enabled) return;
    primaryMemory.clear();
    cancelRequested.set(true);
    reopenCurrentDocument();
  }

  void runStream(String message, Context ctx, DocSession session) {
    if (!enabled) {
      emit(ctx, frame("error", "message", "未配置 DASHSCOPE_API_KEY，无法对话。"));
      emit(ctx, frame("done"));
      return;
    }
    String turnId = "turn-" + turnSeq.incrementAndGet();
    cancelRequested.set(false);
    activeExecution = new DocumentExecutionState();
    try {
      emitTrace(ctx, turnId, "prompt", "PrimaryDocumentAgent", message);
      ChatResult result = primaryAgent.run(message, event -> tracePrimaryEvent(ctx, turnId, event));
      DocumentExecutionState execution = activeExecution;
      if (execution.cancelled || execution.failed || (execution.delegated && !execution.saved)) {
        reopenCurrentDocument();
      }
      emit(ctx, frame("assistant", "turnId", turnId, "message", result.content()));
      if (execution.delegated) {
        emit(
            ctx,
            frame(
                "subagent_result",
                "turnId",
                turnId,
                "success",
                execution.saved && !execution.failed && !execution.cancelled,
                "changed",
                execution.saved,
                "qualityReport",
                execution.qualityReport,
                "error",
                execution.cancelled ? "用户已取消" : execution.failed ? "实施失败，未保存修改" : ""));
      }
      if (execution.saved) {
        emit(ctx, frame("doc_changed", "key", session.bumpKey()));
      }
    } catch (RuntimeException e) {
      reopenCurrentDocument();
      log.warn("主 Agent 执行失败: {}", rootMessage(e));
      emit(ctx, frame("error", "turnId", turnId, "message", rootMessage(e)));
    } finally {
      activeExecution = null;
      emit(ctx, frame("done"));
    }
  }

  void cancel() {
    cancelRequested.set(true);
  }

  String traceReplay() {
    return journal.readAll();
  }

  private void openCurrentDocument() {
    String raw = toolkit.session.openDocx(currentDocPath.toString());
    ToolResultParser.Snapshot parsed = ToolResultParser.parse(raw);
    if (parsed == null || !parsed.success() || parsed.dataText() == null) {
      throw new IllegalStateException("无法打开当前文档: " + raw);
    }
    docId = parsed.dataText();
  }

  private void reopenCurrentDocument() {
    if (docId != null) toolkit.session.closeDocx(docId);
    openCurrentDocument();
  }

  private void tracePrimaryEvent(Context ctx, String turnId, AgentEvent event) {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("type", "trace");
    trace.put("turnId", turnId);
    trace.put("agent", "PrimaryDocumentAgent");
    if (event instanceof AgentEvent.TextDelta) {
      trace.put("event", "content_delta");
      trace.put("delta", ((AgentEvent.TextDelta) event).delta());
    } else if (event instanceof AgentEvent.ThinkingDelta) {
      trace.put("event", "thinking_delta");
      trace.put("delta", ((AgentEvent.ThinkingDelta) event).delta());
    } else if (event instanceof AgentEvent.ToolStart) {
      AgentEvent.ToolStart tool = (AgentEvent.ToolStart) event;
      if (SUB_AGENT_TOOL.equals(tool.toolName()) && activeExecution != null) {
        activeExecution.delegated = true;
      }
      trace.put("event", "tool_start");
      trace.put("tool", tool.toolName());
      trace.put("arguments", tool.arguments());
    } else if (event instanceof AgentEvent.ToolEnd) {
      AgentEvent.ToolEnd tool = (AgentEvent.ToolEnd) event;
      trace.put("event", "tool_end");
      trace.put("tool", tool.toolName());
      trace.put("result", tool.result());
    } else if (event instanceof AgentEvent.Complete) {
      trace.put("event", "complete");
      trace.put("success", true);
    } else {
      return;
    }
    emit(ctx, trace);
  }

  private void emitTrace(Context ctx, String turnId, String event, String agent, String prompt) {
    emit(ctx, frame("trace", "turnId", turnId, "event", event, "agent", agent, "prompt", prompt));
  }

  private void emit(Context ctx, Map<String, Object> data) {
    try {
      journal.append(data);
      OutputStream out = ctx.res().getOutputStream();
      out.write(
          ("data: " + ctx.jsonMapper().toJsonString(data, Map.class) + "\n\n")
              .getBytes(StandardCharsets.UTF_8));
      ctx.res().flushBuffer();
    } catch (java.io.IOException e) {
      throw new RuntimeException("写 SSE 帧失败", e);
    }
  }

  private static Map<String, Object> frame(String type, Object... pairs) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", type);
    for (int i = 0; i < pairs.length; i += 2) data.put((String) pairs[i], pairs[i + 1]);
    return data;
  }

  private static String rootMessage(Throwable e) {
    Throwable cur = e;
    while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
    return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
  }
}
