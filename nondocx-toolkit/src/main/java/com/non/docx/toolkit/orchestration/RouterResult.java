package com.non.docx.toolkit.orchestration;

import com.non.docx.toolkit.orchestration.commit.CommitResult;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.Objects;

/**
 * RouterAgent 单轮运行结果：承载状态机终态、快照、合并计划与提交结果。
 *
 * <p>低层 API / debug 模式返回此对象；高层 {@code run(...)} / {@code chat(...)} 只返回 {@link RunSummary} （由
 * {@link RunSummary#from} 从本结果推导）。
 */
public final class RouterResult {

  private final RouterState state;
  private final String conversationId;
  private final DocumentSnapshot snapshot;
  private final MergedPlan mergedPlan;
  private final boolean reviewTriggered;
  private final CommitResult commitResult;

  private RouterResult(
      RouterState state,
      OrchestratorSession session,
      DocumentSnapshot snapshot,
      MergedPlan mergedPlan,
      boolean reviewTriggered,
      CommitResult commitResult) {
    this.state = state;
    this.conversationId = session.conversationId();
    this.snapshot = snapshot;
    this.mergedPlan = mergedPlan;
    this.reviewTriggered = reviewTriggered;
    this.commitResult = commitResult;
  }

  /** DONE 终态。 */
  static RouterResult done(
      OrchestratorSession session,
      DocumentSnapshot snapshot,
      MergedPlan merged,
      boolean reviewTriggered,
      CommitResult commit) {
    return new RouterResult(RouterState.DONE, session, snapshot, merged, reviewTriggered, commit);
  }

  /** FAILED 终态（提交失败）。 */
  static RouterResult failed(
      OrchestratorSession session,
      DocumentSnapshot snapshot,
      MergedPlan merged,
      boolean reviewTriggered,
      CommitResult commit) {
    return new RouterResult(RouterState.FAILED, session, snapshot, merged, reviewTriggered, commit);
  }

  /** BLOCKED 终态（整批被 review 闸下，未进 commit）。 */
  static RouterResult blocked(
      OrchestratorSession session,
      DocumentSnapshot snapshot,
      MergedPlan merged,
      boolean reviewTriggered) {
    return new RouterResult(RouterState.FAILED, session, snapshot, merged, reviewTriggered, null);
  }

  /** 终态（DONE / FAILED）。 */
  public RouterState state() {
    return state;
  }

  public String conversationId() {
    return conversationId;
  }

  public DocumentSnapshot snapshot() {
    return snapshot;
  }

  public MergedPlan mergedPlan() {
    return mergedPlan;
  }

  /** REVIEW 是否被触发（条件触发，非每次强制）。 */
  public boolean reviewTriggered() {
    return reviewTriggered;
  }

  /** 提交结果（BLOCKED 整批未进 commit 时为 null）。 */
  public CommitResult commitResult() {
    return commitResult;
  }

  /** 是否成功完成。 */
  public boolean isDone() {
    return state == RouterState.DONE;
  }

  /** 是否需要 close + reopen（FAILED 或提交失败时）。 */
  public boolean shouldReopen() {
    return state == RouterState.FAILED || (commitResult != null && commitResult.shouldReopen());
  }

  /** 推导自然语言摘要文本。 */
  public String summaryText() {
    if (state == RouterState.DONE) {
      assert commitResult != null;
      return "完成 " + commitResult.executed().size() + " 项操作。";
    }
    if (commitResult == null) {
      // BLOCKED
      return "存在阻断项，整批未提交（需修正后重试）。";
    }
    return "提交失败于 "
        + commitResult.failedOperationId()
        + "："
        + commitResult.failureMessage()
        + "（已执行 "
        + commitResult.executed().size()
        + " 项，需重新打开文档）";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RouterResult)) return false;
    RouterResult that = (RouterResult) o;
    return reviewTriggered == that.reviewTriggered
        && state == that.state
        && conversationId.equals(that.conversationId)
        && Objects.equals(snapshot, that.snapshot)
        && Objects.equals(mergedPlan, that.mergedPlan)
        && Objects.equals(commitResult, that.commitResult);
  }

  @Override
  public int hashCode() {
    return Objects.hash(state, conversationId, snapshot, mergedPlan, reviewTriggered, commitResult);
  }
}
