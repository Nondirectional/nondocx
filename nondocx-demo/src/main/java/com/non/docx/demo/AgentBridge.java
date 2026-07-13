package com.non.docx.demo;

import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;
import com.non.docx.toolkit.orchestration.DocxOrchestrator;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.PhaseCallback;
import com.non.docx.toolkit.orchestration.RouterResult;
import com.non.docx.toolkit.orchestration.RouterState;
import com.non.docx.toolkit.orchestration.agent.LlmTraceEvent;
import com.non.docx.toolkit.orchestration.body.BodyExecutor;
import com.non.docx.toolkit.orchestration.specialist.HeaderTocExecutor;
import com.non.docx.toolkit.orchestration.specialist.QualityExecutor;
import com.non.docx.toolkit.orchestration.specialist.RevisionExecutor;
import com.non.docx.toolkit.orchestration.table.TableExecutor;
import io.javalin.http.Context;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 桥接层（orchestrator 版）：持有 {@link DocxOrchestrator}，在每个编排阶段完成时推送 step 帧 给前端，前端据此渲染嵌入式进度卡。
 *
 * <p><b>架构。</b> LLM 只产出编辑计划（JSON operation），由 RouterAgent 合并、review、经 CommitCoordinator
 * 串行提交——写入有唯一安全边界。本桥接把编排过程的三阶段（分析→计划→提交） 实时推送给前端，让用户看到分步进度而非黑盒等待。
 *
 * <p><b>SSE 帧序列（一轮对话）：</b>
 *
 * <ol>
 *   <li>{@code step(analyze)} —— 分析完成，推文档结构摘要
 *   <li>{@code trace(prompt)} —— LLM prompt 构造完成（PLAN 进行中，一次性）
 *   <li>{@code trace(thinking_delta)} × N —— LLM thinking 逐字（PLAN 进行中）
 *   <li>{@code trace(content_delta)} × N —— LLM response 逐字（PLAN 进行中）
 *   <li>{@code trace(complete)} —— LLM 调用结束（成功/失败）
 *   <li>{@code step(plan)} —— 计划完成，推人话操作清单（PLAN 完成后，与 trace 共存）
 *   <li>{@code step(commit)} —— 提交完成（或失败），推执行结果
 *   <li>{@code doc_changed} —— save 成功后刷新 OO（仅 DONE）
 *   <li>{@code done} —— 本轮结束
 * </ol>
 *
 * <p><b>线程模型。</b> {@code DocxOrchestrator} 与底层 toolkit 为单会话设计，非线程安全；由路由层串行化保证。
 */
final class AgentBridge {

  private static final Logger log = LoggerFactory.getLogger(AgentBridge.class);

  /** Agent 是否可用（有无 API key）。 */
  private final boolean enabled;

  /** orchestrator 单例（若 enabled）。 */
  private final DocxOrchestrator orchestrator;

  /** 当前会话的 conversationId（绑定单活跃文档）。 */
  private String conversationId;

  /** 当前文档磁盘路径。 */
  private final Path currentDocPath;

  /** turnId 自增序号，每轮对话一个。 */
  private final AtomicLong turnSeq = new AtomicLong();

  AgentBridge(String apiKey, String currentDocPath) {
    this.currentDocPath = Path.of(currentDocPath);

    if (apiKey == null || apiKey.isBlank()) {
      this.enabled = false;
      this.orchestrator = null;
      this.conversationId = null;
      log.warn("未配置 DASHSCOPE_API_KEY,Agent 对话禁用(预览仍可用)");
      return;
    }

    this.enabled = true;
    LLM llm = new VLLM("http://10.100.10.21:40002/v1","qwen3-14b").maxCompletionTokens(65536).thinkingBudget(512);
    log.info("AgentBridge 初始化: model=qwen3.7-plus, maxTokens=65536, thinkingBudget=512");

    this.orchestrator = DocxOrchestrator.create();
    this.orchestrator.experts().register(new LlmDocxExpert(llm));
    this.orchestrator
        .executors()
        .register(new BodyExecutor(orchestrator.toolkit().body, orchestrator.toolkit()));
    this.orchestrator.executors().register(new TableExecutor(orchestrator.toolkit().table));
    this.orchestrator
        .executors()
        .register(new RevisionExecutor(orchestrator.toolkit().trackedChangeQuery));
    this.orchestrator.executors().register(new QualityExecutor());
    this.orchestrator.executors().register(new HeaderTocExecutor());
    log.debug("已注册专家: LlmDocxExpert; 执行器: Body/Table/Revision/Quality/HeaderToc");

    this.conversationId = orchestrator.open(this.currentDocPath);
    log.info("已打开文档会话: conversationId={}, path={}", conversationId, this.currentDocPath);
  }

