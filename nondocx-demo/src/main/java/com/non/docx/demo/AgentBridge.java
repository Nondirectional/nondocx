package com.non.docx.demo;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.agent.AfterResult;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单文档 Agent 的 demo 对话入口。
 *
 * <p><b>架构</b>：单个 {@link Agent} 持有全部只读 + 写 + 质检工具，但<b>不持有保存工具</b>。保存（落盘）由应用层在 agent 主循环结束（{@link
 * AgentEvent.Complete}）时强制执行——见 {@link #flushIfDirty}。这从源头消灭了 "LLM 漏调 save / 谎报成功"导致的自述与磁盘真相鸿沟，不再需要
 * SubAgent + 真相纠正编排层。
 *
 * <p><b>dirty 检测</b>：写工具的 {@code AfterToolCall} 把 {@code dirty} 置 true，标记本轮发生过文档写入。只读工具
 * （view/read/get/list/search/check 等）不置 dirty。{@code Complete} 时仅在 dirty 时 flush。
 *
 * <p><b>记忆污染治理</b>：写工具结果在 {@code AfterToolCall} 瘦身为一行确认；{@code check_quality} 原文挂 {@link
 * Message#note}（{@code llmVisible=false}，不占窗口、不喂 LLM），紧凑摘要作真工具结果喂 LLM。
 *
 * <p>详见 {@code .trellis/spec/backend/agent-single.md}。
 */
final class AgentBridge {

  private static final Logger log = LoggerFactory.getLogger(AgentBridge.class);

  /**
   * 只读工具名前缀/集合。匹配这些的工具<b>不</b>置 dirty——它们不改文档。 其余工具（body/table/修订创作等写工具）一律置 dirty。安全默认：未知工具视为写（多跑一次
   * flush 只是浪费，不丢数据；漏标 dirty 会丢编辑）。
   */
  private static final Set<String> READONLY_TOOL_PREFIXES =
      Set.of("view_", "read_", "get_", "list_", "search_", "check_");

  private static final Set<String> READONLY_TOOL_EXACT =
      Set.of("current_document", "describe_capabilities");

  private final boolean enabled;
  private final DocxToolkit toolkit;
  private final LLM llm;
  private final Path currentDocPath;
  private final Agent agent;
  private final MessageWindowChatMemory memory;
  private final DocumentTools documentTools;
  private final AtomicBoolean cancelRequested = new AtomicBoolean();
  private final AtomicLong turnSeq = new AtomicLong();
  private final TraceJournal journal = new TraceJournal(Path.of("target", "demo-work"));

  /** 每轮 flush 需要 emit 回前端时的上下文，{@code runStream} 开头置位、结尾清空。 */
  private Context activeCtx;

  private String activeTurnId;
  private DocSession activeSession;

  private String docId;

  /** 本轮状态，{@code runStream} 开头重置。 */
  private final AtomicBoolean dirty = new AtomicBoolean();

  private final AtomicBoolean qualityChecked = new AtomicBoolean();
  private final AtomicReference<String> lastQualityReport = new AtomicReference<>("");

  AgentBridge(String apiKey, String currentDocPath) {
    this.currentDocPath = Path.of(currentDocPath);
    if (apiKey == null || apiKey.isBlank()) {
      enabled = false;
      toolkit = null;
      llm = null;
      agent = null;
      memory = null;
      documentTools = null;
      return;
    }
    enabled = true;
    llm = new DashscopeLLM("qwen3.7-plus").maxCompletionTokens(65536).thinkingBudget(512);
    toolkit = new DocxToolkit();
    openCurrentDocument();
    memory =
        MessageWindowChatMemory.builder().conversationId("demo-document").maxMessages(24).build();
    documentTools = new DocumentTools(toolkit, () -> docId, () -> this.currentDocPath);

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

    agent =
        Agent.builder(llm, tools)
            .memory(memory)
            .maxIterations(24)
            .executor(null)
            .addBeforeToolCall(this::beforeToolCall)
            .addAfterToolCall(this::afterToolCall)
            .systemPrompt(
                "你是文档 Agent。先调用 current_document 获取唯一允许使用的 doc_id，再按任务需要工作。"
                    + "普通咨询只调用只读 view/read 工具；用户提出明确编辑请求时，直接调用写工具修改文档，"
                    + "不得调用或寻找 open_docx、close_docx、save_docx 或任何保存工具——保存由系统在结束后自动完成。"
                    + "完成修改后调用 check_quality 检查质量。若任一工具失败，停止并说明失败。"
                    + "不要调用或描述 Dispatcher、计划、专家分派、提交阶段或 SubAgent。"
                    + "不要使用对话记忆或推断得来的旧 doc_id。")
            .build();
  }

  boolean enabled() {
    return enabled;
  }

  void clearMemory() {
    if (!enabled) return;
    memory.clear();
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
    dirty.set(false);
    qualityChecked.set(false);
    lastQualityReport.set("");
    activeCtx = ctx;
    activeTurnId = turnId;
    activeSession = session;
    try {
      emitTrace(ctx, turnId, "prompt", "DocumentAgent", message);
      ChatResult result = agent.run(message, event -> traceEvent(ctx, turnId, event));
      emit(ctx, frame("assistant", "turnId", turnId, "message", result.content()));
    } catch (RuntimeException e) {
      reopenCurrentDocument();
      log.warn("Agent 执行失败: {}", rootMessage(e));
      emit(ctx, frame("error", "turnId", turnId, "message", rootMessage(e)));
    } finally {
      activeCtx = null;
      activeTurnId = null;
      activeSession = null;
      emit(ctx, frame("done"));
    }
  }

  void cancel() {
    cancelRequested.set(true);
  }

  String traceReplay() {
    return journal.readAll();
  }

  // ============ 工具拦截 ============

  /**
   * 取消协作式拦截：{@code cancelRequested} 时 block 后续写工具。block 消息含强指令，降低 agent 谎称"已完成"的概率。 实际成败口径以 {@code
   * edit_outcome} 系统帧为准（服务端状态钉死）。
   */
  private BeforeResult beforeToolCall(ToolCallContext context) {
    if (!cancelRequested.get()) return BeforeResult.pass();
    if (!isReadonly(context.toolName())) {
      return BeforeResult.block("用户已取消，禁止继续调用文档写工具；不要声称已完成或已保存。");
    }
    return BeforeResult.pass();
  }

  /**
   * 写工具结果瘦身（α）+ dirty 检测 + 质检原文挂 note（β）。
   *
   * <ul>
   *   <li>写工具：置 dirty，结果改写为一行确认（LLM 刚写完，不需回显全文，省 24 条窗口预算）。
   *   <li>{@code check_quality}：紧凑摘要喂 LLM，原文挂 {@link Message#note} 给 UI（不占窗口、不喂 LLM）。
   *   <li>只读工具：原样保留。
   * </ul>
   */
  private AfterResult afterToolCall(ToolCallContext context) {
    String tool = context.toolName();
    if (isReadonly(tool)) {
      if ("check_quality".equals(tool)) {
        qualityChecked.set(true);
        String full = context.result();
        lastQualityReport.set(full);
        // β：质检原文挂 note 供 UI 回放/ edit_outcome 引用；摘要作真工具结果喂 LLM。
        if (memory != null && full != null && !full.isBlank()) {
          memory.add(Message.note("quality_report", full));
        }
        return AfterResult.content(summarizeQuality(full));
      }
      return AfterResult.keep();
    }
    // 写工具：置 dirty + 瘦身
    dirty.set(true);
    return AfterResult.content(slimWriteResult(tool, context.result()));
  }

  /** 工具名是否只读（不改文档）。匹配前缀或精确集合；未知工具视为写（安全默认）。 */
  static boolean isReadonly(String toolName) {
    if (toolName == null) return false;
    if (READONLY_TOOL_EXACT.contains(toolName)) return true;
    for (String prefix : READONLY_TOOL_PREFIXES) {
      if (toolName.startsWith(prefix)) return true;
    }
    return false;
  }

  /** 写工具结果瘦身：保留成败，去掉大段 data，只留一行确认。 */
  static String slimWriteResult(String tool, String raw) {
    if (raw == null || raw.isBlank()) {
      return "已调用 " + tool + "（无返回）";
    }
    ToolResultParser.Snapshot parsed = ToolResultParser.parse(raw);
    if (parsed == null) {
      return "已调用 " + tool;
    }
    return (parsed.success() ? "✓ " : "✗ ") + tool + "：" + parsed.message();
  }

  /** 质检摘要：从渲染串里取成败，附简短提示，避免整份报告占满 LLM 上下文。 */
  static String summarizeQuality(String raw) {
    if (raw == null || raw.isBlank()) return "质检无报告";
    ToolResultParser.Snapshot parsed = ToolResultParser.parse(raw);
    if (parsed == null) return "质检完成（详见报告）";
    String msg = parsed.message();
    // 截断超长 message，给 LLM 留预算
    if (msg != null && msg.length() > 240) {
      msg = msg.substring(0, 240) + "…";
    }
    return (parsed.success() ? "✓ 质检通过" : "⚠ 质检发现问题")
        + "："
        + (msg == null ? "" : msg)
        + "。完整报告已存档。";
  }

  // ============ 循环结束 flush（代码强制保存） ============

  /**
   * {@link AgentEvent.Complete} 时强制 flush：若本轮 dirty，运行质检门控并落盘，发 {@code edit_outcome} 系统帧。
   *
   * <p>这是回归的核心——保存不再由 LLM 触发，消除"漏调 save / 谎报成功"。流程：
   *
   * <ol>
   *   <li>非 dirty：纯咨询，发 noop 帧，零落盘。
   *   <li>cancelled：reopen 回滚，发 cancelled 帧。
   *   <li>dirty 但漏调 check_quality：代码兜底，视同跑过质检。
   *   <li>复用 {@link DocumentTools#saveCurrentDocument(boolean)} 的"质检 error 拒绝 / warning 允许 + 落盘"门控。
   *   <li>质检 error 或保存失败：reopen 回滚。
   *   <li>成功：发 doc_changed。
   * </ol>
   */
  private void onAgentComplete(Context ctx, String turnId, DocSession session) {
    if (!dirty.get()) {
      emit(
          ctx,
          frame(
              "edit_outcome",
              "turnId",
              turnId,
              "status",
              "noop",
              "changed",
              false,
              "qualityReport",
              "",
              "error",
              ""));
      return;
    }
    boolean cancelled = cancelRequested.get();
    if (!qualityChecked.get()) {
      // 漏调 check_quality 兜底：saveCurrentDocument 内部会跑质检，此处无需额外调用
      log.debug("本轮 dirty 但未显式 check_quality，保存门控将自动补跑质检");
    }
    DocumentTools.SaveOutcome outcome = documentTools.saveCurrentDocument(cancelled);
    if (!outcome.saved
        && (cancelled
            || outcome.error.contains("错误")
            || outcome.error.contains("失败")
            || outcome.error.contains("取消"))) {
      reopenCurrentDocument();
    }
    String status = deriveStatus(cancelled, outcome);
    emit(
        ctx,
        frame(
            "edit_outcome",
            "turnId",
            turnId,
            "status",
            status,
            "changed",
            outcome.changed,
            "qualityReport",
            lastQualityReport.get(),
            "error",
            outcome.error));
    if (outcome.saved) {
      emit(ctx, frame("doc_changed", "key", session.bumpKey()));
    }
  }

  static String deriveStatus(boolean cancelled, DocumentTools.SaveOutcome outcome) {
    if (cancelled) return "cancelled";
    if (outcome.saved) return "saved";
    return "rolled_back";
  }

  // ============ 文档会话 ============

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
    // 复用同一 docId：避免 agent 记忆里的 docId 因 seq 递增而漂移失效
    toolkit.session.reopen(docId, currentDocPath.toString());
  }

  // ============ SSE trace ============

  private void traceEvent(Context ctx, String turnId, AgentEvent event) {
    if (event instanceof AgentEvent.Complete) {
      // 循环结束：强制 flush 保存，发 edit_outcome
      onAgentComplete(ctx, turnId, activeSession);
      Map<String, Object> trace = new LinkedHashMap<>();
      trace.put("type", "trace");
      trace.put("turnId", turnId);
      trace.put("agent", "DocumentAgent");
      trace.put("event", "complete");
      trace.put("success", true);
      emit(ctx, trace);
      return;
    }
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("type", "trace");
    trace.put("turnId", turnId);
    trace.put("agent", "DocumentAgent");
    if (event instanceof AgentEvent.TextDelta) {
      trace.put("event", "content_delta");
      trace.put("delta", ((AgentEvent.TextDelta) event).delta());
    } else if (event instanceof AgentEvent.ThinkingDelta) {
      trace.put("event", "thinking_delta");
      trace.put("delta", ((AgentEvent.ThinkingDelta) event).delta());
    } else if (event instanceof AgentEvent.ToolStart) {
      AgentEvent.ToolStart tool = (AgentEvent.ToolStart) event;
      trace.put("event", "tool_start");
      trace.put("tool", tool.toolName());
      trace.put("arguments", tool.arguments());
    } else if (event instanceof AgentEvent.ToolEnd) {
      AgentEvent.ToolEnd tool = (AgentEvent.ToolEnd) event;
      trace.put("event", "tool_end");
      trace.put("tool", tool.toolName());
      trace.put("result", tool.result());
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
