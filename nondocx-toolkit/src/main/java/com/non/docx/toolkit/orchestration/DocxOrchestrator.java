package com.non.docx.toolkit.orchestration;

import com.non.docx.core.api.Document;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.orchestration.agent.ExpertRegistry;
import com.non.docx.toolkit.orchestration.commit.CommitCoordinator;
import com.non.docx.toolkit.orchestration.commit.OperationExecutors;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import com.non.docx.toolkit.orchestration.snapshot.SnapshotBuilder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对外高层 facade：RouterAgent 多子代理体系的统一入口。
 *
 * <p><b>OOXML 三层递进（orchestrator）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 只是文件；没有「编排」「会话」「conversation」概念。
 *   <li><b>POI</b>：{@code XWPFDocument} 是单文档活对象句柄；open/save/close 分散在调用方代码里。
 *   <li><b>nondocx</b>：{@code DocxOrchestrator} 在 toolkit 之上引入编排层——它持有 {@link DocxToolkit}
 *       （底层工具聚合）、{@link RouterAgent}（调度核心）、会话表，对外暴露会话标识而不泄露底层 docId。
 * </ul>
 *
 * <p><b>对外 API 分两层（父任务决策）：</b>
 *
 * <ul>
 *   <li>高层 {@code run(...)} / {@code chat(...)}——默认只返回 {@link RunSummary}（摘要 + 精简操作清单 + 统计），用于 demo
 *       与默认使用路径。
 *   <li>低层 {@code analyze(...)} / {@code plan(...)} / {@code commit(...)}——返回完整阶段产物 （snapshot /
 *       plans / review / commit），用于测试、调试、回放和精细控制。
 * </ul>
 *
 * <p><b>会话模型（第一版约束）。</b>
 *
 * <ul>
 *   <li>单会话单文档——一个 conversation memory 只服务一份活跃文档。
 *   <li>切换到另一份文档必须开启新会话，不复用旧 memory。
 *   <li>close/reopen 同一份文档递增 {@code sessionGeneration}，使旧快照与旧 plan 失效。
 *   <li>对外显式暴露 {@code conversationId}；底层 {@code docId} 不外露。
 * </ul>
 *
 * <p>{@code DocxToolkit} 继续保留为底层工具聚合器，不承担编排层职责。
 */
public final class DocxOrchestrator {

  private final DocxToolkit toolkit;
  private final RouterAgent router;
  private final CommitCoordinator commitCoordinator;
  private final SnapshotBuilder snapshotBuilder;
  private final ExpertRegistry experts;
  private final OperationExecutors executors;
  private final Map<String, OrchestratorSession> sessions = new HashMap<>();
  private final AtomicLong sessionSeq = new AtomicLong();

  private DocxOrchestrator(
      DocxToolkit toolkit,
      ExpertRegistry experts,
      OperationExecutors executors,
      PhaseCallback phaseCallback) {
    this.toolkit = Objects.requireNonNull(toolkit);
    this.experts = Objects.requireNonNull(experts);
    this.executors = Objects.requireNonNull(executors);
    this.commitCoordinator = new CommitCoordinator(executors);
    this.snapshotBuilder = new SnapshotBuilder();
    this.router =
        new RouterAgent(
            experts, commitCoordinator, snapshotBuilder, this::lookupDoc, phaseCallback);
  }

  /**
   * 创建一个 orchestrator，持有独立的 toolkit 与空的专家/执行器注册表。
   *
   * <p>调用方后续通过 {@link #experts()} / {@link #executors()} 注册专家与执行器。
   */
  public static DocxOrchestrator create() {
    return create(null);
  }

  /**
   * 创建一个带阶段回调的 orchestrator（用于实时推送编排进度）。
   *
   * @param phaseCallback 每个编排阶段完成时回调；null 时不回调
   */
  public static DocxOrchestrator create(PhaseCallback phaseCallback) {
    DocxToolkit tk = new DocxToolkit();
    return new DocxOrchestrator(tk, new ExpertRegistry(), new OperationExecutors(), phaseCallback);
  }

  /**
   * 从已有 toolkit 构造（复用其工具组与会话状态）。
   *
   * @param toolkit 底层工具聚合器
   * @param experts 专家注册表
   * @param executors operation 执行器注册表
   */
  public static DocxOrchestrator from(
      DocxToolkit toolkit, ExpertRegistry experts, OperationExecutors executors) {
    return new DocxOrchestrator(toolkit, experts, executors, null);
  }

  /** 专家注册表（供子任务注册专家）。 */
  public ExpertRegistry experts() {
    return experts;
  }

  /** operation 执行器注册表（供子任务注册执行器）。 */
  public OperationExecutors executors() {
    return executors;
  }

  // ==================== 会话生命周期 ====================

