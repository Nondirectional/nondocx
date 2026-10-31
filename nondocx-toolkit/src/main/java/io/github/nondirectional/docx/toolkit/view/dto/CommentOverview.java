package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 大纲视图中的批注概览：总数、按作者分组计数和最近明细。
 *
 * <p>与 {@link RevisionOverview} 同型——复审核对「批注加了没有、作者填了谁」的依据；批注创作（{@code
 * comment.add}）落地后只读视图若看不到批注，复审会把已正确的文档误报「未达成」（与修订作者盲区同一家族）。
 *
 * @param total 批注总数（文档级，含锚定在表格单元格内段落的批注）
 * @param countByAuthor 按作者分组计数
 * @param recent 最近若干条批注明细（锚定段落 ref/作者/片段，ref 可直通 view_element 查详情）
 */
public final class CommentOverview {

  private final int total;
  private final Map<String, Integer> countByAuthor;
  private final List<CommentEntry> recent;

  public CommentOverview(int total, Map<String, Integer> countByAuthor, List<CommentEntry> recent) {
    this.total = total;
    this.countByAuthor = Map.copyOf(countByAuthor);
    this.recent = List.copyOf(recent);
  }

  public int total() {
    return total;
  }

  public Map<String, Integer> countByAuthor() {
    return countByAuthor;
  }

  public List<CommentEntry> recent() {
    return recent;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CommentOverview)) return false;
    CommentOverview that = (CommentOverview) o;
    return total == that.total
        && countByAuthor.equals(that.countByAuthor)
        && recent.equals(that.recent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(total, countByAuthor, recent);
  }
}
