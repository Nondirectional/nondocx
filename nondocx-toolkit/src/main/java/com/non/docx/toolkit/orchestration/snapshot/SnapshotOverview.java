package com.non.docx.toolkit.orchestration.snapshot;

import java.util.Objects;

/**
 * 文档概览：段落总数、表格总数、是否有修订、是否有页眉页脚等结构级摘要。
 *
 * <p>属于 {@link DocumentSnapshot} 的内容层子结构，不携带 run 级明细。
 */
public final class SnapshotOverview {

  private final int paragraphCount;
  private final int tableCount;
  private final int imageCount;
  private final int trackedChangeCount;
  private final boolean hasHeader;
  private final boolean hasFooter;
  private final boolean hasToc;

  public SnapshotOverview(
      int paragraphCount,
      int tableCount,
      int imageCount,
      int trackedChangeCount,
      boolean hasHeader,
      boolean hasFooter,
      boolean hasToc) {
    this.paragraphCount = paragraphCount;
    this.tableCount = tableCount;
    this.imageCount = imageCount;
    this.trackedChangeCount = trackedChangeCount;
    this.hasHeader = hasHeader;
    this.hasFooter = hasFooter;
    this.hasToc = hasToc;
  }

  public int paragraphCount() {
    return paragraphCount;
  }

  public int tableCount() {
    return tableCount;
  }

  public int imageCount() {
    return imageCount;
  }

  public int trackedChangeCount() {
    return trackedChangeCount;
  }

  public boolean hasHeader() {
    return hasHeader;
  }

  public boolean hasFooter() {
    return hasFooter;
  }

  public boolean hasToc() {
    return hasToc;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SnapshotOverview)) return false;
    SnapshotOverview that = (SnapshotOverview) o;
    return paragraphCount == that.paragraphCount
        && tableCount == that.tableCount
        && imageCount == that.imageCount
        && trackedChangeCount == that.trackedChangeCount
        && hasHeader == that.hasHeader
        && hasFooter == that.hasFooter
        && hasToc == that.hasToc;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        paragraphCount, tableCount, imageCount, trackedChangeCount, hasHeader, hasFooter, hasToc);
  }
}
