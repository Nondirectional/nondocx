package io.github.nondirectional.docx.examples.eval;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.agent.ContextSelector;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.TokenUsage;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;
import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolRegistry;
import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.toolkit.DocxToolkit;
import io.github.nondirectional.docx.toolkit.result.ToolResult;
import io.github.nondirectional.docx.toolkit.result.ToolResultParser;
import io.github.nondirectional.docx.toolkit.result.ToolResultRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单任务评测运行器：headless 组装当前 demo 的 Agent 工具面，跑一条自然语言任务， 应用层保存，再用确定性 check 读取最终 docx。
 *
 * <p><b>与 demo 的关系。</b> 工具面、system prompt、只读复审 SubAgent 都对齐 {@code AgentBridge}；区别是不经过
 * Javalin/SSE，且不加载 6 条 Skill（第一版基线隔离工具面）。 保存同样不是 LLM 工具，而是运行结束后由本类强制调用，模拟 demo 的 {@code
 * AgentEvent.Complete} flush。
 *
 * <p><b>线程模型。</b> 每个任务独立 {@link DocxToolkit}、独立 temp 目录、独立 daemon executor。超时会取消 future 并记录 {@code
 * timeout}；daemon 线程不会阻塞 JVM 退出。
 */
public final class EvalRunner {

  private static final int MAX_TOOL_RESULT_CHARS = 3000;
  private static final int MAX_FINAL_MESSAGE_CHARS = 2000;
  private static final int MAX_COMPLETION_TOKENS = 8192;

  private static final String SYSTEM_PROMPT =
      "你是文档 Agent。先调用 current_document 获取唯一允许使用的 doc_id，再按任务需要工作。"
          + "普通咨询只调用只读 view/read 工具；用户提出明确编辑请求时，直接调用写工具修改文档，"
          + "不得调用或寻找 open_docx、close_docx、save_docx 或任何保存工具——保存由系统在结束后自动完成。"
          + "完成修改后调用 review_intent 复审用户期望的修改是否已经达成。若任一工具失败，停止并说明失败。"
          + "不要调用或描述 Dispatcher、计划、专家分派或提交阶段。"
          + "不要使用对话记忆或推断得来的旧 doc_id。";

  /**
   * compiler 面的 system prompt：文档生成走「一次 Markdown 提交」而非逐 API 编辑。
   *
   * <p>核心指令是让模型把整篇文档写成 Markdown 一次 create_document——排版（中文字体双槽、 行距、缩进、表头样式）由 style_profile
   * 兜底，模型只表达结构与内容。
   */
  private static final String COMPILER_SYSTEM_PROMPT =
      "你是文档 Agent。当前对话只有一个文档（先调用 current_document 获取 doc_id）。"
          + "用户要求生成/重写整份文档时，把完整内容写成 Markdown，一次调用 create_document 生成"
          + "（它会替换当前文档，句柄不变）。Markdown 支持 # 标题、**粗体**、无序/有序列表、"
          + "|表格|（首行为表头）、{align=center} 属性；排版（黑体标题、宋体小四正文、1.5 倍行距、"
          + "首行缩进、表头加粗灰底）由 style_profile=report-cn 自动完成，不要在 Markdown 里描述字体字号。"
          + "可用 read_document 查看当前文档（段落/表格带 pN/tN 短 ID），validate_document 做质量检查。"
          + "注意：read_document 的输出是 Markdown 视图——# 前缀、{align=…}、<!-- pN --> 都是"
          + "结构/属性标记，不是正文文本，不要试图删除或替换它们。"
          + "编辑既有文档时，把一批修改整理成 ops 一次调用 edit_document（锚点用短 ID，"
          + "插入内容用 Markdown 块），不要逐段零散修改。"
          + "给文字加粗/倾斜（含替换后加粗）用行内标记：replace_text 的 text 写 **加粗**/*倾斜*，"
          + "如把「待定」换成加粗的「通过」就是 text=**通过**；整段全加粗可用 set_style 的 bold=true。"
          + "用户要求「修订模式/以修订」修改时用 tracked.replace / tracked.insert / tracked.delete op"
          + "（必传 author=修订作者，如用户给了作者名就用它；"
          + "tracked.insert/tracked.replace 的 text 同样写行内标记，如以修订把「待定」换成加粗的"
          + "「通过」就是 text=**通过**）；"
          + "用户要求在页脚插页码时用 footer.set_page_number op（format 如「第 {page} 页」）；"
          + "用户要求加批注/写意见时用 comment.add op（anchor+text 必传，author=批注作者，不改正文）。"
          + "当前文档是模板（含 {{key}}/{{#key}} 占位符）且用户给出数据时，一次调用 fill_template 填充"
          + "（缺 key 会保留原样并在 warnings 提示，补数据重试即可）。"
          + "需要检查分页/排版等视觉效果时调用 render_preview（转 PDF，需要 LibreOffice）。"
          + "写工具（create/edit/fill）的结果自带质量检查反馈（qualityIssues）——这是提示性信息，"
          + "除非用户明确要求排版规范，无需为它追加修改，也不必再单独调用 validate_document。"
          + "不得调用或寻找 open_docx、close_docx、save_docx 或任何保存工具——保存由系统在结束后自动完成。"
          + "完成后调用 review_intent 复审用户期望的修改是否已经达成。若任一工具失败，停止并说明失败。";

