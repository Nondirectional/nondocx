package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.Objects;

/**
 * 大纲视图单项：按 body 顺序的段落或表格，带 canonical ref 供后续修改定位。
 *
 * <p>第一版扁平列出（不嵌套标题树）；Agent 用 {@code headingLevel} + {@code bodyIndex} 定位。
 *
 * @param kind 元素类型：{@code "paragraph"} 或 {@code "table"}
 * @param ref canonical 引用字符串
 * @param bodyIndex body 顺序索引（含表格的交错序列位置）
 * @param headingLevel 标题级别（{@code "1"}..{@code "6"}）；非标题为 {@code null}
 * @param text 截断后的文本预览
 * @param listItem 是否为列表项
 * @param rowCount 表格行数（kind=table 时有意义；段落为 0）
 * @param columnCount 表格列数（kind=table 时有意义；段落为 0）
 */
public final class OutlineEntry {

  private final String kind;
  private final String ref;
  private final int bodyIndex;
  private final String headingLevel;
  private final String text;
  private final boolean listItem;
  private final int rowCount;
  private final int columnCount;

  public OutlineEntry(
      String kind,
      String ref,
      int bodyIndex,
      String headingLevel,
      String text,
      boolean listItem,
      int rowCount,
      int columnCount) {
    this.kind = Objects.requireNonNull(kind, "kind 不能为空");
    this.ref = Objects.requireNonNull(ref, "ref 不能为空");
    this.bodyIndex = bodyIndex;
    this.headingLevel = headingLevel;
    this.text = Objects.requireNonNull(text, "text 不能为空");
    this.listItem = listItem;
    this.rowCount = rowCount;
    this.columnCount = columnCount;
  }

  public String kind() {
    return kind;
  }

  public String ref() {
    return ref;
  }

  public int bodyIndex() {
    return bodyIndex;
  }

  public String headingLevel() {
    return headingLevel;
  }

  public String text() {
    return text;
  }

  public boolean listItem() {
    return listItem;
  }

  public int rowCount() {
    return rowCount;
  }

  public int columnCount() {
    return columnCount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof OutlineEntry)) return false;
    OutlineEntry that = (OutlineEntry) o;
    return bodyIndex == that.bodyIndex
        && listItem == that.listItem
        && rowCount == that.rowCount
        && columnCount == that.columnCount
        && kind.equals(that.kind)
        && ref.equals(that.ref)
        && Objects.equals(headingLevel, that.headingLevel)
        && text.equals(that.text);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, ref, bodyIndex, headingLevel, text, listItem, rowCount, columnCount);
  }
}
