package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.Map;
import java.util.Objects;

/**
 * 大纲视图中的修订概览：修订开关、总数和按家族分组计数。
 *
 * @param trackingEnabled 修订跟踪是否开启
 * @param totalCount 修订总数
 * @param countByType 按修订家族分组计数（key 为家族名小写，如 {@code "insert"/"delete"/"move"}）
 */
public final class RevisionOverview {

  private final boolean trackingEnabled;
  private final int totalCount;
  private final Map<String, Integer> countByType;

  public RevisionOverview(
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
    if (!(o instanceof RevisionOverview)) return false;
    RevisionOverview that = (RevisionOverview) o;
    return trackingEnabled == that.trackingEnabled
        && totalCount == that.totalCount
        && countByType.equals(that.countByType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trackingEnabled, totalCount, countByType);
  }
}
