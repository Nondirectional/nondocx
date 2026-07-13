package com.non.docx.toolkit.view.snapshot;

import com.non.docx.toolkit.ref.ParagraphRef;
import java.util.Objects;

/**
 * 段落预览：段落索引 + body 索引 + 短文本预览 + 是否为标题/列表项等结构标记。
 *
 * <p><b>两个索引的区别（核心）。</b>
 *
 * <ul>
 *   <li>{@code index}——<b>段落投影索引</b>：只数段落、跳过表格，即 {@code doc.paragraphs()} 列表里的下标。 用于
 *       replace_run_text / update_run_style / update_paragraph_alignment（payload 的 {@code
 *       paragraph_index}）。
 *   <li>{@code bodyIndex}——<b>body 顺序索引</b>：段落和表格在 {@code <w:body>} 里交错排列，各占一个 slot； bodyIndex
 *       是该段落在 body 交错序列中的绝对位置。用于 insert_paragraph / insert_heading（payload 的 {@code body_index}）。
 * </ul>
 *
 * <p>无表格时两者相等；有表格时两者可能不同。例如 body 顺序为 {@code [表格, 段落A, 段落B]} 时， 段落A 的 {@code index=0}（第一个段落）、{@code
 * bodyIndex=1}（body 里排在表格之后）。
 *
 * <p><b>粒度。</b> 第一版停在 paragraph 级——只给短文本预览（截断），<b>不</b>给 run 级明细。 run 细节由 {@code ReadCoordinator}
 * 按需补读。
 */
public final class ParagraphPreview {

  private final ParagraphRef ref;
  private final int index;
  private final int bodyIndex;
  private final String text;
  private final String headingLevel;
  private final boolean listItem;

  /**
   * @param ref 稳定段落引用
   * @param index 段落投影索引（{@code doc.paragraphs()} 列表下标，跳过表格）；replace/update 类操作用
   * @param bodyIndex body 顺序索引（含表格的交错序列位置）；insert 类操作用
   * @param text 截断后的短文本预览（第一版约定 ≤ 80 字符）
   * @param headingLevel 标题级别（如 {@code "1"} / {@code "2"}）；非标题为 {@code null}
   * @param listItem 是否为列表项
   */
  public ParagraphPreview(
      ParagraphRef ref,
      int index,
      int bodyIndex,
      String text,
      String headingLevel,
      boolean listItem) {
    this.ref = Objects.requireNonNull(ref, "ref 不能为空");
    this.index = index;
    this.bodyIndex = bodyIndex;
    this.text = Objects.requireNonNull(text, "text 不能为空");
    this.headingLevel = headingLevel;
    this.listItem = listItem;
  }

  /** 稳定段落引用。 */
  public ParagraphRef ref() {
    return ref;
  }

  /** 段落投影索引（{@code doc.paragraphs()} 列表下标，跳过表格）。 */
  public int index() {
    return index;
  }

  /** body 顺序索引（含表格的交错序列位置）。 */
  public int bodyIndex() {
    return bodyIndex;
  }

  public String text() {
    return text;
  }

  public String headingLevel() {
    return headingLevel;
  }

  public boolean isListItem() {
    return listItem;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ParagraphPreview)) return false;
    ParagraphPreview that = (ParagraphPreview) o;
    return ref.equals(that.ref)
        && index == that.index
        && bodyIndex == that.bodyIndex
        && text.equals(that.text)
        && Objects.equals(headingLevel, that.headingLevel)
        && listItem == that.listItem;
  }

  @Override
  public int hashCode() {
    return Objects.hash(ref, index, bodyIndex, text, headingLevel, listItem);
  }
}