  /** Agent 是否可用（有无 API key）。 */
  boolean enabled() {
    return enabled;
  }

  /** 重置会话：reopen 文档（代次递增，旧快照失效）。 */
  void clearMemory() {
    if (!enabled) return;
    orchestrator.reopen(conversationId);
    log.info("已清空 Agent 对话记忆 (reopen conversationId={})", conversationId);
  }

  /**
   * 在给定 HTTP 上下文上跑一轮 orchestrator 对话，分阶段推 step 帧到响应输出流。
   *
   * @param message 用户消息
   * @param ctx 当前请求的 Javalin 上下文
   * @param session 文档会话（用于 save 后 bumpKey）
   */
  void runStream(String message, Context ctx, DocSession session) {
    if (!enabled) {
      writeFrame(ctx, frame("error", "message", "未配置 DASHSCOPE_API_KEY，无法对话。"));
      writeFrame(ctx, frame("done"));
      flush(ctx);
      return;
    }
    String turnId = "turn-" + turnSeq.incrementAndGet();
    try {
      // 用阶段回调分步推送 step 帧
      PhaseCallback callback =
          event -> {
            Map<String, Object> stepFrame = buildStepFrame(turnId, event);
            writeFrame(ctx, stepFrame);
            flush(ctx);
          };

      // 用 trace 回调推送 LLM 内部过程（prompt / response delta / thinking delta / complete）
      Consumer<LlmTraceEvent> traceCb =
          trace -> {
            Map<String, Object> traceFrame = buildTraceFrame(turnId, trace);
            writeFrame(ctx, traceFrame);
            flush(ctx);
          };

      RouterResult result = orchestrator.run(conversationId, message, callback, traceCb);
      log.info("编排完成: state={}", result.state());
      if (result.state() == RouterState.FAILED) {
        // FAILED 时打印失败详情，方便排查（commit 异常、操作执行失败等）
        log.warn("编排失败摘要: {}", result.summaryText());
        for (Operation op : result.mergedPlan().operations()) {
          log.warn(
              "  操作: id={}, toolGroup={}, kind={}, payload={}, status={}",
              op.operationId(),
              op.toolGroup(),
              op.kind(),
              op.payload(),
              op.reviewStatus());
        }
      }

      if (result.state() == RouterState.DONE) {
        // DONE：save 落盘并推 doc_changed
        log.debug("状态 DONE,执行 save 落盘: {}", currentDocPath);
        String saveResult = orchestrator.save(conversationId, currentDocPath);
        boolean saved = saveResult != null && saveResult.contains("已保存");
        log.info("save 结果: {}", saveResult);
        if (saved) {
          String newKey = session.bumpKey();
          log.debug("文档已变更,bumpKey: {}", newKey);
          writeFrame(ctx, frame("doc_changed", "key", newKey));
          flush(ctx);
        }
      }
    } catch (RuntimeException e) {
      log.error("编排异常: conversationId={}", conversationId, e);
      writeFrame(ctx, frame("error", "message", rootMessage(e)));
      flush(ctx);
    } finally {
      writeFrame(ctx, frame("done"));
      flush(ctx);
    }
  }

  // ==================== step 帧构造 ====================

