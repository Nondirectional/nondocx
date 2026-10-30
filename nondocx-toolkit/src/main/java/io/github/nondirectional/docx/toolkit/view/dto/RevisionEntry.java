package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.Objects;

/**
 * outline 视图修订概览里的单条修订明细：ref + 类型 + 作者 + 内容片段。
 *
 * <p>复审核对「作者填了谁」「改了什么」的依据（只有计数时无法核对，2026-10-30 tracked-replace 评测里复审因看不到作者把已正确的修订全程误报「未达成」）。ref 是完整
 * canonical RevisionRef， 可直通 {@code view_element} 查详情。
 *
 * @param ref canonical RevisionRef 字符串
 * @param type 修订类型名小写（insert/delete/…）
 * @param author 修订作者
 * @param snippet 内容片段（文本类修订为改动文本截断，其他家族为家族描述）
 */
public final class RevisionEntry {

  private final String ref;
  private final String type;
  private final String author;
  private final String snippet;

  public RevisionEntry(String ref, String type, String author, String snippet) {
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
    if (!(o instanceof RevisionEntry)) return false;
    RevisionEntry that = (RevisionEntry) o;
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
