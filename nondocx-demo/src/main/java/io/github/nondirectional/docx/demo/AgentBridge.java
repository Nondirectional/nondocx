package io.github.nondirectional.docx.demo;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.agent.AfterResult;
import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.agent.BeforeResult;
import com.non.chain.agent.SkillInjectionMode;
import com.non.chain.agent.ToolCallContext;
import com.non.chain.memory.MessageWindowChatMemory;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;
import com.non.chain.skill.SkillRegistry;
import com.non.chain.tool.ToolRegistry;
import io.github.nondirectional.docx.toolkit.DocxToolkit;
import io.github.nondirectional.docx.toolkit.result.ToolResultParser;
import io.javalin.http.Context;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p><b>架构</b>：单个 {@link Agent} 持有全部只读 + 写工具，但<b>不持有保存工具</b>。保存（落盘）由应用层在 agent 主循环结束（{@link
 * AgentEvent.Complete}）时强制执行——见 {@link #onAgentComplete}。这从源头消灭了 "LLM 漏调 save / 谎报成功"导致的自述与磁盘真相鸿沟。
 *
 * <p><b>工具面（2026-10-30 起）</b>：默认 <b>compiler 面</b>（{@code current_document} + {@code
 * create/read/validate/edit/fill/render} 六个编译工具 + 只读复审 SubAgent = 8 入口）——依据 eval A/B（docs/11
 * §6.3~§6.7：新建 5/5、编辑 11/11，均优于 full 面）。{@code NONDOCX_AGENT_SURFACE=full} 回退原 58+ 入口细粒度面；system
 * prompt 与 Skill 策略随面切换（full 面的 Skill 教的是 compiler 面不存在的工具，不能混用）。
 *
 * <p><b>意图复审（2026-07-17 重构）</b>：质检目标从「客观版式规则自检」改为「复审用户本轮期望的修改是否达成」，由<b>只读复审 SubAgent</b> {@code
 * review_intent} 承担。该 SubAgent 仅持有 {@code view_*} 只读工具 + 复审指令，对比「本轮用户请求 vs
 * 文档现状」输出三态结论（达成/部分达成/未达成）+ 差异说明。 它不持有写工具、不接触保存，因此<b>不触发</b>历史 SubAgent 的「自述≠真相」鸿沟——这是 {@code
 * agent-single.md} spec 允许的唯一 SubAgent 例外。两面共用同一个复审 SubAgent。
 *
 * <p><b>软警告门控</b>：复审判「未达成」<b>不拦截保存</b>——dirty 即落盘，复审结论随 {@code edit_outcome.qualityReport}
 * 回传供用户/前端判断。 故 {@link DocumentTools#saveCurrentDocument} 不再做任何质检门控。
 *
 * <p><b>dirty 检测</b>：写工具的 {@code AfterToolCall} 把 {@code dirty} 置 true，标记本轮发生过文档写入。只读工具
 * （view/read/get/list/search/check/validate 及复审 SubAgent 调用）不置 dirty。{@code Complete} 时仅在 dirty 时
 * flush。
 *
 * <p><b>记忆污染治理</b>：写工具结果在 {@code AfterToolCall} 瘦身为一行确认；{@code review_intent} 原文挂 {@link
 * Message#note}（{@code llmVisible=false}，不占窗口、不喂 LLM），紧凑摘要作真工具结果喂 LLM。
 *
 * <p>详见 {@code .trellis/spec/backend/agent-single.md}。
 */
final class AgentBridge {

  private static final Logger log = LoggerFactory.getLogger(AgentBridge.class);

  /** 复审 SubAgent 工具名（DIRECT 模式下，SubAgent name 即暴露给主 Agent 的工具名）。 */
  static final String REVIEW_TOOL = "review_intent";

  /** compiler 面名（默认）：current_document + 6 编译工具 + review_intent。 */
  static final String SURFACE_COMPILER = "compiler";

  /** full 面名：细粒度工具面（58+ 入口），NONDOCX_AGENT_SURFACE=full 时启用。 */
  static final String SURFACE_FULL = "full";

  /** 选择工具面的环境变量名。 */
  static final String SURFACE_ENV = "NONDOCX_AGENT_SURFACE";

  /** 复审 SubAgent 的 systemPrompt：约束输出固定前缀三态 + 差异说明，便于 afterToolCall 解析。 */
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

  /**
   * 只读工具名前缀/集合。匹配这些的工具<b>不</b>置 dirty——它们不改文档。 其余工具（body/table/修订创作等写工具）一律置 dirty。安全默认：未知工具视为写（多跑一次
   * flush 只是浪费，不丢数据；漏标 dirty 会丢编辑）。
   *
   * <p>{@code review_} 前缀覆盖只读复审 SubAgent 工具名（{@code review_intent}）：复审 SubAgent 仅持有 {@code
   * view_*}，不改文档，故归只读。
   */
  private static final Set<String> READONLY_TOOL_PREFIXES =
      Set.of(
          "view_",
          "read_",
          "get_",
          "list_",
          "search_",
          "check_",
          "validate_",
          "review_",
          "render_");

  private static final Set<String> READONLY_TOOL_EXACT =
      Set.of("current_document", "describe_capabilities", "get_subagent_result", "steer_subagent");

  private final boolean enabled;

  /** 当前工具面名：{@link #SURFACE_COMPILER} 或 {@link #SURFACE_FULL}。 */
  private final String surface;

  /**
   * Agent 被禁用的原因（{@code enabled=true} 时为 {@code null}）。
   *
   * <p>两种禁用路径：未配置 {@code DASHSCOPE_API_KEY}，或启动校验发现端点不提供配置的模型 （{@link
   * LlmConfig#validateModelAvailable()}）。原因会进启动日志与对话错误帧，让用户 能直接看出该配哪个环境变量。
   */
  private final String disabledReason;

  private final DocxToolkit toolkit;
  private final LLM llm;
  private final Path currentDocPath;
  private final Agent agent;
  private final MessageWindowChatMemory memory;
  private final SkillRegistry skillRegistry;
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

  private final AtomicBoolean reviewed = new AtomicBoolean();
  private final AtomicReference<String> lastReviewReport = new AtomicReference<>("");

  /** 本轮用户请求文本，{@code runStream} 开头置位，供复审 SubAgent 的 contextSelector 注入。 */
  private volatile String currentTurnRequest = "";

  AgentBridge(String apiKey, String currentDocPath) {
    this.currentDocPath = Path.of(currentDocPath);
    this.surface = surfaceFromEnv();
    if (apiKey == null || apiKey.isBlank()) {
      disabledReason = "未配置 DASHSCOPE_API_KEY";
      enabled = false;
      toolkit = null;
      llm = null;
      agent = null;
      memory = null;
      skillRegistry = null;
      documentTools = null;
      return;
    }
    // 启动期校验：端点必须真的提供配置的模型，否则此刻禁用并给出可操作的原因，
    // 不留到第一次对话才 404（曾因写死 qwen3-14b 踩坑，见 docs/11 P0-1）。
    LlmConfig llmConfig = new LlmConfig(apiKey);
    String problem = llmConfig.validateModelAvailable();
    if (problem != null) {
      log.error("Agent 已禁用：{}", problem);
      disabledReason = problem;
      enabled = false;
      toolkit = null;
      llm = null;
      agent = null;
      memory = null;
      skillRegistry = null;
      documentTools = null;
      return;
    }
    disabledReason = null;
    enabled = true;
    llm =
        new VLLM(llmConfig.baseUrl(), llmConfig.apiKey(), llmConfig.model())
            .maxCompletionTokens(65536)
            .thinkingBudget(512);
    log.info("Agent LLM: {} @ {}", llmConfig.model(), llmConfig.baseUrl());
    toolkit = new DocxToolkit();
    openCurrentDocument();
    memory =
        MessageWindowChatMemory.builder().conversationId("demo-document").maxMessages(24).build();
    documentTools = new DocumentTools(toolkit, () -> docId, () -> this.currentDocPath);
    // Skill 策略随面切换：full 面 Skill 教的是细粒度工具用法，compiler 面没有这些工具，不能注入。
    skillRegistry = skillRegistryFor(surface);

    ToolRegistry tools = buildToolRegistry();
    log.info("Agent surface: {}（{} 入口）", surface, tools.getRegularTools().size() + 1);

    agent =
        Agent.builder(llm, tools)
            .memory(memory)
            .maxIterations(24)
            .executor(null)
            .skillRegistry(skillRegistry)
            .skillInjectionMode(SkillInjectionMode.SYSTEM)
            .addBeforeToolCall(this::beforeToolCall)
            .addAfterToolCall(this::afterToolCall)
            .systemPrompt(systemPromptFor(surface))
            .build();
  }

  /**
   * 构建主 Agent 的 {@link ToolRegistry}：按面装配写侧 + <b>只读复审 SubAgent</b> {@code review_intent}。
   *
   * <p><b>compiler 面（默认）</b>：{@code documentTools}（current_document）+ {@code
   * toolkit.compiler}（create/read/validate/edit_document + fill_template + render_preview），主面 7
   * regular 工具 + 复审 SubAgent = 8 入口——与 eval 的 compiler 面同构（docs/11 §6.3~§6.7 的 A/B 数据即此装配）。
   *
   * <p><b>full 面</b>：现行细粒度装配（view/body/table/headerFooterToc/trackedChangeQuery/
   * trackedChangeAuthoring 六组 scan），供回退对比。
   *
   * <p>复审 SubAgent 的 {@link ToolRegistry} 刻意<b>只 scan {@code
   * toolkit.view}</b>——不持有任何写工具、不持有保存工具、不持有其它 SubAgent（仅一层委派）。这是它被 {@code agent-single.md} spec
   * 允许为例外的物理保证：只读复审 SubAgent 无法触发「漏调 save / 谎报成功」的真相鸿沟。复审 SubAgent 复用主 Agent 的 {@link
   * #llm}，不引入第二模型；<b>两面共用同一复审装配</b>。
   */
  private ToolRegistry buildToolRegistry() {
    return buildToolRegistry(surface, documentTools, toolkit, llm, this::selectReviewContext);
  }

  /** 静态装配（package-private 便于测试锁定双面工具集，不经 LLM/网络）。 */
  static ToolRegistry buildToolRegistry(
      String surface,
      DocumentTools documentTools,
      DocxToolkit toolkit,
      LLM llm,
      com.non.chain.agent.ContextSelector reviewSelector) {
    ToolRegistry main = new ToolRegistry().scan(documentTools);
    if (SURFACE_FULL.equals(surface)) {
      main.scan(toolkit.view)
          .scan(toolkit.body)
          .scan(toolkit.table)
          .scan(toolkit.headerFooterToc)
          .scan(toolkit.trackedChangeQuery)
          .scan(toolkit.trackedChangeAuthoring);
    } else {
      // compiler 面（默认）
      main.scan(toolkit.compiler);
    }
    ToolRegistry reviewTools = new ToolRegistry().scan(toolkit.view);
    return main.registerSubAgent(REVIEW_TOOL, "[复审] 对比用户本轮修改请求与文档现状，返回达成/部分达成/未达成结论及差异说明。修改完成后调用。")
        .systemPrompt(REVIEW_SYSTEM_PROMPT)
        .toolRegistry(reviewTools)
        .llm(llm)
        .maxIterations(6)
        .contextSelector(reviewSelector)
        .build();
  }

  /**
   * 面向工具面的 system prompt（package-private 便于测试）。
   *
   * <p>compiler 版改编自 eval 的 COMPILER_SYSTEM_PROMPT（同口径才能复现 A/B 数据），叠加交互场景差异： demo
   * 是多轮对话，不预设「用户要求生成/重写整份文档」的一次性任务框架。
   */
  static String systemPromptFor(String surface) {
    if (SURFACE_FULL.equals(surface)) {
      return "你是文档 Agent。先调用 current_document 获取唯一允许使用的 doc_id，再按任务需要工作。"
          + "普通咨询只调用只读 view/read 工具；用户提出明确编辑请求时，直接调用写工具修改文档，"
          + "不得调用或寻找 open_docx、close_docx、save_docx 或任何保存工具——保存由系统在结束后自动完成。"
          + "完成修改后调用 review_intent 复审用户期望的修改是否已经达成。若任一工具失败，停止并说明失败。"
          + "不要调用或描述 Dispatcher、计划、专家分派或提交阶段。"
          + "不要使用对话记忆或推断得来的旧 doc_id。"
          // Skill 是可选过程知识：不强制相关请求必须激活某条 Skill，模型可自主判断是否需要。
          + "部分 Skill（以 [Skill] 标注、无参数）是可选的过程性知识，仅在确实需要时调用，"
          + "同一 Skill 在一轮内最多调用一次，不重复点选。";
    }
    return "你是文档 Agent。先调用 current_document 获取唯一允许使用的 doc_id。"
        + "用户要求生成/重写整份文档时，把完整内容写成 Markdown，一次调用 create_document 生成"
        + "（它会替换当前文档，句柄不变）。Markdown 支持 # 标题、**粗体**、无序/有序列表、"
        + "|表格|（首行为表头）、{align=center} 属性；排版（黑体标题、宋体小四正文、1.5 倍行距、"
        + "首行缩进、表头加粗灰底）由 style_profile=report-cn 自动完成，不要在 Markdown 里描述字体字号。"
        + "了解或核对文档用 read_document（段落/表格带 pN/tN 短 ID，页脚在末尾元数据行），"
        + "版式质量用 validate_document。"
        + "注意：read_document 的输出是 Markdown 视图——# 前缀、{align=…}、<!-- pN --> 都是"
        + "结构/属性标记，不是正文文本，不要试图删除或替换它们。"
        + "编辑既有文档时，把一批修改整理成 ops 一次调用 edit_document（锚点用短 ID，"
        + "插入内容用 Markdown 块），不要逐段零散修改。"
        + "给文字加粗/倾斜（含替换后加粗）用行内标记：replace_text 的 text 写 **加粗**/*倾斜*，"
        + "如把「待定」换成加粗的「通过」就是 text=**通过**；整段全加粗可用 set_style 的 bold=true。"
        + "用户要求「修订模式/以修订」修改时用 tracked.replace / tracked.insert / tracked.delete op"
        + "（必传 author=修订作者，用户给了作者名就用它；"
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
        + "完成修改后调用 review_intent 复审用户期望的修改是否已经达成。若任一工具失败，停止并说明失败。"
        + "不要使用对话记忆或推断得来的旧 doc_id。";
  }

  /**
   * Skill 策略（package-private 便于测试）：full 面 6 条过程知识 Skill；compiler 面<b>空</b> ——Skill 正文教的是
   * insert_paragraph 等细粒度工具用法，compiler 面没有这些工具，注入即误导。
   */
  static SkillRegistry skillRegistryFor(String surface) {
    return SURFACE_FULL.equals(surface) ? DemoSkills.create() : new SkillRegistry();
  }

  /**
   * 解析工具面环境变量（package-private 便于测试）：空/null → compiler（默认）； compiler/full 原样；非法值记 error 并回退
   * compiler（demo 保持可用，不因拼写错误拒绝启动）。
   */
  static String surfaceFromRaw(String raw) {
    String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (value.isEmpty() || SURFACE_COMPILER.equals(value)) {
      return SURFACE_COMPILER;
    }
    if (SURFACE_FULL.equals(value)) {
      return SURFACE_FULL;
    }
    log.error("未知 NONDOCX_AGENT_SURFACE={}（可用: compiler/full），回退默认 compiler", raw);
    return SURFACE_COMPILER;
  }

  static String surfaceFromEnv() {
    return surfaceFromRaw(System.getenv(SURFACE_ENV));
  }

  /**
   * 复审 SubAgent 的上下文裁剪：注入「本轮用户请求」+ 当前文档句柄 {@code doc_id}。
   *
   * <p>MVP 策略（见 prd.md 已决）：只取本轮 user message + 文档现状（复审 SubAgent 用 {@code view_*} 读取），不带主 Agent
   * 的写作过程摘要。 忽略框架默认从父链裁剪的历史（复审只需本轮请求 + 文档事实）。
   *
   * <p><b>必须注入 {@code doc_id}</b>：复审 SubAgent 的 {@code view_*} 工具需要 {@code doc_id} 参数才能读文档。主 Agent
   * 通过 {@code current_document} 拿到的句柄不在 SubAgent 上下文里，故这里显式注入；否则复审 SubAgent
   * 读不到文档，会把正常完成的修改误判为「未达成」。 {@link #docId} 由 {@link #openCurrentDocument()} 在构造期设置，复审发生时（本轮
   * dirty）必然非空。
   */
  private List<Message> selectReviewContext(List<Message> history, Message newMsg, String args) {
    String request = currentTurnRequest == null ? "" : currentTurnRequest;
    String prompt =
        "用户本轮对文档的修改请求：\n"
            + (request.isBlank() ? "（无明确文本）" : request)
            + "\n\n当前文档句柄 doc_id = "
            + docId
            + "。请用这个 doc_id 调用 view_* 只读工具（如 view_stats/view_body）读取文档当前内容，"
            + "判断上述请求的修改是否已经达成。";
    return List.of(Message.user(prompt));
  }

  boolean enabled() {
    return enabled;
  }

  /** Agent 被禁用的原因；启用时为 {@code null}。供启动日志与前端状态展示。 */
  String disabledReason() {
    return disabledReason;
  }

  /** 当前工具面名（compiler/full），供 /api/status 与前端展示。 */
  String surface() {
    return surface;
  }

  void clearMemory() {
    if (!enabled) return;
    memory.clear();
    cancelRequested.set(true);
    reopenCurrentDocument();
  }

  void runStream(String message, Context ctx, DocSession session) {
    if (!enabled) {
      emit(ctx, frame("error", "message", "Agent 不可用：" + disabledReason + "。"));
      emit(ctx, frame("done"));
      return;
    }
    String turnId = "turn-" + turnSeq.incrementAndGet();
    cancelRequested.set(false);
    dirty.set(false);
    reviewed.set(false);
    lastReviewReport.set("");
    currentTurnRequest = message;
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
   * 写工具结果瘦身（α）+ dirty 检测 + 复审原文挂 note（β）。
   *
   * <ul>
   *   <li>写工具：置 dirty，结果改写为一行确认（LLM 刚写完，不需回显全文，省 24 条窗口预算）。
   *   <li>{@code review_intent}（只读复审 SubAgent）：紧凑摘要喂 LLM，原文挂 {@link Message#note} 给 UI（不占窗口、不喂
   *       LLM）。
   *   <li>其它只读工具：原样保留。
   * </ul>
   */
  private AfterResult afterToolCall(ToolCallContext context) {
    String tool = context.toolName();
    if (isReadonly(tool)) {
      if (REVIEW_TOOL.equals(tool)) {
        reviewed.set(true);
        String full = context.result();
        lastReviewReport.set(full);
        // β：复审原文挂 note 供 UI 回放/ edit_outcome 引用；摘要作真工具结果喂 LLM。
        if (memory != null && full != null && !full.isBlank()) {
          memory.add(Message.note("review_report", full));
        }
        return AfterResult.content(summarizeReview(full));
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
  /**
   * 复审摘要：解析 {@code <verdict>} 三态，附简短差异，避免整份复审结论占满 LLM 上下文。
   *
   * <p>复审 SubAgent 输出形如 {@code <verdict>达成</verdict><diff>...</diff>}。此处只取 verdict 桶 + 截断的 diff，喂主
   * Agent； 原文已挂 {@link Message#note} 给 UI。
   */
  static String summarizeReview(String raw) {
    if (raw == null || raw.isBlank()) return "复审无结论";
    String verdict = parseVerdict(raw);
    String diff = parseDiff(raw);
    if (diff != null && diff.length() > 200) {
      diff = diff.substring(0, 200) + "…";
    }
    String icon;
    String label;
    switch (verdict == null ? "" : verdict) {
      case "达成":
        icon = "✓";
        label = "复审：用户期望的修改已达成";
        break;
      case "部分达成":
        icon = "⚠";
        label = "复审：部分达成";
        break;
      case "未达成":
        icon = "✗";
        label = "复审：未达成";
        break;
      default:
        icon = "?";
        label = "复审结论无法解析";
    }
    return icon + " " + label + (diff == null ? "" : "：" + diff) + "。完整复审结论已存档。";
  }

  /** 从复审原文提取 {@code <verdict>} 值（达成/部分达成/未达成）；无法解析返回 null。 */
  private static String parseVerdict(String raw) {
    int start = raw.indexOf("<verdict>");
    int end = raw.indexOf("</verdict>");
    if (start < 0 || end <= start) return null;
    return raw.substring(start + "<verdict>".length(), end).trim();
  }

  /** 从复审原文提取 {@code <diff>} 正文（去掉标签）；无法解析返回 null。 */
  private static String parseDiff(String raw) {
    int start = raw.indexOf("<diff>");
    int end = raw.indexOf("</diff>");
    if (start < 0 || end <= start) return null;
    return raw.substring(start + "<diff>".length(), end).trim();
  }

  // ============ 循环结束 flush（代码强制保存） ============

  /**
   * {@link AgentEvent.Complete} 时强制 flush：若本轮 dirty，直接落盘（无质检门控），发 {@code edit_outcome} 系统帧。
   *
   * <p>这是回归的核心——保存不再由 LLM 触发，消除"漏调 save / 谎报成功"。流程：
   *
   * <ol>
   *   <li>非 dirty：纯咨询，发 noop 帧，零落盘。
   *   <li>cancelled：reopen 回滚，发 cancelled 帧。
   *   <li>dirty：直接落盘（复审为软警告，不拦截保存）。复审结论随 {@code qualityReport} 回传供前端展示。
   *   <li>保存失败：reopen 回滚，发 rolled_back 帧。
   *   <li>成功：发 doc_changed。
   * </ol>
   *
   * <p><b>2026-07-17 重构</b>：删除原"质检 error 拒绝落盘"门控与"漏调 check_quality 兜底"。复审 SubAgent（{@code
   * review_intent}） 改为软警告——复审判「未达成」仍落盘，结论仅作诊断信号回传。{@code qualityReport} 字段语义从「规则质检报告」改为「复审结论」。
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
            lastReviewReport.get(),
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
    } else if (event instanceof AgentEvent.SkillActivated) {
      // Skill 激活：独立 trace 帧，不发正文、不发 tool_start/tool_end，不改变 dirty。
      AgentEvent.SkillActivated skill = (AgentEvent.SkillActivated) event;
      trace.putAll(skillActivatedFields(skill.skillName(), skill.contentLength()));
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

  /**
   * 取 Skill 的 description 用于 SSE 帧。description 是固定元数据（见 {@link DemoSkills}）， 与 registry
   * 是否构造无关，直接走静态查表，避免 disabled 模式下 NPE。
   */
  private static String skillDescription(String skillName) {
    try {
      return DemoSkills.description(skillName);
    } catch (IllegalArgumentException unknown) {
      // 未知 Skill（不应发生）：回退为名称，不中断 trace 流。
      return skillName;
    }
  }

  /**
   * 组装 Skill 激活 trace 帧的 Skill 专属字段（不含 type/turnId/agent 公共头）。
   *
   * <p>刻意<b>不</b>包含 Skill 正文：正文是过程知识，重复广播会扩大日志并暴露内部指令（见 design.md §4.1）。仅暴露名称、description
   * 和注入字符数，供前端展示与诊断。
   *
   * <p>package-private 便于 trace 映射测试直接断言字段完整性。
   */
  static Map<String, Object> skillActivatedFields(String skillName, int contentLength) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("event", "skill_activated");
    fields.put("skill", skillName);
    fields.put("description", skillDescription(skillName));
    fields.put("contentLength", contentLength);
    return fields;
  }
}
