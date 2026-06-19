package com.non.docx.core.api.track;

import com.non.docx.core.internal.util.Objects;
import java.util.List;

/**
 * 属性类修订({@code rPrChange} / {@code pPrChange} 等)的 payload。
 *
 * <p>回答「这条修订改了哪个属性树、改成什么样」。属性类修订的 OOXML 结构是:外层属性元素(如 {@code <w:rPr>})表达 <b>当前(新)</b>属性,其内的 {@code
 * *PrChange} 元素又内嵌一个<b>旧(pristine)</b>属性元素——accept 即保留新树、删 {@code *PrChange}; reject 即用旧树覆盖外层、删
 * {@code *PrChange}(见 research/ooxml-forms.md §1.2)。
 *
 * <p><b>属性树类型</b>({@link #kind()})回答「改了哪类属性」,目前稳定覆盖:
 *
 * <ul>
 *   <li>{@link PropertyChangeKind#RUN_PROPERTIES RUN_PROPERTIES} —— 运行属性({@code rPrChange})。
 *   <li>{@link PropertyChangeKind#PARAGRAPH_PROPERTIES PARAGRAPH_PROPERTIES} —— 段落属性({@code
 *       pPrChange})。
 * </ul>
 *
 * <p><b>变更摘要</b>({@link #newSummary()} / {@link #oldSummary()}):对新旧属性树做一份<b>紧凑文本摘要</b>(该属性树直接子元素
 * 的本地名集合,如 {@code "[b]"} 表示粗体)。这是给人看的概览,不是可解析的属性契约——要精确属性请走 {@code raw()}。
 *
 * <p><b>不可变值对象。</b> {@code equals} / {@code hashCode} 比较 {@code kind} + {@code newSummary} + {@code
 * oldSummary}。
 *
 * @see PropertyChangeKind
 */
public final class PropertyChangeDetails implements ChangeDetails {

  private final PropertyChangeKind kind;
  private final String newSummary;
  private final String oldSummary;

  /**
   * 构造属性类修订的 payload。
   *
   * @param kind 属性树类型(不能为 {@code null})
   * @param newSummary 新(当前)属性树的紧凑摘要(不能为 {@code null};无属性时传 {@code ""})
   * @param oldSummary 旧(pristine)属性树的紧凑摘要(不能为 {@code null};无属性时传 {@code ""})
   * @throws IllegalArgumentException 如果任一参数为 {@code null}
   */
  public PropertyChangeDetails(PropertyChangeKind kind, String newSummary, String oldSummary) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.newSummary = Objects.requireNonNull(newSummary, "newSummary");
    this.oldSummary = Objects.requireNonNull(oldSummary, "oldSummary");
  }

  /** 返回属性树类型(改了哪类属性)。 */
  public PropertyChangeKind kind() {
    return kind;
  }

  /** 返回新(当前)属性树的紧凑摘要。 */
  public String newSummary() {
    return newSummary;
  }

  /** 返回旧(pristine)属性树的紧凑摘要。 */
  public String oldSummary() {
    return oldSummary;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PropertyChangeDetails)) {
      return false;
    }
    PropertyChangeDetails that = (PropertyChangeDetails) o;
    return kind == that.kind
        && java.util.Objects.equals(newSummary, that.newSummary)
        && java.util.Objects.equals(oldSummary, that.oldSummary);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(kind, newSummary, oldSummary);
  }

  @Override
  public String toString() {
    return kind + ": new=" + newSummary + ", old=" + oldSummary;
  }

  /** 把若干本地名拼成紧凑摘要,如 {@code "[b,i]"};空列表返回 {@code "[]"}。 */
  public static String summarize(List<String> childLocalNames) {
    java.util.Collections.sort(childLocalNames);
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < childLocalNames.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(childLocalNames.get(i));
    }
    return sb.append("]").toString();
  }
}
