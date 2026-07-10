package com.non.docx.toolkit.orchestration.commit;

import java.util.List;
import java.util.Objects;

/**
 * CommitCoordinator 的提交结果：记录已执行步骤、失败点（若有）与会话后续建议。
 *
 * <p><b>非事务语义（第一版决策）。</b> 提交按顺序执行，遇错即停，<b>不</b>承诺自动回滚已完成修改。 本结果必须把以下信息显式返回给 RouterAgent：
 *
 * <ul>
 *   <li>{@code executed}——已成功执行的 operationId 列表（按执行顺序）。
 *   <li>{@code failedOperationId}——失败点的 operationId；全部成功时为 {@code null}。
 *   <li>{@code failureMessage}——失败原因（面向人）。
 *   <li>{@code shouldReopen}——是否建议 close + reopen 后再进入下一轮。失败时为 true。
 * </ul>
 *
 * <p>提交失败后，RouterAgent 必须关闭当前会话并从磁盘重新打开文档，再发起新一轮分析与提交， <b>不在半修改内存态上继续补丁式修复</b>。
 */
public final class CommitResult {

  private final boolean allSucceeded;
  private final List<String> executed;
  private final String failedOperationId;
  private final String failureMessage;
  private final boolean shouldReopen;

  private CommitResult(
      boolean allSucceeded,
      List<String> executed,
      String failedOperationId,
      String failureMessage,
      boolean shouldReopen) {
    this.allSucceeded = allSucceeded;
    this.executed = List.copyOf(executed);
    this.failedOperationId = failedOperationId;
    this.failureMessage = failureMessage == null ? "" : failureMessage;
    this.shouldReopen = shouldReopen;
  }

  /** 全部成功的便捷构造。 */
  public static CommitResult success(List<String> executed) {
    return new CommitResult(true, executed, null, "", false);
  }

  /** 在某个 operation 上失败的构造；自动标记 shouldReopen=true。 */
  public static CommitResult failure(
      List<String> executed, String failedOperationId, String failureMessage) {
    Objects.requireNonNull(failedOperationId, "failedOperationId 不能为空");
    return new CommitResult(false, executed, failedOperationId, failureMessage, true);
  }

  /** 是否全部成功。 */
  public boolean allSucceeded() {
    return allSucceeded;
  }

  /** 已成功执行的 operationId 列表（按执行顺序）。 */
  public List<String> executed() {
    return executed;
  }

  /** 失败点的 operationId；全部成功时为 null。 */
  public String failedOperationId() {
    return failedOperationId;
  }

  /** 失败原因（面向人）。 */
  public String failureMessage() {
    return failureMessage;
  }

  /** 是否建议 close + reopen 后再进入下一轮。 */
  public boolean shouldReopen() {
    return shouldReopen;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CommitResult)) return false;
    CommitResult that = (CommitResult) o;
    return allSucceeded == that.allSucceeded
        && shouldReopen == that.shouldReopen
        && executed.equals(that.executed)
        && Objects.equals(failedOperationId, that.failedOperationId)
        && failureMessage.equals(that.failureMessage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(allSucceeded, executed, failedOperationId, failureMessage, shouldReopen);
  }

  @Override
  public String toString() {
    if (allSucceeded) {
      return "CommitResult{成功，执行 " + executed.size() + " 项}";
    }
    return "CommitResult{失败于 "
        + failedOperationId
        + "，已执行 "
        + executed.size()
        + " 项："
        + failureMessage
        + "}";
  }
}
