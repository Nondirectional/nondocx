package com.non.docx.toolkit.view.dto;

import com.non.docx.toolkit.view.snapshot.DocumentSnapshot;
import java.util.Objects;

/**
 * 视图元信息：所有视图 DTO 内嵌的版本与截断标记。
 *
 * <p>Agent 据此判断视图来自哪一代次、是否被 maxItems 截断，避免用过期视图驱动写操作。
 *
 * @param snapshotVersion 快照 schema 版本（与 {@link DocumentSnapshot#SNAPSHOT_VERSION} 一致）
 * @param sessionGeneration 会话代次（close/reopen/reset 递增）
 * @param createdAt 快照生成时刻（ISO-8601）
 * @param truncated 是否因 maxItems 截断
 * @param totalCount 未截断时的总条数（截断时 > 返回条数；未截断时等于返回条数）
 */
public final class ViewMeta {

  private final int snapshotVersion;
  private final long sessionGeneration;
  private final String createdAt;
  private final boolean truncated;
  private final int totalCount;

  public ViewMeta(
      int snapshotVersion,
      long sessionGeneration,
      String createdAt,
      boolean truncated,
      int totalCount) {
    this.snapshotVersion = snapshotVersion;
    this.sessionGeneration = sessionGeneration;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    this.truncated = truncated;
    this.totalCount = totalCount;
  }

  public int snapshotVersion() {
    return snapshotVersion;
  }

  public long sessionGeneration() {
    return sessionGeneration;
  }

  public String createdAt() {
    return createdAt;
  }

  public boolean truncated() {
    return truncated;
  }

  public int totalCount() {
    return totalCount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ViewMeta)) return false;
    ViewMeta that = (ViewMeta) o;
    return snapshotVersion == that.snapshotVersion
        && sessionGeneration == that.sessionGeneration
        && createdAt.equals(that.createdAt)
        && truncated == that.truncated
        && totalCount == that.totalCount;
  }

  @Override
  public int hashCode() {
    return Objects.hash(snapshotVersion, sessionGeneration, createdAt, truncated, totalCount);
  }
}
