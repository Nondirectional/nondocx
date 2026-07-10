package com.non.docx.toolkit.orchestration;

import com.non.docx.toolkit.orchestration.commit.CommitResult;

/**
 * RouterAgent 的阶段回调：在每个编排阶段完成时被调用，供调用方（如 demo 的 AgentBridge） 实时推送进度帧给前端。
 *
 * <p><b>为什么用回调而非返回多个结果。</b> RouterAgent 的状态机是线性推进的（ANALYZE→PLAN→COMMIT），
 * 每个阶段完成后有一个天然的「可以对外报告进度」的点。用回调让调用方在这些点上立即行动 （推 SSE 帧），而不需要等整轮跑完再一次性返回——实现实时流式的分步进度展示。
 *
 * <p><b>可选注入。</b> 传 null 时不回调，不影响 RouterAgent 的核心逻辑与既有测试。
 *
 * <p><b>阶段语义：</b>
 *
 * <ul>
 *   <li>{@code ANALYZE}——快照构建完成，可报告文档结构摘要。
 *   <li>{@code PLAN}——专家计划合并完成（含 review），可报告人话操作清单。
 *   <li>{@code COMMIT}——提交完成（成功或失败），可报告执行结果。
 * </ul>
 */
@FunctionalInterface
public interface PhaseCallback {

  /**
   * 某个编排阶段完成时调用。
   *
   * @param event 阶段事件（含 phase / status / 相关产物）
   */
  void onPhase(PhaseEvent event);

  /**
   * 阶段事件：携带 phase 标识、完成状态与该阶段的产物。
   *
   * <p>不同 phase 携带不同产物：
   *
   * <ul>
   *   <li>ANALYZE：携带 snapshot（文档结构摘要）。
   *   <li>PLAN：携带 mergedPlan（人话操作清单来源）+ reviewTriggered。
   *   <li>COMMIT：携带 commitResult（执行成功/失败 + 已执行列表）。
   * </ul>
   */
  final class PhaseEvent {

    /** 阶段标识。 */
    public enum Phase {
      ANALYZE,
      PLAN,
      COMMIT
    }

    private final Phase phase;
    private final boolean success;
    private final DocumentSnapshot snapshot;
    private final MergedPlan mergedPlan;
    private final boolean reviewTriggered;
    private final CommitResult commitResult;
    private final String failureMessage;

    private PhaseEvent(
        Phase phase,
        boolean success,
        DocumentSnapshot snapshot,
        MergedPlan mergedPlan,
        boolean reviewTriggered,
        CommitResult commitResult,
        String failureMessage) {
      this.phase = phase;
      this.success = success;
      this.snapshot = snapshot;
      this.mergedPlan = mergedPlan;
      this.reviewTriggered = reviewTriggered;
      this.commitResult = commitResult;
      this.failureMessage = failureMessage;
    }

    /** ANALYZE 完成事件。 */
    public static PhaseEvent analyzed(DocumentSnapshot snapshot) {
      return new PhaseEvent(Phase.ANALYZE, true, snapshot, null, false, null, null);
    }

    /** PLAN 完成事件。 */
    public static PhaseEvent planned(MergedPlan merged, boolean reviewTriggered) {
      return new PhaseEvent(Phase.PLAN, true, null, merged, reviewTriggered, null, null);
    }

    /** COMMIT 成功事件。 */
    public static PhaseEvent committed(CommitResult commitResult) {
      return new PhaseEvent(Phase.COMMIT, true, null, null, false, commitResult, null);
    }

    /** COMMIT 失败事件（含原因）。 */
    public static PhaseEvent commitFailed(CommitResult commitResult, String failureMessage) {
      return new PhaseEvent(Phase.COMMIT, false, null, null, false, commitResult, failureMessage);
    }

    /** 阶段标识。 */
    public Phase phase() {
      return phase;
    }

    /** 是否成功（COMMIT 失败时为 false，其余为 true）。 */
    public boolean success() {
      return success;
    }

    /** ANALYZE 阶段的快照（仅 phase=ANALYZE 时非 null）。 */
    public DocumentSnapshot snapshot() {
      return snapshot;
    }

    /** PLAN 阶段的合并计划（仅 phase=PLAN 时非 null）。 */
    public MergedPlan mergedPlan() {
      return mergedPlan;
    }

    /** PLAN 阶段是否触发了 review。 */
    public boolean reviewTriggered() {
      return reviewTriggered;
    }

    /** COMMIT 阶段的提交结果（仅 phase=COMMIT 时非 null）。 */
    public CommitResult commitResult() {
      return commitResult;
    }

    /** COMMIT 失败原因（仅 commit 失败时非 null）。 */
    public String failureMessage() {
      return failureMessage;
    }
  }
}
