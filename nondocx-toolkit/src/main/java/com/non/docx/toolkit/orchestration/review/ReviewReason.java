package com.non.docx.toolkit.orchestration.review;

import java.util.Locale;

/**
 * review 原因枚举：把「为什么这条 operation 被打上某个状态」做成机器可读，避免退回自由文本。
 *
 * <p>每个原因明确绑定到一个 {@link ReviewStatus}（见 {@link #status()}），形成稳定的 (status, reason) 二元组，供测试断言、UI 展示与
 * trace 追踪。
 *
 * <p>第一版枚举集<b>刻意小而稳</b>——优先覆盖父任务 PRD 已点名的风险场景；后续如需扩充只在对应分组内增量追加， 不破坏既有取值。
 */
public enum ReviewReason {
  // ==================== SKIPPED：被显式识别并跳过 ====================

  /** SKIPPED：与另一条 operation 重复，已被去重吸收到保留项（配合 mergedIntoOperationId）。 */
  DUPLICATE_MERGED(ReviewStatus.SKIPPED),

  /** SKIPPED：被另一条更完整/更新近的 operation 取代。 */
  SUPERSEDED(ReviewStatus.SKIPPED),

  /** SKIPPED：超出当前任务或工具组范围。 */
  OUT_OF_SCOPE_SKIPPED(ReviewStatus.SKIPPED),

  /** SKIPPED：置信度过低，主动放弃。 */
  LOW_CONFIDENCE_SKIPPED(ReviewStatus.SKIPPED),

  /** SKIPPED：因冲突而被丢弃（不可安全合并）。 */
  CONFLICT_DROPPED(ReviewStatus.SKIPPED),

  // ==================== WARNED：有风险但允许提交 ====================

  /** WARNED：触发了质量风险（对应 QualityAgent 的软告警）。 */
  QUALITY_RISK(ReviewStatus.WARNED),

  /** WARNED：置信度偏低但仍提交，需告知调用方。 */
  LOW_CONFIDENCE_WARNED(ReviewStatus.WARNED),

  /** WARNED：可能与其它 operation 冲突，但不足以阻断。 */
  POTENTIAL_CONFLICT(ReviewStatus.WARNED),

  /** WARNED：上下文不完整（例如缺 run 级明细），已尽力提交但需提示。 */
  PARTIAL_CONTEXT(ReviewStatus.WARNED),

  // ==================== BLOCKED：阻断，整批停止 ====================

  /** BLOCKED：不安全的冲突，不能自动合并。 */
  UNSAFE_CONFLICT(ReviewStatus.BLOCKED),

  /** BLOCKED：缺少提交所必需的上下文（例如 targetRef 无法解析）。 */
  MISSING_REQUIRED_CONTEXT(ReviewStatus.BLOCKED),

  /** BLOCKED：超出安全提交范围（例如子代理产出了跨工具组复合操作）。 */
  OUT_OF_SCOPE_BLOCKED(ReviewStatus.BLOCKED),

  /** BLOCKED：质量门禁失败（对应 QualityAgent 的硬失败）。 */
  QUALITY_GATE_FAILED(ReviewStatus.BLOCKED);

  private final ReviewStatus status;

  ReviewReason(ReviewStatus status) {
    this.status = status;
  }

  /** 该原因所属的 review 状态。 */
  public ReviewStatus status() {
    return status;
  }

  /**
   * 稳定的机器可读 code（供 {@code ruleCode} 字段与 trace 使用）。
   *
   * <p>形如 {@code "SKIPPED:DUPLICATE_MERGED"}——状态前缀 + 枚举名，全大写、稳定不变。
   */
  public String code() {
    return status.name() + ":" + name().toUpperCase(Locale.ROOT);
  }
}
