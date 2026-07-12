package com.non.docx.toolkit.view.dto;

import java.util.Objects;

/**
 * 文本视图单项：按 body 顺序的段落或表格文本，带 canonical ref。
 *
 * @param kind 元素类型：{@code "paragraph"} 或 {@code "table"}
 * @param ref canonical 引用字符串
 * @param bodyIndex body 顺序索引
 * @param text 截断后的文本
 */
public final class TextEntry {

  private final String kind;
  private final String ref;
  private final int bodyIndex;
  private final String text;

  public TextEntry(String kind, String ref, int bodyIndex, String text) {
    this.kind = Objects.requireNonNull(kind, "kind 不能为空");
    this.ref = Objects.requireNonNull(ref, "ref 不能为空");
    this.bodyIndex = bodyIndex;
    this.text = Objects.requireNonNull(text, "text 不能为空");
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

  public String text() {
    return text;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TextEntry)) return false;
    TextEntry that = (TextEntry) o;
    return bodyIndex == that.bodyIndex
        && kind.equals(that.kind)
        && ref.equals(that.ref)
        && text.equals(that.text);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, ref, bodyIndex, text);
  }
}
