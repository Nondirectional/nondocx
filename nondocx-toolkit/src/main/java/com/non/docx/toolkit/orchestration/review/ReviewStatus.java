package com.non.docx.toolkit.orchestration.review;

/**
 * 单条 operation 的 review 顶层状态。
 *
 * <p><b>OOXML 三层递进（review 模型）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 的修订与「校阅」语义分散在 {@code <w:ins>}（插入）、{@code <w:del>}（删除）、 {@code
 *       <w:pPrChange>}（属性变更）等元素里，Word 的「审阅」面板只是把这些元素的状态聚合给人看。
 *   <li><b>POI</b>：没有统一「review 状态」概念，{@code XWPFDocument} 只提供 tracked change 的枚举与
 *       accept/reject，没有「这条操作能不能提交」这层判断。
 *   <li><b>nondocx</b>：在 toolkit 编排层引入统一的 {@code ReviewStatus} 强类型枚举，作为「提交闸门」的 机器可读判定依据——不依赖自然语言
 *       explanation，让 review 结果能驱动 commit、UI/CLI 展示与测试断言。
 * </ul>
 *
 * <p><b>四态语义：</b>
 *
 * <ul>
 *   <li>{@link #APPROVED}——通过，可提交。
 *   <li>{@link #WARNED}——有风险但允许提交；warning 必须显式暴露在结果/日志/trace，不可静默吞掉。
 *   <li>{@link #BLOCKED}——阻断。只要 MergedPlan 中存在任一 BLOCKED，整批停止，不进入 CommitCoordinator。
 *   <li>{@link #SKIPPED}——被系统显式识别并跳过，必须保留记录与原因（含来源链），不能无痕消失。
 * </ul>
 *
 * <p>第一版为每个非 APPROVED 状态配套了小型原因枚举（见 {@link ReviewReason}），避免 review 原因退回自由文本。
 */
public enum ReviewStatus {
  /** 通过，可进入提交。 */
  APPROVED,

  /** 有风险但允许提交；warning 必须显式暴露，不可静默吞掉。 */
  WARNED,

  /** 阻断；只要存在任一 BLOCKED，整批停止，不进入 CommitCoordinator。 */
  BLOCKED,

  /** 被显式识别并跳过；必须保留记录、原因与来源链，不能无痕消失。 */
  SKIPPED;

  /** 是否为阻断态——出现即整批停止。 */
  public boolean blocksBatch() {
    return this == BLOCKED;
  }

  /** 是否进入提交队列（APPROVED 与 WARNED 都会提交）。 */
  public boolean submitted() {
    return this == APPROVED || this == WARNED;
  }
}
