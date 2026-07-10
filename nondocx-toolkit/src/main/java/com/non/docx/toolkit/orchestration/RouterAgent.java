package com.non.docx.toolkit.orchestration;

import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import com.non.docx.toolkit.orchestration.agent.ExpertRegistry;
import com.non.docx.toolkit.orchestration.commit.CommitCoordinator;
import com.non.docx.toolkit.orchestration.commit.CommitResult;
import com.non.docx.toolkit.orchestration.review.ReviewReason;
import com.non.docx.toolkit.orchestration.review.ReviewResult;
import com.non.docx.toolkit.orchestration.review.ReviewStatus;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import com.non.docx.toolkit.orchestration.snapshot.SnapshotBuilder;
import com.non.docx.toolkit.ref.ElementRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RouterAgent：编排中枢，驱动 ANALYZE → PLAN → REVIEW → COMMIT → DONE/FAILED 状态机。
 *
 * <p><b>职责（父任务决策）：</b>
 *
 * <ul>
 *   <li>理解用户意图；基于 {@link DocumentSnapshot} 粗分流，分阶段派发必要专家。
 *   <li>接收各专家 {@link ExpertPlan}，合并为 {@link MergedPlan}（去重、固定优先级排序、来源链维护）。
 *   <li>条件触发 REVIEW（跨专家合并 / 冲突候选 / 质量告警 / 修订操作 / 失败重试等高风险场景）。
 *   <li>无 BLOCKED 时调用 {@link CommitCoordinator} 串行提交。
 * </ul>
 *
 * <p><b>第一版同步推进。</b> runtime 骨架阶段不引入真正的异步并发——分阶段派发先按顺序同步唤起相关专家， 为后续异步化预留接口（{@code
 * ExpertRegistry.selectRelevant} 已支持粗分流）。
 */
public final class RouterAgent {

  private final ExpertRegistry experts;
  private final CommitCoordinator commitCoordinator;
  private final SnapshotBuilder snapshotBuilder;
  private final DocProvider docProvider;
  private final PhaseCallback phaseCallback;

  /** 当前请求的临时回调（由 run(session, intent, callback) 设置，run 结束后恢复）。 */
  private PhaseCallback currentCallback;

  private final AtomicLong planIdSeq = new AtomicLong();

  public RouterAgent(
      ExpertRegistry experts,
      CommitCoordinator commitCoordinator,
      SnapshotBuilder snapshotBuilder,
      DocProvider docProvider) {
    this(experts, commitCoordinator, snapshotBuilder, docProvider, null);
  }

  public RouterAgent(
      ExpertRegistry experts,
      CommitCoordinator commitCoordinator,
      SnapshotBuilder snapshotBuilder,
      DocProvider docProvider,
      PhaseCallback phaseCallback) {
    this.experts = Objects.requireNonNull(experts);
    this.commitCoordinator = Objects.requireNonNull(commitCoordinator);
    this.snapshotBuilder = Objects.requireNonNull(snapshotBuilder);
    this.docProvider = Objects.requireNonNull(docProvider);
    this.phaseCallback = phaseCallback;
    this.currentCallback = phaseCallback;
  }

  /**
   * 处理一轮用户请求：跑完整状态机，返回结果对象（含状态、MergedPlan、CommitResult）。
   *
   * @param session 当前会话
   * @param intent 用户意图文本
   * @return 本轮结果
   */
  public RouterResult run(OrchestratorSession session, String intent) {
    return run(session, intent, null);
  }

  /**
   * 带临时阶段回调的 run。
   *
   * <p>传入的 callback 临时覆盖构造时设置的 callback（若构造时也设了的话）。这让调用方能在每轮请求时 注入不同的回调（例如捕获当前 HTTP 请求的 SSE 输出流）。
   *
   * @param session 当前会话
   * @param intent 用户意图
   * @param callback 阶段回调（null 时不回调，即使构造时设了也不调）
   */
  public RouterResult run(OrchestratorSession session, String intent, PhaseCallback callback) {
    Objects.requireNonNull(session, "session 不能为空");
    Objects.requireNonNull(intent, "intent 不能为空");
    PhaseCallback prev = this.currentCallback;
    this.currentCallback = callback;
    try {
      return runInternal(session, intent);
    } finally {
      this.currentCallback = prev;
    }
  }

