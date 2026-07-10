package com.non.docx.toolkit.orchestration;

import com.non.docx.toolkit.orchestration.commit.CommitResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一轮用户请求的高层摘要结果：高层 {@code run(...)} / {@code chat(...)} 默认返回对象。
 *
 * <p><b>设计目标。</b> 高层入口默认只返回摘要，避免把完整阶段产物（snapshot / expert plans / merged plan / review / commit
 * result）一股脑塞给调用方——完整产物仅在 debug 模式或低层 API 中暴露，兼顾易用性与可观测性。
 *
 * <p><b>摘要内容（父任务决策）。</b> 除自然语言 {@code summaryText} 外，还显式包含：
 *
 * <ul>
 *   <li>统计字段——执行 {@code executedCount}、警告 {@code warnedCount}、跳过 {@code skippedCount}、 阻断 {@code
 *       blockedCount}。
 *   <li>精简操作清单 {@code operations}——每项含 {@code operationId}、{@code status}、 {@code shortLabel}、可选
 *       {@code reason}。
 * </ul>
 *
 * <p><b>本轮视图。</b> 高层摘要默认展示「本轮新增结果」，不混入整个会话累计视图；累计视图保留给 debug 模式、低层 API 或后续专门的会话汇总能力。
 */
public final class RunSummary {

  private final String conversationId;
  private final String summaryText;
  private final int executedCount;
  private final int warnedCount;
  private final int skippedCount;
  private final int blockedCount;
  private final List<Map<String, Object>> operations;
  private final CommitResult commitResult;

  public RunSummary(
      String conversationId,
      String summaryText,
      int executedCount,
      int warnedCount,
      int skippedCount,
      int blockedCount,
      List<Map<String, Object>> operations,
      CommitResult commitResult) {
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId 不能为空");
    this.summaryText = Objects.requireNonNull(summaryText, "summaryText 不能为空");
    this.executedCount = executedCount;
    this.warnedCount = warnedCount;
    this.skippedCount = skippedCount;
    this.blockedCount = blockedCount;
    // 防御性深拷贝：每项 shortView 已是不可变 Map，这里再包一层不可变 List
    this.operations = List.copyOf(operations);
    this.commitResult = commitResult;
  }

  /** 所属会话标识。 */
  public String conversationId() {
    return conversationId;
  }

  /** 自然语言总结（面向人）。 */
  public String summaryText() {
    return summaryText;
  }

  public int executedCount() {
    return executedCount;
  }

  public int warnedCount() {
    return warnedCount;
  }

  public int skippedCount() {
    return skippedCount;
  }

  public int blockedCount() {
    return blockedCount;
  }

  /** 精简操作清单：每项含 operationId/status/shortLabel，可选 reason。 */
  public List<Map<String, Object>> operations() {
    return operations;
  }

  /** 提交结果（可能为 null——例如 BLOCKED 整批未进 commit）。 */
  public CommitResult commitResult() {
    return commitResult;
  }

  /**
   * 从 MergedPlan 与 CommitResult 构建摘要。
   *
   * <p><b>统计语义。</b>
   *
   * <ul>
   *   <li>{@code executedCount}——commit <b>实际成功执行</b>的条数（来自 commitResult，非 plan 预期）。 当 commit
   *       失败（遇错即停）时，只有 CommitResult.executed 列表里的算成功。
   *   <li>{@code warnedCount} / {@code blockedCount} / {@code skippedCount}——来自 review 结果 （review
   *       影响是否进 commit，是 plan 层判定）。
   * </ul>
   *
   * <p>这样 FAILED 场景下 executedCount 能正确反映「实际改了几条」，而非「计划想改几条」——避免日志出现 「executed=1 但文档没改」的误导。
   *
   * @param conversationId 会话标识
   * @param summaryText 自然语言总结
   * @param merged 合并计划（warning/blocked/skipped 统计 + 精简清单来源）
   * @param commitResult 提交结果（executed 统计来源；整批 BLOCKED 未进 commit 时传 null）
   */
  public static RunSummary from(
      String conversationId, String summaryText, MergedPlan merged, CommitResult commitResult) {
    int warned = 0, skipped = 0, blocked = 0;
    List<Map<String, Object>> ops = new java.util.ArrayList<>();
    for (Operation op : merged.operations()) {
      Map<String, Object> view = new LinkedHashMap<>(op.shortView());
      // 如果 commit 失败且这条 operation 是失败点，在精简视图里标注 FAILED
      if (commitResult != null
          && !commitResult.allSucceeded()
          && op.operationId().equals(commitResult.failedOperationId())) {
        view.put("status", "FAILED");
        view.put("reason", commitResult.failureMessage());
      }
      ops.add(view);
      switch (op.reviewStatus()) {
        case WARNED:
          warned++;
          break;
        case BLOCKED:
          blocked++;
          break;
        case SKIPPED:
          skipped++;
          break;
        default:
          break;
      }
    }
    // executed 取 commit 实际成功数（而非 plan 里 APPROVED 的条数）
    int executed = commitResult == null ? 0 : commitResult.executed().size();
    return new RunSummary(
        conversationId, summaryText, executed, warned, skipped, blocked, ops, commitResult);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RunSummary)) return false;
    RunSummary that = (RunSummary) o;
    return executedCount == that.executedCount
        && warnedCount == that.warnedCount
        && skippedCount == that.skippedCount
        && blockedCount == that.blockedCount
        && conversationId.equals(that.conversationId)
        && summaryText.equals(that.summaryText)
        && operations.equals(that.operations)
        && Objects.equals(commitResult, that.commitResult);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        conversationId,
        summaryText,
        executedCount,
        warnedCount,
        skippedCount,
        blockedCount,
        operations,
        commitResult);
  }
}
