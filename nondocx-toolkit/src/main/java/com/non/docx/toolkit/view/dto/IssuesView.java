package com.non.docx.toolkit.view.dto;

import java.util.List;
import java.util.Objects;

/** 问题视图：质量检查结果列表 + 汇总计数。只含未通过项（passed=false）。 */
public final class IssuesView {

  private final ViewMeta meta;
  private final int passedCount;
  private final int warningCount;
  private final int errorCount;
  private final List<IssueEntry> issues;

  public IssuesView(
      ViewMeta meta, int passedCount, int warningCount, int errorCount, List<IssueEntry> issues) {
    this.meta = Objects.requireNonNull(meta, "meta 不能为空");
    this.passedCount = passedCount;
    this.warningCount = warningCount;
    this.errorCount = errorCount;
    this.issues = List.copyOf(issues);
  }

  public ViewMeta meta() {
    return meta;
  }

  public int passedCount() {
    return passedCount;
  }

  public int warningCount() {
    return warningCount;
  }

  public int errorCount() {
    return errorCount;
  }

  public List<IssueEntry> issues() {
    return issues;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof IssuesView)) return false;
    IssuesView that = (IssuesView) o;
    return passedCount == that.passedCount
        && warningCount == that.warningCount
        && errorCount == that.errorCount
        && meta.equals(that.meta)
        && issues.equals(that.issues);
  }

  @Override
  public int hashCode() {
    return Objects.hash(meta, passedCount, warningCount, errorCount, issues);
  }
}
