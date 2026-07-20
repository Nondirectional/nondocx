package io.github.nondirectional.docx.core.api.track;

import io.github.nondirectional.docx.core.internal.util.Objects;

/**
 * 单元格结构类修订({@code cellIns} / {@code cellDel} / {@code cellMerge})的 payload。
 *
 * <p>回答「这条修订对单元格做了什么」。单元格结构类修订的 OOXML 结构是:在单元格属性 {@code <w:tcPr>} 内嵌一个裸 属性元素(只有 {@code w:id}/{@code
 * w:author}/{@code w:date},<b>无 run、无文本</b>),标记「这个单元格本身是被 插入/删除/合并的」——表格<b>结构修订</b>,不是文本内容修订(见
 * {@code research/cell-forms.md} §2)。
 *
 * <p><b>与文本类 / 属性类 details 的差异。</b>
 *
 * <ul>
 *   <li>{@link TextChangeDetails} 携带被增删的文本(cell 类没有文本)。
 *   <li>{@link PropertyChangeDetails} 携带新旧属性摘要(cell 类没有属性子树)。
 *   <li>本类只携带一个 {@link CellChangeKind kind}——cell 修订的 payload 本就是「单元格的存亡语义」,没有更多内容可
 *       解析。这是诚实的最小建模,不为对称而硬塞字段。
 * </ul>
 *
 * <p><b>不可变值对象。</b> {@code equals} / {@code hashCode} 比较 {@code kind}。
 *
 * @see CellChangeKind
 */
public final class CellChangeDetails implements ChangeDetails {

  private final CellChangeKind kind;

  /**
   * 构造单元格结构类修订的 payload。
   *
   * @param kind 结构语义分类(不能为 {@code null})
   * @throws IllegalArgumentException 如果 {@code kind} 为 {@code null}
   */
  public CellChangeDetails(CellChangeKind kind) {
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  /** 返回结构语义分类(对单元格做了什么)。 */
  public CellChangeKind kind() {
    return kind;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CellChangeDetails)) {
      return false;
    }
    return kind == ((CellChangeDetails) o).kind;
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hashCode(kind);
  }

  @Override
  public String toString() {
    return kind.toString();
  }
}
