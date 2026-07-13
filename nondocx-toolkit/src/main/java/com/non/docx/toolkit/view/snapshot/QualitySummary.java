package com.non.docx.toolkit.view.snapshot;

import java.util.List;
import java.util.Objects;

/**
 * 质量风险摘要：从 QualityCheckTools 的结论里提炼的风险条目。
 *
 * <p><b>与 QualityAgent 的关系。</b> snapshot 里的 quality summary 是<b>基线快照</b>——反映文档当前
 * 已知的质量风险；QualityAgent 在 PLAN 阶段还会基于即将提交的 operation 再做一次检查，产出 {@code WARNED(QUALITY_RISK)} 或 {@code
 * BLOCKED(QUALITY_GATE_FAILED)}。
 */
public final class QualitySummary {

  private final int blockingCount;
  private final int warningCount;

  /** 风险条目的简短描述列表（如 {@code "空白页: 第3页"}）。 */
  private final List<String> items;

  public QualitySummary(int blockingCount, int warningCount, List<String> items) {
    this.blockingCount = blockingCount;
    this.warningCount = warningCount;
    this.items = List.copyOf(items);
  }

  public int blockingCount() {
    return blockingCount;
  }

  public int warningCount() {
    return warningCount;
  }

  public List<String> items() {
    return items;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof QualitySummary)) return false;
    QualitySummary that = (QualitySummary) o;
    return blockingCount == that.blockingCount
        && warningCount == that.warningCount
        && items.equals(that.items);
  }

  @Override
  public int hashCode() {
    return Objects.hash(blockingCount, warningCount, items);
  }
}
