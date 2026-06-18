package com.non.docx.core.api.track;

import com.non.docx.core.internal.util.Objects;

/**
 * 文本类修订(插入 / 删除)的 payload。
 *
 * <p>回答「这条修订插入了什么文本」或「删除了什么文本」。
 *
 * <p><b>OOXML 对应。</b>
 *
 * <ul>
 *   <li>插入修订 {@code <w:ins>}:其内若干 {@code <w:r>/<w:t>} 的可见文本拼起来,即被插入的内容。
 *   <li>删除修订 {@code <w:del>}:其内若干 {@code <w:r>/<w:delText>}(注意是 {@code delText} 而非 {@code t})
 *       的文本拼起来,即被删除的内容。
 * </ul>
 *
 * 因此本类只携带一个 {@code text} 字段——被插入或被删除的文本(已按文档顺序拼接)。是插入还是删除,由外层 {@link TrackedChange#type()
 * type}({@link TrackedChangeType#INS INS} / {@link TrackedChangeType#DEL DEL})区分, details
 * 本身不重复存方向信息。
 *
 * <p><b>不可变值对象。</b> {@code equals} / {@code hashCode} 比较 {@code text}。
 */
public final class TextChangeDetails implements ChangeDetails {

  private final String text;

  /**
   * 构造文本类修订的 payload。
   *
   * @param text 被插入或被删除的文本(不能为 {@code null};无文本时传 {@code ""},例如空插入)
   * @throws IllegalArgumentException 如果 {@code text} 为 {@code null}
   */
  public TextChangeDetails(String text) {
    this.text = Objects.requireNonNull(text, "text");
  }

  /**
   * 返回被插入或被删除的文本(已按文档顺序拼接)。是插入还是删除,看外层 {@link TrackedChange#type()}。
   *
   * @return 文本(从不为 {@code null},可能为空)
   */
  public String text() {
    return text;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TextChangeDetails)) {
      return false;
    }
    return java.util.Objects.equals(text, ((TextChangeDetails) o).text);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hashCode(text);
  }

  @Override
  public String toString() {
    return "text=\"" + text + "\"";
  }
}
