package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 大纲视图中的修订概览：修订开关、总数、按家族/作者分组计数和最近明细。
 *
 * @param trackingEnabled 修订跟踪是否开启
 * @param totalCount 修订总数
 * @param countByType 按修订家族分组计数（key 为家族名小写，如 {@code "insert"/"delete"/"move"}）
 * @param countByAuthor 按作者分组计数（复审核对「作者填了谁」的依据）
 * @param recent 最近若干条修订明细（ref/type/author/片段，可直通 view_element 查详情）
 */
public final class RevisionOverview {

  private final boolean trackingEnabled;
  private final int totalCount;
  private final Map<String, Integer> countByType;
  private final Map<String, Integer> countByAuthor;
  private final List<RevisionEntry> recent;

  public RevisionOverview(
      boolean trackingEnabled,
      int totalCount,
      Map<String, Integer> countByType,
      Map<String, Integer> countByAuthor,
      List<RevisionEntry> recent) {
    this.trackingEnabled = trackingEnabled;
    this.totalCount = totalCount;
    this.countByType = Map.copyOf(countByType);
    this.countByAuthor = Map.copyOf(countByAuthor);
    this.recent = List.copyOf(recent);
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

  public Map<String, Integer> countByAuthor() {
    return countByAuthor;
  }

  public List<RevisionEntry> recent() {
    return recent;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RevisionOverview)) return false;
    RevisionOverview that = (RevisionOverview) o;
    return trackingEnabled == that.trackingEnabled
        && totalCount == that.totalCount
        && countByType.equals(that.countByType)
        && countByAuthor.equals(that.countByAuthor)
        && recent.equals(that.recent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trackingEnabled, totalCount, countByType, countByAuthor, recent);
  }
}