  private static final String REVIEW_SYSTEM_PROMPT =
      "你是文档意图复审员。你会收到用户本轮对文档的修改请求，并通过只读 view_* 工具读取文档当前内容。"
          + "你的任务是判断文档是否已经完成了用户期望的修改，输出三态结论与差异说明。\n"
          + "严格按以下格式输出，不要输出其他内容：\n"
          + "<verdict>达成|部分达成|未达成</verdict>\n"
          + "<diff>\n"
          + "- 已完成: （列出用户请求中已落地的改动；无则写「无」）\n"
          + "- 缺失/偏差: （列出未做或与请求不符的部分；无则写「无」）\n"
          + "</diff>\n"
          + "判定标准：用户请求的所有改动都正确落地=达成；部分落地或有小偏差=部分达成；核心改动未做或做错=未达成。"
          + "只读取文档，不要调用任何写工具。";

  private final EvalConfig config;

  public EvalRunner(EvalConfig config) {
    this.config = config;
  }

  /** 当前 demo 工具面的暴露数量（普通工具 + 1 个只读复审 SubAgent）。 */
  public static int exposedToolCount() {
    DocxToolkit toolkit = new DocxToolkit();
    return baseRegistry(toolkit, "doc-0", "full").getRegularTools().size() + 1;
  }

  /** compiler 工具面的暴露数量（current_document + 3 编译工具 + 1 个只读复审 SubAgent）。 */
  public static int compilerToolCount() {
    DocxToolkit toolkit = new DocxToolkit();
    return baseRegistry(toolkit, "doc-0", "compiler").getRegularTools().size() + 1;
  }

