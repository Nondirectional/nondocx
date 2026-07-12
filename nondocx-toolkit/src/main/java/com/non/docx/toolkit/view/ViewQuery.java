package com.non.docx.toolkit.view;

import java.util.Objects;

/**
 * 视图查询参数：控制返回条数、文本截断长度和是否展开 run。
 *
 * <p>不可变值对象；用 {@link #withMaxItems(int)} / {@link #withTextTruncate(int)} / {@link
 * #withExpandRuns(boolean)} wither 派生新实例。
 *
 * <ul>
 *   <li>{@code maxItems}——最大返回条数（默认 200）。超过截断并在 {@link dto.ViewMeta} 标记 {@code
 *       truncated=true}。{@code element} 视图不走此限制（单元素全量）。
 *   <li>{@code textTruncate}——outline/text 视图的文本截断长度（默认 120）。
 *   <li>{@code expandRuns}——annotated 视图是否展开 run 级明细（默认 false）。false 时只给段落级文本， Agent
 *       定位到目标后再展开，避免大型文档逐段解析 run 的成本。
 * </ul>
 */
public final class ViewQuery {

  /** 默认最大返回条数。 */
  public static final int DEFAULT_MAX_ITEMS = 200;

  /** 默认文本截断长度。 */
  public static final int DEFAULT_TEXT_TRUNCATE = 120;

  private final int maxItems;
  private final int textTruncate;
  private final boolean expandRuns;

  private ViewQuery(int maxItems, int textTruncate, boolean expandRuns) {
    this.maxItems = maxItems;
    this.textTruncate = textTruncate;
    this.expandRuns = expandRuns;
  }

  /** 默认查询参数：maxItems=200, textTruncate=120, expandRuns=false。 */
  public static ViewQuery defaults() {
    return new ViewQuery(DEFAULT_MAX_ITEMS, DEFAULT_TEXT_TRUNCATE, false);
  }

  /** 最大返回条数。 */
  public int maxItems() {
    return maxItems;
  }

  /** 文本截断长度。 */
  public int textTruncate() {
    return textTruncate;
  }

  /** annotated 视图是否展开 run 级明细。 */
  public boolean expandRuns() {
    return expandRuns;
  }

  /** 派生一个调整 maxItems 的新查询参数。 */
  public ViewQuery withMaxItems(int maxItems) {
    return new ViewQuery(maxItems, this.textTruncate, this.expandRuns);
  }

  /** 派生一个调整 textTruncate 的新查询参数。 */
  public ViewQuery withTextTruncate(int textTruncate) {
    return new ViewQuery(this.maxItems, textTruncate, this.expandRuns);
  }

  /** 派生一个调整 expandRuns 的新查询参数。 */
  public ViewQuery withExpandRuns(boolean expandRuns) {
    return new ViewQuery(this.maxItems, this.textTruncate, expandRuns);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ViewQuery)) return false;
    ViewQuery that = (ViewQuery) o;
    return maxItems == that.maxItems
        && textTruncate == that.textTruncate
        && expandRuns == that.expandRuns;
  }

  @Override
  public int hashCode() {
    return Objects.hash(maxItems, textTruncate, expandRuns);
  }
}
