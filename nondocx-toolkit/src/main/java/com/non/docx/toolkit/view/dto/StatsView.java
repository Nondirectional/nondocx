package com.non.docx.toolkit.view.dto;

import java.util.List;
import java.util.Objects;

/**
 * 统计视图：段落/表格/图片/section/修订数量 + 字体/字号聚合。
 *
 * <p>是 {@code get_document_overview} 旧 4 int（正文段落数/正文表格数/body 元素数/section 数）的超集。
 */
public final class StatsView {

  private final ViewMeta meta;
  private final int paragraphCount;
  private final int tableCount;
  private final int imageCount;
  private final int sectionCount;
  private final int bodyElementCount;
  private final int trackedChangeCount;
  private final boolean hasToc;
  private final boolean hasHeader;
  private final boolean hasFooter;
  private final List<FontStat> fonts;
  private final List<Integer> fontSizes;

  public StatsView(
      ViewMeta meta,
      int paragraphCount,
      int tableCount,
      int imageCount,
      int sectionCount,
      int bodyElementCount,
      int trackedChangeCount,
      boolean hasToc,
      boolean hasHeader,
      boolean hasFooter,
      List<FontStat> fonts,
      List<Integer> fontSizes) {
    this.meta = Objects.requireNonNull(meta, "meta 不能为空");
    this.paragraphCount = paragraphCount;
    this.tableCount = tableCount;
    this.imageCount = imageCount;
    this.sectionCount = sectionCount;
    this.bodyElementCount = bodyElementCount;
    this.trackedChangeCount = trackedChangeCount;
    this.hasToc = hasToc;
    this.hasHeader = hasHeader;
    this.hasFooter = hasFooter;
    this.fonts = List.copyOf(fonts);
    this.fontSizes = List.copyOf(fontSizes);
  }

  public ViewMeta meta() {
    return meta;
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

  public int sectionCount() {
    return sectionCount;
  }

  public int bodyElementCount() {
    return bodyElementCount;
  }

  public int trackedChangeCount() {
    return trackedChangeCount;
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

  public List<FontStat> fonts() {
    return fonts;
  }

  public List<Integer> fontSizes() {
    return fontSizes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof StatsView)) return false;
    StatsView that = (StatsView) o;
    return paragraphCount == that.paragraphCount
        && tableCount == that.tableCount
        && imageCount == that.imageCount
        && sectionCount == that.sectionCount
        && bodyElementCount == that.bodyElementCount
        && trackedChangeCount == that.trackedChangeCount
        && hasToc == that.hasToc
        && hasHeader == that.hasHeader
        && hasFooter == that.hasFooter
        && meta.equals(that.meta)
        && fonts.equals(that.fonts)
        && fontSizes.equals(that.fontSizes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        meta,
        paragraphCount,
        tableCount,
        imageCount,
        sectionCount,
        bodyElementCount,
        trackedChangeCount,
        hasToc,
        hasHeader,
        hasFooter,
        fonts,
        fontSizes);
  }
}