  /**
   * 跑一条任务。
   *
   * @param task 任务定义
   * @param taskDir 该任务产物目录（in/out/trace/result）
   * @return 单任务结果；不抛业务异常，失败以 {@link EvalResult.TaskResult} 返回
   */
  public EvalResult.TaskResult run(EvalTask task, Path taskDir) {
    long started = System.currentTimeMillis();
    List<EvalResult.ToolCall> calls = new CopyOnWriteArrayList<>();
    List<String> traceLines = new CopyOnWriteArrayList<>();
    AtomicLong promptTokens = new AtomicLong();
    AtomicLong completionTokens = new AtomicLong();

    Path in = taskDir.resolve("in.docx");
    Path out = taskDir.resolve("out.docx");
    DocxToolkit toolkit = null;
    String docId = null;
    boolean timedOut = false;

    try {
      Files.createDirectories(taskDir);
      task.writeSeed(in);
      trace(traceLines, "seed", "path", in.toString());

      toolkit = new DocxToolkit();
      String openRaw = toolkit.session.openDocx(in.toString());
      ToolResultParser.Snapshot open = ToolResultParser.parse(openRaw);
      if (open == null || !open.success() || open.dataText() == null) {
        return failure(
            task,
            taskDir,
            calls,
            traceLines,
            EvalResult.FailureKind.AGENT_ERROR,
            "打开初始文档失败: " + openRaw,
            started,
            promptTokens.get(),
            completionTokens.get(),
            null);
      }
      docId = open.dataText();
      trace(traceLines, "open", "docId", docId);

      VLLM llm = createLlm();
      AgentOutcome outcome;
      try {
        outcome =
            runWithTimeout(
                task, docId, toolkit, llm, calls, traceLines, promptTokens, completionTokens);
      } catch (TimeoutException e) {
        timedOut = true;
        trace(traceLines, "timeout", "seconds", config.timeoutSeconds());
        EvalResult.TaskResult result =
            failure(
                task,
                taskDir,
                calls,
                traceLines,
                EvalResult.FailureKind.TIMEOUT,
                "任务超时: " + config.timeoutSeconds() + "s",
                started,
                promptTokens.get(),
                completionTokens.get(),
                null);
        return result;
      }

      trace(
          traceLines,
          "final",
          "content",
          truncate(outcome.result.content(), MAX_FINAL_MESSAGE_CHARS));
      String saveRaw = toolkit.session.saveDocx(docId, out.toString());
      ToolResultParser.Snapshot save = ToolResultParser.parse(saveRaw);
      if (save == null || !save.success()) {
        trace(traceLines, "save_error", "message", saveRaw);
        return failure(
            task,
            taskDir,
            calls,
            traceLines,
            EvalResult.FailureKind.SAVE_ERROR,
            "保存输出文档失败: " + saveRaw,
            started,
            promptTokens.get(),
            completionTokens.get(),
            outcome.result.content());
      }
      trace(traceLines, "saved", "path", out.toString());

      List<EvalResult.CheckResult> checks = runChecks(task, out);
      boolean passed = checks.stream().allMatch(EvalResult.CheckResult::passed);
      EvalResult.FailureKind failureKind = EvalResult.FailureKind.NONE;
      if (!passed) {
        failureKind =
            calls.stream().anyMatch(call -> !call.success())
                ? EvalResult.FailureKind.TOOL_ERROR
                : EvalResult.FailureKind.CHECK_FAILED;
      }
      int invalid = (int) calls.stream().filter(call -> !call.success()).count();
      EvalResult.TaskResult result =
          new EvalResult.TaskResult(
              task,
              passed,
              failureKind,
              checks,
              calls,
              invalid,
              promptTokens.get(),
              completionTokens.get(),
              System.currentTimeMillis() - started,
              truncate(outcome.result.content(), MAX_FINAL_MESSAGE_CHARS),
              "",
              out.toAbsolutePath().toString());
      writeTaskArtifacts(taskDir, result, traceLines);
      return result;
    } catch (RuntimeException e) {
      trace(traceLines, "error", "message", rootMessage(e));
      return failure(
          task,
          taskDir,
          calls,
          traceLines,
          EvalResult.FailureKind.AGENT_ERROR,
          "运行异常: " + rootMessage(e),
          started,
          promptTokens.get(),
          completionTokens.get(),
          null);
    } catch (IOException e) {
      trace(traceLines, "error", "message", rootMessage(e));
      return failure(
          task,
          taskDir,
          calls,
          traceLines,
          EvalResult.FailureKind.AGENT_ERROR,
          "IO 异常: " + rootMessage(e),
          started,
          promptTokens.get(),
          completionTokens.get(),
          null);
    } finally {
      // 超时路径不关闭：agent 线程可能仍在用这份 toolkit；它是每任务独立实例，daemon 线程不阻塞退出。
      if (!timedOut && toolkit != null && docId != null) {
        try {
          toolkit.session.closeDocx(docId);
        } catch (RuntimeException ignored) {
          // 关闭失败不影响评测结论
        }
      }
    }
  }

  // ==================== Agent 执行 ====================

  private VLLM createLlm() {
    VLLM llm =
        config.apiKey().isBlank()
            ? new VLLM(config.baseUrl(), config.model())
            : new VLLM(config.baseUrl(), config.apiKey(), config.model());
    llm.maxCompletionTokens(MAX_COMPLETION_TOKENS);
    llm.temperature(config.temperature());
    llm.thinkingBudget(config.thinkingBudget());
    return llm;
  }

