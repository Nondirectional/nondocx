package io.github.nondirectional.docx.toolkit.view.snapshot;

import java.util.Map;
import java.util.Objects;

/**
 * 修订摘要：修订开关是否开启、修订数量、按类型/家族分组的计数。
 *
 * <p>第一版只给数量级摘要，不给单条修订明细（明细由 ReadCoordinator 补读或由 RevisionAgent 自行查询）。
 */
public final class RevisionSummary {

  private final boolean trackingEnabled;
  private final int totalCount;

  /** 按修订家族/类型分组的计数，如 {@code {"insertion": 3, "deletion": 1}}。 */
  private final Map<String, Integer> countByType;

  public RevisionSummary(
      boolean trackingEnabled, int totalCount, Map<String, Integer> countByType) {
    this.trackingEnabled = trackingEnabled;
    this.totalCount = totalCount;
    this.countByType = Map.copyOf(countByType);
  }

  public boolean trackingEnabled() {
    return trackingEnabled;
  }

  public int totalCount() {
    return totalCount;
  }

  public Map<String, Integer> countByType() {
    return countByType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RevisionSummary)) return false;
    RevisionSummary that = (RevisionSummary) o;
    return trackingEnabled == that.trackingEnabled
        && totalCount == that.totalCount
        && countByType.equals(that.countByType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trackingEnabled, totalCount, countByType);
  }
}