  /** 实际的状态机推进逻辑（由 run / run(callback) 调用，currentCallback 已设好）。 */
  private RouterResult runInternal(OrchestratorSession session, String intent) {
    // ---- ANALYZE：构建快照 ----
    com.non.docx.core.api.Document doc = docProvider.current(session);
    DocumentSnapshot snapshot =
        snapshotBuilder.build(
            doc, session.conversationId(), session.sourcePath(), session.sessionGeneration());
    firePhase(PhaseCallback.PhaseEvent.analyzed(snapshot));

    // ---- PLAN：粗分流 + 唤起相关专家 + 合并 ----
    List<ExpertAgent> relevant = experts.selectRelevant(intent, snapshot);
    List<ExpertPlan> expertPlans = new ArrayList<>();
    for (ExpertAgent a : relevant) {
      ExpertPlan ep = a.plan(session, snapshot, intent);
      if (ep != null && !ep.operations().isEmpty()) {
        expertPlans.add(ep);
      }
    }
    MergedPlan merged = mergePlans(session, expertPlans, snapshot);

    // ---- REVIEW：条件触发 ----
    boolean needReview = shouldReview(merged, expertPlans);
    if (needReview) {
      merged = applyReview(merged);
    }
    firePhase(PhaseCallback.PhaseEvent.planned(merged, needReview));

    // 检查 BLOCKED 闸门
    if (merged.hasBlocked()) {
      return RouterResult.blocked(session, snapshot, merged, needReview);
    }

    // ---- COMMIT ----
    CommitResult commitResult = commitCoordinator.commit(session, merged);
    if (!commitResult.allSucceeded()) {
      firePhase(PhaseCallback.PhaseEvent.commitFailed(commitResult, commitResult.failureMessage()));
      return RouterResult.failed(session, snapshot, merged, needReview, commitResult);
    }
    firePhase(PhaseCallback.PhaseEvent.committed(commitResult));

    // ---- DONE ----
    return RouterResult.done(session, snapshot, merged, needReview, commitResult);
  }

  /** 合并多个 ExpertPlan 为一个 MergedPlan：保留来源、去重、固定优先级排序。 */
  MergedPlan mergePlans(
      OrchestratorSession session, List<ExpertPlan> expertPlans, DocumentSnapshot snapshot) {
    List<String> sourceIds = new ArrayList<>();
    List<Operation> all = new ArrayList<>();
    for (ExpertPlan ep : expertPlans) {
      sourceIds.add(ep.planId());
      all.addAll(ep.operations());
    }

    // 去重：同 ConflictKey 的 operation，保留第一个，其余标记 SKIPPED(DUPLICATE_MERGED)
    List<Operation> deduped = deduplicate(all);

    String mergedId = "merged-" + planIdSeq.incrementAndGet();
    return new MergedPlan(session.conversationId(), mergedId, sourceIds, deduped);
  }

  private List<Operation> deduplicate(List<Operation> ops) {
    List<Operation> out = new ArrayList<>();
    Map<ConflictKey, String> seen = new HashMap<>();
    for (Operation op : ops) {
      ConflictKey key = op.conflictKey();
      String existing = seen.get(key);
      if (existing == null) {
        seen.put(key, op.operationId());
        out.add(op);
      } else {
        // 重复——标记被吸收
        out.add(op.withMergedInto(existing));
      }
    }
    return out;
  }

  /** REVIEW 触发条件：跨专家合并 / 冲突候选 / 修订操作 / 质量告警。 */
  boolean shouldReview(MergedPlan merged, List<ExpertPlan> expertPlans) {
    // 跨专家合并：多于一个来源专家
    if (expertPlans.size() > 1) return true;
    // 有 SKIPPED（去重吸收）或 WARNED
    if (merged.hasSkipped() || merged.hasWarned()) return true;
    // 含修订类 operation
    for (Operation op : merged.operations()) {
      if ("revision".equals(op.toolGroup())) return true;
    }
    return false;
  }

  /** REVIEW 阶段：对每条 operation 产出 review 结果（第一版启发式，留扩展点给 QualityAgent）。 */
  MergedPlan applyReview(MergedPlan merged) {
    // 检测粗粒度冲突候选（不同 operation 同 target 但不同 kind——可能需要第二层判定）
    // 第一版：对同 target 的多个非去重 operation 标记 POTENTIAL_CONFLICT（WARNED）
    Map<ElementRef, List<Operation>> byTarget = new HashMap<>();
    for (Operation op : merged.operations()) {
      if (op.reviewStatus() == ReviewStatus.SKIPPED) continue;
      byTarget.computeIfAbsent(op.conflictKey().targetRef(), k -> new ArrayList<>()).add(op);
    }
    Set<ElementRef> conflictTargets = new HashSet<>();
    for (Map.Entry<ElementRef, List<Operation>> e : byTarget.entrySet()) {
      if (e.getValue().size() > 1) {
        // 同 target 多 operation（且未被去重，说明 kind 不同）——候选冲突
        conflictTargets.add(e.getKey());
      }
    }

    List<Operation> reviewed = new ArrayList<>();
    for (Operation op : merged.operations()) {
      if (op.reviewStatus() != ReviewStatus.APPROVED) {
        // 已有 review 结果（SKIPPED/WARNED/BLOCKED）保留
        reviewed.add(op);
        continue;
      }
      if (conflictTargets.contains(op.conflictKey().targetRef())) {
        reviewed.add(
            op.withReview(
                ReviewResult.of(
                    ReviewReason.POTENTIAL_CONFLICT,
                    "同目标 " + op.conflictKey().targetRef().canonical() + " 存在多个操作，需人工确认")));
      } else {
        reviewed.add(op);
      }
    }
    return new MergedPlan(
        merged.conversationId(), merged.mergedPlanId(), merged.sourceExpertPlans(), reviewed);
  }

  /** 触发阶段回调（currentCallback 为 null 时安全跳过）。 */
  private void firePhase(PhaseCallback.PhaseEvent event) {
    if (currentCallback != null) {
      currentCallback.onPhase(event);
    }
  }

  /** 取活文档的函数式注入点（由 DocxOrchestrator 在 run 时提供）。 */
  public interface DocProvider {
    com.non.docx.core.api.Document current(OrchestratorSession session);
  }
}