  /**
   * 打开文档，开启新会话（单会话单文档约束）。
   *
   * @param path 文档路径
   * @return 新会话的 conversationId（对外标识）
   */
  public String open(Path path) {
    Objects.requireNonNull(path);
    String docId = toolkit.session.openDocx(path.toString());
    if (docId.startsWith("错误")) {
      throw new IllegalStateException(docId);
    }
    String conversationId = "conv-" + sessionSeq.incrementAndGet();
    String sessionId = "sess-" + UUID.randomUUID();
    OrchestratorSession session = new OrchestratorSession(conversationId, sessionId, docId, path);
    sessions.put(conversationId, session);
    return conversationId;
  }

  /** 切换/重开同一份文档：close 当前 + reopen + 递增代次。 */
  public void reopen(String conversationId) {
    OrchestratorSession session = requireSession(conversationId);
    // close + reopen 同一文件
    toolkit.session.closeDocx(session.docId());
    String newDocId = toolkit.session.openDocx(session.sourcePath().toString());
    if (newDocId.startsWith("错误")) {
      throw new IllegalStateException(newDocId);
    }
    // 更新 session 的 docId 与代次（session 的 docId 是 final——需要可变；改用替换 session）
    OrchestratorSession reopened =
        new OrchestratorSession(
            session.conversationId(), session.sessionId(), newDocId, session.sourcePath());
    // 复制 memory 与代次递增
    for (OrchestratorSession.Turn t : session.memory()) {
      reopened.appendTurn(t);
    }
    reopened.bumpGeneration();
    sessions.put(conversationId, reopened);
  }

  /** 保存文档。 */
  public String save(String conversationId, Path outputPath) {
    OrchestratorSession session = requireSession(conversationId);
    return toolkit.session.saveDocx(session.docId(), outputPath.toString());
  }

  /** 关闭会话（释放文档）。 */
  public String close(String conversationId) {
    OrchestratorSession session = sessions.remove(conversationId);
    if (session == null) {
      return "会话 " + conversationId + " 不存在";
    }
    return toolkit.session.closeDocx(session.docId());
  }

  // ==================== 高层 API：run / chat ====================

  /**
   * 高层入口：跑完整状态机，返回 {@link RunSummary}（摘要 + 精简操作清单 + 统计）。
   *
   * @param conversationId 会话标识
   * @param intent 用户意图文本
   * @return 高层摘要
   */
  public RunSummary run(String conversationId, String intent) {
    OrchestratorSession session = requireSession(conversationId);
    RouterResult result = router.run(session, intent);
    // 记入多轮记忆
    session.appendTurn(new OrchestratorSession.Turn(intent, result.summaryText()));
    return RunSummary.from(
        conversationId, result.summaryText(), result.mergedPlan(), result.commitResult());
  }

  /** {@link #run} 的别名（chat 语义）。 */
  public RunSummary chat(String conversationId, String intent) {
    return run(conversationId, intent);
  }

  // ==================== 低层 API：analyze / plan / commit ====================

  /** 低层 analyze：只构建快照，不进 PLAN/COMMIT。 */
  public DocumentSnapshot analyze(String conversationId) {
    OrchestratorSession session = requireSession(conversationId);
    return snapshotBuilder.build(
        lookupDoc(session),
        session.conversationId(),
        session.sourcePath(),
        session.sessionGeneration());
  }

  /** 低层 plan：构建快照 + 唤起专家 + 合并 + review，不进 commit。 */
  public RouterResult plan(String conversationId, String intent) {
    OrchestratorSession session = requireSession(conversationId);
    return router.run(session, intent);
  }

  /**
   * 高层 run：跑完整状态机（analyze → plan → review → commit），带阶段回调。
   *
   * <p>与 {@link #plan} 的区别：plan 实际也跑完整状态机（含 commit），但 run 支持注入临时阶段回调， 让调用方在 ANALYZE/PLAN/COMMIT
   * 每个阶段完成时收到通知（用于实时推送进度帧）。
   *
   * @param conversationId 会话标识
   * @param intent 用户意图
   * @param phaseCallback 阶段回调（null 时不回调）
   * @return 本轮完整结果
   */
  public RouterResult run(String conversationId, String intent, PhaseCallback phaseCallback) {
    OrchestratorSession session = requireSession(conversationId);
    return router.run(session, intent, phaseCallback);
  }

  // 低层 commit 由 RouterResult 已包含 commitResult；若需单独提交某个 MergedPlan，
  // 可通过 commitCoordinator 暴露——第一版不单独提供，避免绕过 review 闸门。

  // ==================== 内部 ====================

  private OrchestratorSession requireSession(String conversationId) {
    OrchestratorSession s = sessions.get(conversationId);
    if (s == null) {
      throw new IllegalStateException("会话 " + conversationId + " 不存在（未 open 或已 close）");
    }
    return s;
  }

  private Document lookupDoc(OrchestratorSession session) {
    Document doc = toolkit.session.getDocument(session.docId());
    if (doc == null) {
      throw new IllegalStateException("文档句柄 " + session.docId() + " 不存在（可能已被 close/reopen）");
    }
    return doc;
  }

  /** 把 toolkit 暴露给子任务（注册执行器时需要访问工具组）。 */
  public DocxToolkit toolkit() {
    return toolkit;
  }
}
