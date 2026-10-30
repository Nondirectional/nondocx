package io.github.nondirectional.docx.toolkit.view.snapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 修订摘要：修订开关是否开启、修订数量、按类型/家族与作者分组的计数，以及最近的若干条明细。
 *
 * <p>明细（{@link Entry}）给只读复审用：只看计数无法核对「作者是否正确」「改了什么」—— 2026-10-30 的 tracked-replace
 * 评测里复审因看不到作者而把已正确的修订全程误报成「未达成」。 只带最近若干条（构造方裁剪），全文明细仍走修订查询工具。
 */
public final class RevisionSummary {

  private final boolean trackingEnabled;
  private final int totalCount;

  /** 按修订家族/类型分组的计数，如 {@code {"insertion": 3, "deletion": 1}}。 */
  private final Map<String, Integer> countByType;

  /** 按作者分组的计数，如 {@code {"审查员": 2}}——复审核对「作者填了谁」的依据。 */
  private final Map<String, Integer> countByAuthor;

  /** 最近的修订明细（最多若干条，按 list 顺序）。 */
  private final List<Entry> recent;

  /**
   * 一条修订的紧凑明细。
   *
   * @param ref canonical RevisionRef（可回查 view_element 详情）
   * @param type 修订类型名小写（insert/delete/…）
   * @param author 修订作者
   * @param snippet 文本类修订的内容片段；其他家族为家族描述
   */
  public static final class Entry {

    private final String ref;
    private final String type;
    private final String author;
    private final String snippet;

    public Entry(String ref, String type, String author, String snippet) {
      this.ref = Objects.requireNonNull(ref, "ref 不能为空");
      this.type = Objects.requireNonNull(type, "type 不能为空");
      this.author = Objects.requireNonNull(author, "author 不能为空");
      this.snippet = Objects.requireNonNull(snippet, "snippet 不能为空");
    }

    public String ref() {
      return ref;
    }

    public String type() {
      return type;
    }

    public String author() {
      return author;
    }

    public String snippet() {
      return snippet;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Entry)) return false;
      Entry that = (Entry) o;
      return ref.equals(that.ref)
          && type.equals(that.type)
          && author.equals(that.author)
          && snippet.equals(that.snippet);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ref, type, author, snippet);
    }
  }

  public RevisionSummary(
      boolean trackingEnabled,
      int totalCount,
      Map<String, Integer> countByType,
      Map<String, Integer> countByAuthor,
      List<Entry> recent) {
    this.trackingEnabled = trackingEnabled;
    this.totalCount = totalCount;
    this.countByType = Map.copyOf(countByType);
    this.countByAuthor = Map.copyOf(countByAuthor);
    this.recent = List.copyOf(recent);
  }

  public boolean trackingEnabled() {
    return trackingEnabled;
  }

  public int totalCount() {
    return totalCount;
  }

  public Map<String, Integer> countByType() {
    return countByType;
  }

  public Map<String, Integer> countByAuthor() {
    return countByAuthor;
  }

  public List<Entry> recent() {
    return recent;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RevisionSummary)) return false;
    RevisionSummary that = (RevisionSummary) o;
    return trackingEnabled == that.trackingEnabled
        && totalCount == that.totalCount
        && countByType.equals(that.countByType)
        && countByAuthor.equals(that.countByAuthor)
        && recent.equals(that.recent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trackingEnabled, totalCount, countByType, countByAuthor, recent);
  }
}
