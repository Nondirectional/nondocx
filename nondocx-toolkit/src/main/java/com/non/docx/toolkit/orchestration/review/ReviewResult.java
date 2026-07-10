package com.non.docx.toolkit.orchestration.review;

import java.util.Objects;

/**
 * 单条 operation 的 review 结果：强类型状态 + 原因 + ruleCode + 说明。
 *
 * <p><b>为什么用值对象而非散落字段。</b> review 结果会被 commit 闸门、UI/CLI 展示、测试断言与 trace 反复读取。 收敛成一个不可变值对象后，{@code
 * (status, reason)} 的一致性约束可以在构造时校验，避免出现 「status=BLOCKED 但 reason=QUALITY_RISK」这类跨组错配。
 *
 * <p><b>APPROVED 特例。</b> APPROVED 没有「风险原因」，故 reason 字段对 APPROVED 为 {@code null}， ruleCode 固定为
 * {@code "APPROVED:OK"}。其余三个状态必须携带真实 {@link ReviewReason}，且 reason 的 {@link ReviewReason#status()}
 * 必须与 status 一致，否则构造抛异常。
 *
 * <p><b>不可变性。</b> 所有字段 final；review 结果一旦产出即不可变，便于在多阶段流程中安全传递。
 */
public final class ReviewResult {

  private final ReviewStatus status;
  private final ReviewReason reason;
  private final String ruleCode;
  private final String explanation;

  private ReviewResult(
      ReviewStatus status, ReviewReason reason, String ruleCode, String explanation) {
    this.status = Objects.requireNonNull(status, "reviewStatus 不能为空");
    this.ruleCode = Objects.requireNonNull(ruleCode, "ruleCode 不能为空");
    this.explanation = explanation == null ? "" : explanation;
    // APPROVED 无原因；非 APPROVED 必须携带原因且与状态匹配。
    if (status == ReviewStatus.APPROVED) {
      this.reason = null;
    } else {
      this.reason = Objects.requireNonNull(reason, "非 APPROVED 状态的 reviewReason 不能为空");
      if (reason.status() != status) {
        throw new IllegalArgumentException(
            "review 状态与原因不匹配：status=" + status + " 但 reason=" + reason + " 属于 " + reason.status());
      }
    }
  }

  /**
   * 基于「原因」构造：状态与 ruleCode 由原因自动推导。
   *
   * <p>非 APPROVED 状态的推荐入口——保证 (status, reason, ruleCode) 三者一致。explanation 可为空串。
   */
  public static ReviewResult of(ReviewReason reason, String explanation) {
    return new ReviewResult(reason.status(), reason, reason.code(), explanation);
  }

  /** 显式 APPROVED（无风险原因），ruleCode 固定为 {@code "APPROVED:OK"}。 */
  public static ReviewResult approved() {
    return new ReviewResult(ReviewStatus.APPROVED, null, "APPROVED:OK", "");
  }

  /** 显式 APPROVED 并附说明。 */
  public static ReviewResult approved(String explanation) {
    return new ReviewResult(ReviewStatus.APPROVED, null, "APPROVED:OK", explanation);
  }

  /** review 状态。 */
  public ReviewStatus status() {
    return status;
  }

  /**
   * review 原因（机器可读枚举）。
   *
   * @return APPROVED 返回 {@code null}（APPROVED 无风险原因）；其余状态返回对应 {@link ReviewReason}。
   */
  public ReviewReason reason() {
    return reason;
  }

  /**
   * 命中的具体规则 code（机器可读）。
   *
   * <p>标识命中的质量规则、冲突规则、上下文规则或安全规则，形如 {@code "BLOCKED:QUALITY_GATE_FAILED"}。 review 结果不只存在于
   * explanation 文本中，可被程序读取。
   */
  public String ruleCode() {
    return ruleCode;
  }

  /** 自由文本说明（面向人），用于 review、trace、失败复盘与 UI/CLI 展示。可为空串。 */
  public String explanation() {
    return explanation;
  }

  /** 是否阻断整批。 */
  public boolean blocksBatch() {
    return status.blocksBatch();
  }

  /** 是否进入提交队列。 */
  public boolean submitted() {
    return status.submitted();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ReviewResult)) return false;
    ReviewResult that = (ReviewResult) o;
    return status == that.status
        && reason == that.reason
        && ruleCode.equals(that.ruleCode)
        && explanation.equals(that.explanation);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, reason, ruleCode, explanation);
  }

  @Override
  public String toString() {
    return "ReviewResult{" + ruleCode + (explanation.isEmpty() ? "" : ": " + explanation) + '}';
  }
}
