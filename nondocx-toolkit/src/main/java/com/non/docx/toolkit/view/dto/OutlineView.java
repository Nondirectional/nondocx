package com.non.docx.toolkit.view.dto;

import java.util.List;
import java.util.Objects;

/**
 * 大纲视图：按 body 顺序的标题/段落/表格 + section/TOC/页眉页脚/修订概览。
 *
 * <p>Agent 用它在不全文 dump 的情况下快速定位文档结构和目标元素。每项都带 canonical ref。
 */
public final class OutlineView {

  private final ViewMeta meta;
  private final List<OutlineEntry> entries;
  private final boolean hasToc;
  private final boolean hasHeader;
  private final boolean hasFooter;
  private final int sectionCount;
  private final RevisionOverview revision;

  public OutlineView(
      ViewMeta meta,
      List<OutlineEntry> entries,
      boolean hasToc,
      boolean hasHeader,
      boolean hasFooter,
      int sectionCount,
      RevisionOverview revision) {
    this.meta = Objects.requireNonNull(meta, "meta 不能为空");
    this.entries = List.copyOf(entries);
    this.hasToc = hasToc;
    this.hasHeader = hasHeader;
    this.hasFooter = hasFooter;
    this.sectionCount = sectionCount;
    this.revision = Objects.requireNonNull(revision, "revision 不能为空");
  }

  public ViewMeta meta() {
    return meta;
  }

  public List<OutlineEntry> entries() {
    return entries;
  }

  public boolean hasToc() {
    return hasToc;
  }

  public boolean hasHeader() {
    return hasHeader;
  }

  public boolean hasFooter() {
    return hasFooter;
  }

  public int sectionCount() {
    return sectionCount;
  }

  public RevisionOverview revision() {
    return revision;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof OutlineView)) return false;
    OutlineView that = (OutlineView) o;
    return hasToc == that.hasToc
        && hasHeader == that.hasHeader
        && hasFooter == that.hasFooter
        && sectionCount == that.sectionCount
        && meta.equals(that.meta)
        && entries.equals(that.entries)
        && revision.equals(that.revision);
  }

  @Override
  public int hashCode() {
    return Objects.hash(meta, entries, hasToc, hasHeader, hasFooter, sectionCount, revision);
  }
}
