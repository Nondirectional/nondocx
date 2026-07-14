package com.non.docx.demo;

import com.non.chain.ChatResult;
import com.non.chain.agent.AfterResult;
import com.non.chain.agent.AfterToolCall;
import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.agent.BeforeResult;
import com.non.chain.agent.ToolCallContext;
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
  private final CurrentDocumentTools currentDocumentTools;

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
      currentDocumentTools = null;
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
    currentDocumentTools = new CurrentDocumentTools(() -> docId);

    ToolRegistry childTools =
        new ToolRegistry()
            .scan(currentDocumentTools)
            .scan(documentSessionTools)
            .scan(toolkit.view)
            .scan(toolkit.body)
            .scan(toolkit.table)
            .scan(toolkit.headerFooterToc)
            .scan(toolkit.trackedChangeQuery)
            .scan(toolkit.trackedChangeAuthoring)
            .scan(toolkit.qualityCheck);
    ToolRegistry primaryTools = new ToolRegistry().scan(currentDocumentTools).scan(toolkit.view);
    primaryTools
        .registerSubAgent(SUB_AGENT_TOOL, "实施一项明确的当前文档编辑任务")
        .systemPrompt(
            "你是文档实施 SubAgent。每次任务无历史记忆。先调用 current_document 获取唯一允许使用的 doc_id；"
                + "再按任务需要读取并修改文档。不得调用或寻找 open_docx、close_docx、任意路径保存工具。"
                + "完成修改后运行 check_quality，最后调用 save_current_document。"
                + "若任一工具失败，停止实施并说明失败。最终只输出 JSON："
                + "{\"success\":boolean,\"summary\":string,\"changed\":boolean,\"qualityReport\":string,\"error\":string}。")
        .toolRegistry(childTools)
        .maxIterations(30)
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
            .addAfterToolCall(this::afterInvokeSubAgent)
            .systemPrompt(
                "你是主文档 Agent。普通咨询只可调用只读 view 工具。"
                    + "用户提出明确编辑请求时，立即调用 invoke_subagent，并在它返回后只依据返回结果答复。"
                    + "你没有写工具，不得声称已修改或保存，除非 SubAgent 的结果明确成功。"
                    + "不要等待按钮授权，不要调用或描述 Dispatcher、计划、专家分派或提交阶段。"
                    + "调用任何 view 工具前，先调用 current_document 获取当前 doc_id，"
                    + "不要使用对话记忆或推断得来的旧 doc_id。"
                    + "invoke_subagent 的 task 里不要写死 doc_id（SubAgent 会自行获取）；"
                    + "禁止调用 get_subagent_result、steer_subagent——本系统只有前台同步 SubAgent，无后台 SubAgent。")
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
    if (docId == null) {
      openCurrentDocument();
      return;
    }
    // 复用同一 docId：避免主 Agent 记忆/已派发 task 里的 docId 因 seq 递增而漂移失效
    toolkit.session.reopen(docId, currentDocPath.toString());
  }

  /**
   * 主 Agent 调 {@code invoke_subagent} 后的编排层后处理：先尝试应用层兜底保存，再用服务端真相更正 SubAgent 的自述。
   *
   * <p>时序：本方法在 SubAgent 执行完毕、主 Agent 收到工具结果<b>之前</b>执行（框架的 {@link AfterToolCall}）。因此兜底若成功把
   * {@code execution.saved} 置 true，后续 {@link #correctSubAgentResult} 就会信任自述，主 Agent 收到的是真实的成功。
   *
   * @see #attemptFallbackSave 兜底保存
   * @see #correctSubAgentResult 真相更正
   */
  private AfterResult afterInvokeSubAgent(ToolCallContext context) {
    if (SUB_AGENT_TOOL.equals(context.toolName())) {
      attemptFallbackSave(activeExecution);
    }
    return correctSubAgentResult(context, activeExecution);
  }

  /**
   * 应用层兜底保存：SubAgent 漏调 {@code save_current_document}（LLM 幻觉/提前终止）时，替它跑质检并落盘。
   *
   * <p>触发条件：任务已委派给 SubAgent（{@code delegated}）但未保存（{@code !saved}），且未取消、未失败（质检 error 会把 {@code
   * failed} 置 true，此时不再兜底）。
   *
   * <p>实现：直接调用 {@code documentSessionTools.saveCurrentDocument()}，复用其「质检 error 拦截 + 落盘 + 设状态」完整门控。
   * 兜底走的质检门控与 SubAgent 自己调 save 完全一致，不会绕过质检。返回的渲染字符串在兜底场景丢弃。
   */
  private void attemptFallbackSave(DocumentExecutionState execution) {
    if (execution == null) {
      return;
    }
    if (!execution.delegated || execution.saved || execution.cancelled || execution.failed) {
      return;
    }
    try {
      documentSessionTools.saveCurrentDocument();
      log.info(
          "兜底保存：SubAgent 漏调 save_current_document，应用层兜底{}",
          execution.saved ? "成功" : "未保存（质检未通过或文档已关闭）");
    } catch (RuntimeException e) {
      log.warn("兜底保存异常: {}", rootMessage(e));
    }
  }

  /**
   * 用服务端真相更正 SubAgent 返回给主 Agent 的自述结果，避免主 Agent 把 SubAgent 的 LLM 幻觉（自称成功但未真正保存）当事实转述给用户。
   *
   * <p>框架语义：主 Agent 调 {@code invoke_subagent} 时，{@link AfterToolCall} 拿到的 {@link ToolCallContext#result()} 是
   * SubAgent 的最终文本（自述 JSON）。此时 SubAgent 已执行完毕，若它成功调了 {@code save_current_document}（或被应用层兜底保存），
   * {@link DocumentExecutionState#saved} 为 true；否则为 false（LLM 幻觉/提前终止）。本方法按 {@code execution} 真相覆盖自述：
   *
   * <ul>
   *   <li>非 {@code invoke_subagent} 工具：原样保留。
   *   <li>{@code saved=true}：信任自述，原样保留（含 summary/qualityReport）。
   *   <li>{@code saved=false}：强制 {@code success=false, changed=false}，{@code error} 填权威原因， 让主 Agent 只能据实报告失败。
   * </ul>
   *
   * <p>{@code saved=false} 时 {@code reopenCurrentDocument()} 仍会丢弃内存改动（正确语义），本方法只负责 让用户看到的消息与磁盘真相一致。
   */
  static AfterResult correctSubAgentResult(
      ToolCallContext context, DocumentExecutionState execution) {
    if (!SUB_AGENT_TOOL.equals(context.toolName())) {
      return AfterResult.keep();
    }
    if (execution != null && execution.saved) {
      return AfterResult.keep();
    }
    String claimedSummary = extractJsonField(context.result(), "summary");
    String claimedQuality = extractJsonField(context.result(), "qualityReport");
    String error =
        execution == null
            ? "实施任务未执行，未保存"
            : execution.cancelled
                ? "用户已取消，未保存"
                : execution.failed
                    ? "实施失败，未保存修改"
                    : "SubAgent 未调用 save_current_document，未保存";
    String corrected =
        "{\"success\":false,\"summary\":"
            + jsonQuote(claimedSummary)
            + ",\"changed\":false,\"qualityReport\":"
            + jsonQuote(claimedQuality)
            + ",\"error\":"
            + jsonQuote(error)
            + "}";
    return AfterResult.content(corrected);
  }

  /** 从 JSON 文本里提取一个字符串字段值（简易解析，失败返回空串）。复用 {@code DemoServer} 同款解析逻辑。 */
  private static String extractJsonField(String json, String key) {
    if (json == null || json.isBlank()) {
      return "";
    }
    int idx = json.indexOf("\"" + key + "\"");
    if (idx < 0) {
      return "";
    }
    int colon = json.indexOf(':', idx);
    if (colon < 0) {
      return "";
    }
    int start = json.indexOf('"', colon);
    if (start < 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = start + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '"' && json.charAt(i - 1) != '\\') {
        break;
      }
      if (c == '\\' && i + 1 < json.length()) {
        char next = json.charAt(i + 1);
        switch (next) {
          case 'n':
            sb.append('\n');
            break;
          case 't':
            sb.append('\t');
            break;
          case '"':
            sb.append('"');
            break;
          case '\\':
            sb.append('\\');
            break;
          default:
            sb.append(next);
        }
        i++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** 把字符串包装为 JSON 字符串字面量（转义引号和反斜杠）。 */
  private static String jsonQuote(String s) {
    if (s == null) {
      return "\"\"";
    }
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