  private AgentOutcome runWithTimeout(
      EvalTask task,
      String docId,
      DocxToolkit toolkit,
      LLM llm,
      List<EvalResult.ToolCall> calls,
      List<String> traceLines,
      AtomicLong promptTokens,
      AtomicLong completionTokens)
      throws TimeoutException {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "eval-" + task.id());
              thread.setDaemon(true);
              return thread;
            });
    Future<AgentOutcome> future =
        executor.submit(
            () ->
                executeAgent(
                    task, docId, toolkit, llm, calls, traceLines, promptTokens, completionTokens));
    try {
      return future.get(config.timeoutSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("评测被中断", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new RuntimeException(rootMessage(cause), cause);
    } finally {
      executor.shutdownNow();
    }
  }

  private AgentOutcome executeAgent(
      EvalTask task,
      String docId,
      DocxToolkit toolkit,
      LLM llm,
      List<EvalResult.ToolCall> calls,
      List<String> traceLines,
      AtomicLong promptTokens,
      AtomicLong completionTokens) {
    String surface = config.surface();
    ToolRegistry tools = baseRegistry(toolkit, docId, surface);
    tools
        .registerSubAgent("review_intent", "[复审] 对比用户本轮修改请求与文档现状，返回达成/部分达成/未达成结论及差异说明。修改完成后调用。")
        .systemPrompt(REVIEW_SYSTEM_PROMPT)
        .toolRegistry(new ToolRegistry().scan(toolkit.view))
        .llm(llm)
        .maxIterations(6)
        .contextSelector(reviewContext(task.prompt(), docId))
        .build();

    ChainCallback callback =
        new ChainCallback() {
          @Override
          public void onLlmComplete(LlmCompleteEvent event) {
            TokenUsage usage = event.tokenUsage();
            if (usage != null) {
              promptTokens.addAndGet(usage.promptTokens());
              completionTokens.addAndGet(usage.completionTokens());
            }
          }
        };

    Agent agent =
        Agent.builder(llm, tools)
            .maxIterations(config.maxIterations())
            .executor(null)
            .systemPrompt("compiler".equals(surface) ? COMPILER_SYSTEM_PROMPT : SYSTEM_PROMPT)
            .callback(callback)
            .build();

    Map<String, PendingTool> pending = new LinkedHashMap<>();
    ChatResult result =
        agent.run(
            task.prompt(),
            event -> {
              if (event instanceof AgentEvent.ToolStart) {
                AgentEvent.ToolStart start = (AgentEvent.ToolStart) event;
                pending.put(start.toolName(), new PendingTool(start.arguments()));
                trace(
                    traceLines,
                    "tool_start",
                    "tool",
                    start.toolName(),
                    "arguments",
                    truncate(start.arguments(), 2000));
              } else if (event instanceof AgentEvent.ToolEnd) {
                AgentEvent.ToolEnd end = (AgentEvent.ToolEnd) event;
                PendingTool startedTool = pending.remove(end.toolName());
                long durationMs = startedTool == null ? 0 : startedTool.elapsedMs();
                String arguments = startedTool == null ? "" : startedTool.arguments;
                boolean success = isToolSuccess(end.toolName(), end.result());
                EvalResult.ToolCall call =
                    new EvalResult.ToolCall(
                        end.toolName(),
                        arguments,
                        truncate(end.result(), MAX_TOOL_RESULT_CHARS),
                        success,
                        durationMs);
                calls.add(call);
                trace(
                    traceLines,
                    "tool_end",
                    "tool",
                    end.toolName(),
                    "success",
                    success,
                    "durationMs",
                    durationMs,
                    "result",
                    truncate(end.result(), MAX_TOOL_RESULT_CHARS));
              }
            });
    return new AgentOutcome(result);
  }

  /** 复审 SubAgent 上下文：只注入本轮请求 + docId，不带主 Agent 的写作过程。 */
  private static ContextSelector reviewContext(String request, String docId) {
    return (history, newMessage, args) ->
        List.of(
            Message.user(
                "用户本轮对文档的修改请求：\n"
                    + request
                    + "\n\n当前文档句柄 doc_id = "
                    + docId
                    + "。请用这个 doc_id 调用 view_* 只读工具（如 view_stats/view_text）读取文档当前内容，"
                    + "判断上述请求的修改是否已经达成。"));
  }

  // ==================== 工具面 ====================

  /**
   * 按工具面组装 registry。
   *
   * <p>{@code full}：demo 对齐面（current_document + view/body/table/header/tracker，58 入口）。 {@code
   * compiler}：编译器面（current_document + create/read/validate_document，4 入口）。 两个面都不含保存工具；review
   * SubAgent 由调用方在返回的 registry 上注册。
   */
  private static ToolRegistry baseRegistry(DocxToolkit toolkit, String docId, String surface) {
    ToolRegistry registry = new ToolRegistry().scan(new CurrentDocumentTool(docId));
    if ("compiler".equals(surface)) {
      return registry.scan(toolkit.compiler);
    }
    return registry
        .scan(toolkit.view)
        .scan(toolkit.body)
        .scan(toolkit.table)
        .scan(toolkit.headerFooterToc)
        .scan(toolkit.trackedChangeQuery)
        .scan(toolkit.trackedChangeAuthoring);
  }

  /** 只读当前文档句柄工具；与 demo 的 {@code DocumentTools#currentDocument} 行为一致。 */
  public static final class CurrentDocumentTool {

    private final String docId;

    public CurrentDocumentTool(String docId) {
      this.docId = docId;
    }

    @ToolDef(
        name = "current_document",
        description = "返回当前文档句柄 doc_id（如 doc-1），供 view/body 等工具定位文档。先调用它获取 doc_id。")
    public String currentDocument() {
      ToolResult<String> result = ToolResult.ok(docId, "当前文档句柄 " + docId);
      return ToolResultRenderer.render(result);
    }
  }

  // ==================== 保存与断言 ====================

  private List<EvalResult.CheckResult> runChecks(EvalTask task, Path out) {
    List<EvalResult.CheckResult> results = new ArrayList<>();
    try (Document doc = Docx.open(out)) {
      for (EvalTask.NamedCheck named : task.checks()) {
        try {
          named.check().verify(doc);
          results.add(new EvalResult.CheckResult(named.name(), true, ""));
        } catch (AssertionError e) {
          results.add(new EvalResult.CheckResult(named.name(), false, e.getMessage()));
        } catch (Exception e) {
          results.add(
              new EvalResult.CheckResult(named.name(), false, "check 执行异常: " + rootMessage(e)));
        }
      }
    } catch (RuntimeException e) {
      results.add(new EvalResult.CheckResult("打开输出文档", false, "无法读取输出 docx: " + rootMessage(e)));
    }
    return results;
  }

  private static boolean isToolSuccess(String toolName, String result) {
    if (result == null) {
      return false;
    }
    if (result.startsWith("工具执行失败") || result.contains("未注册的工具")) {
      return false;
    }
    ToolResultParser.Snapshot parsed = ToolResultParser.parse(result);
    if (parsed != null) {
      return parsed.success();
    }
    // 复审 SubAgent 返回的是纯文本结论，不是 ToolResult envelope；没有失败前缀视为执行成功。
    return toolName != null && toolName.startsWith("review_");
  }

  // ==================== 结果与产物 ====================

  private EvalResult.TaskResult failure(
      EvalTask task,
      Path taskDir,
      List<EvalResult.ToolCall> calls,
      List<String> traceLines,
      EvalResult.FailureKind kind,
      String error,
      long started,
      long promptTokens,
      long completionTokens,
      String finalMessage) {
    List<EvalResult.CheckResult> checks = new ArrayList<>();
    if (kind == EvalResult.FailureKind.SAVE_ERROR) {
      checks.add(new EvalResult.CheckResult("保存输出文档", false, error));
    } else if (kind == EvalResult.FailureKind.AGENT_ERROR) {
      checks.add(new EvalResult.CheckResult("Agent 执行", false, error));
    } else if (kind == EvalResult.FailureKind.TIMEOUT) {
      checks.add(new EvalResult.CheckResult("任务超时", false, error));
    }
    EvalResult.TaskResult result =
        new EvalResult.TaskResult(
            task,
            false,
            kind,
            checks,
            calls,
            (int) calls.stream().filter(call -> !call.success()).count(),
            promptTokens,
            completionTokens,
            System.currentTimeMillis() - started,
            truncate(finalMessage, MAX_FINAL_MESSAGE_CHARS),
            error,
            "");
    writeTaskArtifacts(taskDir, result, traceLines);
    return result;
  }

  private static void writeTaskArtifacts(
      Path taskDir, EvalResult.TaskResult result, List<String> traceLines) {
    try {
      Files.createDirectories(taskDir);
      Files.writeString(
          taskDir.resolve("result.json"), JsonWriter.write(result.toMap()), StandardCharsets.UTF_8);
      String trace = String.join("\n", traceLines);
      Files.writeString(
          taskDir.resolve("trace.jsonl"),
          trace.isEmpty() ? "" : trace + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      // 报告写失败不应改变任务结论；控制台会看到 EvalMain 的最终汇总。
      System.err.println("写任务产物失败: " + taskDir + " (" + rootMessage(e) + ")");
    }
  }

  private static void trace(List<String> traceLines, String type, Object... pairs) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", type);
    event.put("time", System.currentTimeMillis());
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      event.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    traceLines.add(JsonWriter.write(event));
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }

  /** 一次 Agent 执行结果。 */
  private static final class AgentOutcome {

    private final ChatResult result;

    private AgentOutcome(ChatResult result) {
      this.result = result;
    }
  }

  /** ToolStart 到 ToolEnd 之间的临时状态。 */
  private static final class PendingTool {

    private final String arguments;
    private final long startedAtNanos;

    private PendingTool(String arguments) {
      this.arguments = arguments == null ? "" : arguments;
      this.startedAtNanos = System.nanoTime();
    }

    private long elapsedMs() {
      return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
  }
}
