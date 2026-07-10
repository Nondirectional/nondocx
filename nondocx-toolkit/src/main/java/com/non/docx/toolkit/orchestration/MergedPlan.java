package com.non.docx.toolkit.orchestration;

import java.util.List;
import java.util.Objects;

/**
 * RouterAgent 合并后的统一执行视图：跨专家的 operation 排序、去重与冲突处理后，供 review/commit/trace 使用。
 *
 * <p><b>OOXML 三层递进（merged plan）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 的最终落盘结果由实际写入顺序决定，没有「合并计划」这种中间态。
 *   <li><b>POI</b>：写入顺序即执行顺序，没有冲突检测、优先级排序的概念。
 *   <li><b>nondocx</b>：在编排层引入 {@code MergedPlan}，把多个 {@link ExpertPlan} 的 operation 收敛、
 *       去重、按固定优先级排序后，形成<b>单一</b>执行视图——保留来源 {@code sourceExpertPlans} 以维护 专家提案到执行项的来源链，便于冲突检测、review
 *       批注、失败定位与日志关联。
 * </ul>
 *
 * <p><b>固定优先级排序（第一版）。</b> 默认顺序为：
 *
 * <ol>
 *   <li>结构变更（增删段落/表格/行列）
 *   <li>文本/样式变更（替换文本、改样式）
 *   <li>修订相关操作（accept/reject/insert/delete revision）
 *   <li>质量复查
 *   <li>保存前检查
 * </ol>
 *
 * 提交/保存/关闭属于 coordinator 生命周期动作，不进入专家 plan 排序。
 *
 * <p><b>review 闸门。</b> 只要 MergedPlan 中存在任一 {@code BLOCKED} operation，整批停止，不进入
 * CommitCoordinator。{@code WARNED} 允许提交但必须显式暴露；{@code SKIPPED} 必须保留原因与来源链。
 */
public final class MergedPlan {

  /** plan schema 版本，第一版固定为 1（与 ExpertPlan 同版本演进）。 */
  public static final int SCHEMA_VERSION = 1;

  private final int schemaVersion;
  private final String conversationId;
  private final String mergedPlanId;
  private final List<String> sourceExpertPlans;
  private final List<Operation> operations;

  public MergedPlan(
      String conversationId,
      String mergedPlanId,
      List<String> sourceExpertPlans,
      List<Operation> operations) {
    this.schemaVersion = SCHEMA_VERSION;
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId 不能为空");
    this.mergedPlanId = Objects.requireNonNull(mergedPlanId, "mergedPlanId 不能为空");
    this.sourceExpertPlans = List.copyOf(sourceExpertPlans);
    this.operations = List.copyOf(operations);
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  public String conversationId() {
    return conversationId;
  }

  public String mergedPlanId() {
    return mergedPlanId;
  }

  public List<String> sourceExpertPlans() {
    return sourceExpertPlans;
  }

  public List<Operation> operations() {
    return operations;
  }

  /** 是否存在任一 BLOCKED operation（出现即整批停止）。 */
  public boolean hasBlocked() {
    return operations.stream().anyMatch(op -> op.reviewStatus().blocksBatch());
  }

  /** 是否存在任一 WARNED operation（允许提交，但必须显式暴露）。 */
  public boolean hasWarned() {
    return operations.stream()
        .anyMatch(
            op ->
                op.reviewStatus() == com.non.docx.toolkit.orchestration.review.ReviewStatus.WARNED);
  }

  /** 是否存在任一 SKIPPED operation。 */
  public boolean hasSkipped() {
    return operations.stream()
        .anyMatch(
            op ->
                op.reviewStatus()
                    == com.non.docx.toolkit.orchestration.review.ReviewStatus.SKIPPED);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof MergedPlan)) return false;
    MergedPlan that = (MergedPlan) o;
    return schemaVersion == that.schemaVersion
        && conversationId.equals(that.conversationId)
        && mergedPlanId.equals(that.mergedPlanId)
        && sourceExpertPlans.equals(that.sourceExpertPlans)
        && operations.equals(that.operations);
  }

  @Override
  public int hashCode() {
    return Objects.hash(schemaVersion, conversationId, mergedPlanId, sourceExpertPlans, operations);
  }
}