  /**
   * 把 {@link LlmTraceEvent} 转成前端可渲染的 trace 帧。
   *
   * <p>trace 帧与 step 帧是两种独立帧类型：step 是阶段级汇总，trace 是 token 级增量。前端按 {@code type}
   * 分发。
   */
  private static Map<String, Object> buildTraceFrame(String turnId, LlmTraceEvent trace) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "trace");
    m.put("turnId", turnId);
    m.put("agent", trace.agentName());
    switch (trace.kind()) {
      case PROMPT:
        m.put("event", "prompt");
        m.put("prompt", trace.prompt());
        break;
      case CONTENT_DELTA:
        m.put("event", "content_delta");
        m.put("delta", trace.delta());
        break;
      case THINKING_DELTA:
        m.put("event", "thinking_delta");
        m.put("delta", trace.delta());
        break;
      case COMPLETE:
        m.put("event", "complete");
        m.put("success", trace.success());
        if (!trace.success()) {
          m.put("error", trace.error());
        }
        if (trace.usage() != null) {
          m.put("usage", trace.usage().toString());
        }
        break;
      default:
        m.put("event", trace.kind().name().toLowerCase(java.util.Locale.ROOT));
    }
    return m;
  }

  /** 把 PhaseEvent 转成前端可渲染的 step 帧。 */
  private static Map<String, Object> buildStepFrame(String turnId, PhaseCallback.PhaseEvent event) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "step");
    m.put("turnId", turnId);
    m.put("phase", event.phase().name().toLowerCase(java.util.Locale.ROOT));
    m.put("status", event.success() ? "done" : "failed");

    switch (event.phase()) {
      case ANALYZE:
        m.put("title", "分析文档结构");
        m.put("detail", buildAnalyzeDetail(event.snapshot()));
        break;
      case PLAN:
        m.put("title", "生成编辑计划");
        m.put("operations", buildOperationList(event.mergedPlan()));
        break;
      case COMMIT:
        if (event.success()) {
          m.put("title", "执行完成");
          m.put("detail", buildCommitSuccessDetail(event.commitResult()));
        } else {
          m.put("title", "执行失败");
          m.put("error", event.failureMessage());
        }
        break;
      default:
        m.put("title", event.phase().name());
    }
    return m;
  }

  /** 分析阶段的文档结构摘要。 */
  private static String buildAnalyzeDetail(
      com.non.docx.toolkit.orchestration.DocumentSnapshot snapshot) {
    if (snapshot == null) return "";
    List<String> parts = new ArrayList<>();
    int paras = snapshot.overview().paragraphCount();
    int tables = snapshot.overview().tableCount();
    if (paras > 0) parts.add(paras + " 个段落");
    if (tables > 0) parts.add(tables + " 个表格");
    if (snapshot.overview().trackedChangeCount() > 0) {
      parts.add(snapshot.overview().trackedChangeCount() + " 处修订");
    }
    return parts.isEmpty() ? "空文档" : String.join("，", parts);
  }

  /** 计划阶段的操作清单（用人话描述）。 */
  private static List<Map<String, Object>> buildOperationList(
      com.non.docx.toolkit.orchestration.MergedPlan merged) {
    if (merged == null) return List.of();
    List<Map<String, Object>> ops = new ArrayList<>();
    for (Operation op : merged.operations()) {
      Map<String, Object> v = new LinkedHashMap<>();
      v.put("description", OperationDescriptor.describe(op));
      v.put("status", op.reviewStatus().name().toLowerCase(java.util.Locale.ROOT));
      ops.add(v);
    }
    return ops;
  }

  /** 提交成功的统计摘要。 */
  private static String buildCommitSuccessDetail(
      com.non.docx.toolkit.orchestration.commit.CommitResult commitResult) {
    if (commitResult == null) return "文档已更新";
    int n = commitResult.executed().size();
    return n + " 项操作已执行，文档已更新";
  }

  // ==================== SSE 帧输出 ====================

  private static void writeFrame(Context ctx, Map<String, Object> data) {
    try {
      String json = ctx.jsonMapper().toJsonString(data, Map.class);
      OutputStream out = ctx.res().getOutputStream();
      out.write(("data: " + json + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new RuntimeException("写 SSE 帧失败", e);
    }
  }

  private static void flush(Context ctx) {
    try {
      ctx.res().flushBuffer();
    } catch (java.io.IOException e) {
      throw new RuntimeException("flush 响应失败", e);
    }
  }

  private static Map<String, Object> frame(String type, String key, String value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    m.put(key, value);
    return m;
  }

  private static Map<String, Object> frame(String type) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    return m;
  }

  private static String rootMessage(Throwable e) {
    Throwable cur = e;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getMessage();
  }
}
