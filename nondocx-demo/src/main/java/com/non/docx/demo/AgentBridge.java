package com.non.docx.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.memory.MessageWindowChatMemory;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.DocxOrchestrator;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.MergedPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.agent.LlmTraceEvent;
import com.non.docx.toolkit.orchestration.body.BodyExecutor;
import com.non.docx.toolkit.orchestration.commit.CommitResult;
import com.non.docx.toolkit.orchestration.specialist.HeaderTocExecutor;
import com.non.docx.toolkit.orchestration.specialist.QualityAgent;
import com.non.docx.toolkit.orchestration.specialist.QualityExecutor;
import com.non.docx.toolkit.orchestration.specialist.RevisionExecutor;
import com.non.docx.toolkit.orchestration.table.TableExecutor;
import io.javalin.http.Context;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo 的协商入口。
 *
 * <p>普通聊天只允许主 LLM 读取快照并协商；只有 {@link #executeStream(String, Context, DocSession)} 接收一次性授权 token
 * 后才会调度专家与写入文档。
 */
final class AgentBridge {

  private static final Logger log = LoggerFactory.getLogger(AgentBridge.class);
  private static final Set<String> TOOL_GROUPS =
      Set.of("body", "table", "header-toc", "revision", "quality");

  private final boolean enabled;
  private final DocxOrchestrator orchestrator;
  private final LLM llm;
  private final Path currentDocPath;
  private final ObjectMapper json = new ObjectMapper();
  private final Agent primaryAgent;
  private final MessageWindowChatMemory primaryMemory;
  private final Map<String, LlmDocxExpert> experts = new LinkedHashMap<>();
  private final AtomicLong turnSeq = new AtomicLong();
  private final AtomicLong dispatchSeq = new AtomicLong();
  private final AtomicBoolean cancelRequested = new AtomicBoolean();
  private final TraceJournal journal = new TraceJournal(Path.of("target", "demo-work"));

  private String conversationId;
  private PendingAuthorization pending;
  private boolean executing;

  AgentBridge(String apiKey, String currentDocPath) {
    this.currentDocPath = Path.of(currentDocPath);
    if (apiKey == null || apiKey.isBlank()) {
      enabled = false;
      orchestrator = null;
      llm = null;
      primaryAgent = null;
      primaryMemory = null;
      conversationId = null;
      return;
    }
    enabled = true;
    llm = new DashscopeLLM("qwen3.7-plus").maxCompletionTokens(65536).thinkingBudget(512);
    orchestrator = DocxOrchestrator.create();
    orchestrator
        .executors()
        .register(new BodyExecutor(orchestrator.toolkit().body, orchestrator.toolkit()));
    orchestrator.executors().register(new TableExecutor(orchestrator.toolkit().table));
    orchestrator
        .executors()
        .register(new RevisionExecutor(orchestrator.toolkit().trackedChangeQuery));
    orchestrator.executors().register(new QualityExecutor());
    orchestrator.executors().register(new HeaderTocExecutor());
    for (String group : TOOL_GROUPS) {
      String name = "Llm" + group.replace("-", "_") + "Expert";
      experts.put(group, new LlmDocxExpert(llm, name, Set.of(group)));
    }
    conversationId = orchestrator.open(this.currentDocPath);
    primaryMemory =
        MessageWindowChatMemory.builder().conversationId(conversationId).maxMessages(24).build();
    ToolRegistry readTools = new ToolRegistry().scan(orchestrator.toolkit().view);
    primaryAgent =
        Agent.builder(llm, readTools)
            .memory(primaryMemory)
            .maxIterations(6)
            .systemPrompt(
                "你是主文档协商 Agent。仅可调用已注册的只读文档视图工具，不得写文档、不得调度专家。"
                    + "你绝不枚举、验证、否定或猜测任何写工具、operation、标题样式或参数是否可用；"
                    + "这些决定只由用户授权后的工具组专家负责。"
                    + "用户的编辑目标已清晰且不需要澄清时，概括目标并请求实施授权，不得提出降级方案。"
                    + "聊天文本中的同意、确认或开始不执行写入；它只能使界面重新展示“开始实施”按钮。"
                    + "不得声称系统已经安排专家、正在执行或已经修改文档。"
                    + "此时输出严格 JSON："
                    + "{\"reply\":\"给用户的中文回复\",\"requestAuthorization\":true}；"
                    + "其它情况 requestAuthorization 必须为 false。")
            .build();
  }

  boolean enabled() {
    return enabled;
  }

  void clearMemory() {
    if (!enabled) return;
    primaryMemory.clear();
    pending = null;
    cancelRequested.set(true);
    orchestrator.reopen(conversationId);
  }

  /** 协商消息；绝不调度专家或写文档。 */
  void runStream(String message, Context ctx, DocSession session) {
    if (!enabled) {
      emit(ctx, frame("error", "message", "未配置 DASHSCOPE_API_KEY，无法对话。"));
      emit(ctx, frame("done"));
      return;
    }
    if (executing) {
      emit(ctx, frame("error", "message", "正在实施，请取消或等待完成。"));
      emit(ctx, frame("done"));
      return;
    }
    PendingAuthorization previousAuthorization = pending;
    pending = null;
    String turnId = "turn-" + turnSeq.incrementAndGet();
    DocumentSnapshot snapshot = orchestrator.analyze(conversationId);
    String prompt = consultationPrompt(message, snapshot);
    emitTrace(ctx, turnId, LlmTraceEvent.ofPrompt("PrimaryConversationAgent", prompt));
    try {
      ChatResult result = primaryAgent.run(prompt, event -> tracePrimaryEvent(ctx, turnId, event));
      ConsultationReply reply = parseConsultationReply(result.content());
      if (previousAuthorization != null && isTextAuthorization(message)) {
        reply = new ConsultationReply("已收到你的同意。请点击下方“开始实施”按钮，系统才会派发专家并修改文档。", true);
      }
      emit(ctx, frame("assistant", "turnId", turnId, "message", reply.reply));
      if (reply.requestAuthorization) {
        String goal = previousAuthorization == null ? message : previousAuthorization.goal;
        pending = new PendingAuthorization(newToken(), goal, snapshot.sessionGeneration());
        emit(ctx, frame("authorization_required", "turnId", turnId, "token", pending.token));
      }
    } catch (RuntimeException e) {
      emit(ctx, frame("error", "message", rootMessage(e)));
    }
    emit(ctx, frame("done"));
  }

  /** 仅由“开始实施”按钮调用。 */
  void executeStream(String token, Context ctx, DocSession session) {
    String turnId = "execution-" + turnSeq.incrementAndGet();
    if (!enabled || pending == null || !pending.token.equals(token) || executing) {
      emit(ctx, frame("error", "message", "授权无效、已过期或正在实施。"));
      emit(ctx, frame("done"));
      return;
    }
    executing = true;
    cancelRequested.set(false);
    PendingAuthorization authorization = pending;
    pending = null;
    try {
      DocumentSnapshot snapshot = orchestrator.analyze(conversationId);
      if (snapshot.sessionGeneration() != authorization.generation) {
        emit(ctx, frame("blocked", "message", "文档已变化，请重新协商后授权。"));
        return;
      }
      Dispatch dispatch = createDispatch(authorization.goal, snapshot, ctx, turnId);
      if (dispatch.assignments.isEmpty()) {
        emit(ctx, frame("blocked", "message", "未生成可执行的专家分派，请继续协商。"));
        return;
      }
      emit(
          ctx,
          frame(
              "dispatch",
              "turnId",
              turnId,
              "dispatchId",
              dispatch.id,
              "assignments",
              dispatch.views()));
      log.info(
          "DispatchPlan {} 分派 {} 个专家: {}",
          dispatch.id,
          dispatch.assignments.size(),
          dispatch.views());
      List<CompletableFuture<ExpertPlan>> futures = new ArrayList<>();
      for (Assignment assignment : dispatch.assignments) {
        LlmDocxExpert expert = experts.get(assignment.toolGroup);
        futures.add(
            CompletableFuture.supplyAsync(
                () ->
                    expert.plan(
                        orchestratorSession(),
                        snapshot,
                        assignment.task,
                        traceConsumer(ctx, turnId))));
      }
      List<ExpertPlan> plans = new ArrayList<>();
      for (CompletableFuture<ExpertPlan> future : futures) {
        if (cancelRequested.get()) throw new CancelledException();
        ExpertPlan plan = future.join();
        log.info(
            "专家计划回收: agent={}, planId={}, operations={}",
            plan == null ? "null" : plan.agentName(),
            plan == null ? "null" : plan.planId(),
            plan == null ? 0 : plan.operations().size());
        if (plan != null && !plan.operations().isEmpty()) plans.add(plan);
      }
      List<String> sources = new ArrayList<>();
      List<Operation> operations = new ArrayList<>();
      for (ExpertPlan plan : plans) {
        sources.add(plan.planId());
        operations.addAll(plan.operations());
      }
      log.info(
          "DispatchPlan {} 合并完成: plans={}, operations={}",
          dispatch.id,
          plans.size(),
          operations.size());
      if (operations.isEmpty()) {
        emit(ctx, frame("blocked", "message", "专家未产生有效操作，请继续协商。"));
        return;
      }
      if (cancelRequested.get()) throw new CancelledException();
      MergedPlan merged = new MergedPlan(conversationId, dispatch.id, sources, operations);
      emit(ctx, frame("review", "turnId", turnId, "operations", operationViews(operations)));
      CommitResult committed = orchestrator.commitPlan(conversationId, merged);
      if (!committed.allSucceeded()) {
        orchestrator.reopen(conversationId);
        emit(ctx, frame("rolled_back", "message", committed.failureMessage()));
        return;
      }
      if (cancelRequested.get()) throw new CancelledException();
      String save = orchestrator.save(conversationId, currentDocPath);
      if (save == null || !save.contains("已保存")) {
        orchestrator.reopen(conversationId);
        emit(ctx, frame("rolled_back", "message", "保存失败：" + save));
        return;
      }
      emit(
          ctx,
          frame("commit", "turnId", turnId, "detail", committed.executed().size() + " 项操作已提交"));
      ExpertPlan qualityPlan =
          new QualityAgent(orchestrator.toolkit().qualityCheck)
              .plan(orchestratorSession(), orchestrator.analyze(conversationId), "质量验收", null);
      Operation quality = qualityPlan.operations().get(0);
      emit(
          ctx,
          frame(
              "quality",
              "turnId",
              turnId,
              "detail",
              String.valueOf(quality.payload().get("report"))));
      emit(ctx, frame("doc_changed", "key", session.bumpKey()));
    } catch (CancelledException e) {
      orchestrator.reopen(conversationId);
      emit(ctx, frame("rolled_back", "message", "用户已取消，本次修改未保存。"));
    } catch (RuntimeException e) {
      orchestrator.reopen(conversationId);
      emit(ctx, frame("error", "message", rootMessage(e)));
    } finally {
      executing = false;
      emit(ctx, frame("done"));
    }
  }

  void cancel() {
    cancelRequested.set(true);
  }

  private com.non.docx.toolkit.orchestration.session.OrchestratorSession orchestratorSession() {
    return orchestrator.session(conversationId);
  }

  private Dispatch createDispatch(
      String goal, DocumentSnapshot snapshot, Context ctx, String turnId) {
    String prompt =
        "你是实施分派器。用户已明确授权。基于目标和快照，只输出 JSON："
            + "{\"assignments\":[{\"toolGroup\":\"body|table|header-toc|revision|quality\",\"task\":\"具体任务\"}]}。"
            + "不得解释，不得输出未列出的工具组。分派规则：正文中的标题、段落、文本、对齐和样式一律是 body；"
            + "表格及单元格是 table；header-toc 仅用于页眉、页脚或目录；修订是 revision；检查是 quality。"
            + "例如“在文档开头添加 H1 标题”必须分派 body，绝不能分派 header-toc。\n目标："
            + goal
            + "\n快照："
            + snapshot.overview();
    emitTrace(ctx, turnId, LlmTraceEvent.ofPrompt("PrimaryConversationAgent", prompt));
    ChatResult result =
        llm.streamChat(
            List.of(Message.user(prompt)),
            chunk -> {
              if (chunk.hasContent())
                emitTrace(
                    ctx,
                    turnId,
                    LlmTraceEvent.ofContentDelta("PrimaryConversationAgent", chunk.deltaContent()));
              if (chunk.hasThinking())
                emitTrace(
                    ctx,
                    turnId,
                    LlmTraceEvent.ofThinkingDelta(
                        "PrimaryConversationAgent", chunk.deltaThinking()));
            });
    emitTrace(
        ctx, turnId, LlmTraceEvent.ofComplete("PrimaryConversationAgent", result.tokenUsage()));
    List<Assignment> assignments = new ArrayList<>();
    try {
      JsonNode array = json.readTree(stripJson(result.content())).path("assignments");
      if (array.isArray())
        for (JsonNode item : array) {
          String group = item.path("toolGroup").asText("");
          String task = item.path("task").asText("");
          group = normalizeDispatchGroup(group, task);
          if (TOOL_GROUPS.contains(group) && !task.isBlank())
            assignments.add(new Assignment(group, task));
        }
    } catch (Exception e) {
      log.warn("DispatchPlan 解析失败", e);
    }
    return new Dispatch("dispatch-" + dispatchSeq.incrementAndGet(), assignments);
  }

  /** 防止 LLM 把正文标题误解为页眉/目录领域；不改变真正页眉、页脚或目录请求。 */
  private static String normalizeDispatchGroup(String group, String task) {
    if (!"header-toc".equals(group) || task == null) return group;
    String text = task.toLowerCase(java.util.Locale.ROOT);
    boolean explicitHeaderDomain =
        text.contains("页眉") || text.contains("页脚") || text.contains("目录");
    boolean bodyHeading =
        text.contains("文档开头") || text.contains("正文") || text.contains("h1") || text.contains("标题");
    if (bodyHeading && !explicitHeaderDomain) return "body";
    return group;
  }

  private Consumer<LlmTraceEvent> traceConsumer(Context ctx, String turnId) {
    return event -> emitTrace(ctx, turnId, event);
  }

  private String consultationPrompt(String message, DocumentSnapshot snapshot) {
    return "当前文档句柄："
        + orchestratorSession().docId()
        + "。你可调用 view_* 读取内容。"
        + "\n文档初始快照："
        + snapshot.overview()
        + "\n用户："
        + message;
  }

  private void tracePrimaryEvent(Context ctx, String turnId, AgentEvent event) {
    if (event instanceof AgentEvent.TextDelta) {
      emitTrace(
          ctx,
          turnId,
          LlmTraceEvent.ofContentDelta(
              "PrimaryConversationAgent", ((AgentEvent.TextDelta) event).delta()));
      return;
    }
    if (event instanceof AgentEvent.ThinkingDelta) {
      emitTrace(
          ctx,
          turnId,
          LlmTraceEvent.ofThinkingDelta(
              "PrimaryConversationAgent", ((AgentEvent.ThinkingDelta) event).delta()));
      return;
    }
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("type", "trace");
    trace.put("turnId", turnId);
    trace.put("agent", "PrimaryConversationAgent");
    if (event instanceof AgentEvent.ToolStart) {
      AgentEvent.ToolStart tool = (AgentEvent.ToolStart) event;
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

  private ConsultationReply parseConsultationReply(String content) {
    try {
      JsonNode root = json.readTree(stripJson(content));
      if (root.isObject())
        return new ConsultationReply(
            root.path("reply").asText(""), root.path("requestAuthorization").asBoolean(false));
    } catch (Exception ignored) {
      // 保持原文显示，避免因模型非 JSON 回复丢失协商内容。
    }
    return new ConsultationReply(content == null ? "" : content.trim(), false);
  }

  private static boolean isTextAuthorization(String message) {
    if (message == null) return false;
    String normalized = message.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    return normalized.contains("同意")
        || normalized.contains("确认")
        || normalized.contains("授权")
        || normalized.contains("开始执行")
        || normalized.contains("开始实施");
  }

  private static String stripJson(String value) {
    return value == null
        ? ""
        : value.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
  }

  private static String newToken() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static List<Map<String, Object>> operationViews(List<Operation> operations) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Operation operation : operations) out.add(operation.shortView());
    return out;
  }

  private void emitTrace(Context ctx, String turnId, LlmTraceEvent event) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", "trace");
    data.put("turnId", turnId);
    data.put("agent", event.agentName());
    switch (event.kind()) {
      case PROMPT:
        data.put("event", "prompt");
        data.put("prompt", event.prompt());
        break;
      case CONTENT_DELTA:
        data.put("event", "content_delta");
        data.put("delta", event.delta());
        break;
      case THINKING_DELTA:
        data.put("event", "thinking_delta");
        data.put("delta", event.delta());
        break;
      case COMPLETE:
        data.put("event", "complete");
        data.put("success", event.success());
        break;
      default:
        data.put("event", event.kind().name().toLowerCase(java.util.Locale.ROOT));
    }
    emit(ctx, data);
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

  String traceReplay() {
    return journal.readAll();
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

  private static final class PendingAuthorization {
    final String token;
    final String goal;
    final long generation;

    PendingAuthorization(String token, String goal, long generation) {
      this.token = token;
      this.goal = goal;
      this.generation = generation;
    }
  }

  private static final class ConsultationReply {
    final String reply;
    final boolean requestAuthorization;

    ConsultationReply(String reply, boolean requestAuthorization) {
      this.reply = reply;
      this.requestAuthorization = requestAuthorization;
    }
  }

  private static final class Assignment {
    final String toolGroup;
    final String task;

    Assignment(String toolGroup, String task) {
      this.toolGroup = toolGroup;
      this.task = task;
    }
  }

  private static final class Dispatch {
    final String id;
    final List<Assignment> assignments;

    Dispatch(String id, List<Assignment> assignments) {
      this.id = id;
      this.assignments = List.copyOf(assignments);
    }

    List<Map<String, String>> views() {
      List<Map<String, String>> out = new ArrayList<>();
      for (Assignment a : assignments) out.add(Map.of("toolGroup", a.toolGroup, "task", a.task));
      return out;
    }
  }

  private static final class CancelledException extends RuntimeException {}
}
