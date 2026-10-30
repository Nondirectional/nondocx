package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.Objects;

/**
 * outline 视图修订概览里的单条修订明细：ref + 类型 + 作者 + 内容片段 + 字符格式证据。
 *
 * <p>复审核对「作者填了谁」「改了什么」的依据（只有计数时无法核对，2026-10-30 tracked-replace 评测里复审因看不到作者把已正确的修订全程误报「未达成」）。ref 是完整
 * canonical RevisionRef， 可直通 {@code view_element} 查详情。bold/italic 是带格式修订的证据—— 修订内 run 不被 {@code
 * paragraph.runs()} 暴露（N14），run 级视图够不着，复审核对「以修订加粗」只能靠这两个字段（恒出现，false 也是证据）。
 *
 * @param ref canonical RevisionRef 字符串
 * @param type 修订类型名小写（insert/delete/…）
 * @param author 修订作者
 * @param snippet 内容片段（文本类修订为改动文本截断，其他家族为家族描述）
 * @param bold 修订内任一 run 带粗体
 * @param italic 修订内任一 run 带斜体
 */
public final class RevisionEntry {

  private final String ref;
  private final String type;
  private final String author;
  private final String snippet;
  private final boolean bold;
  private final boolean italic;

  public RevisionEntry(
      String ref, String type, String author, String snippet, boolean bold, boolean italic) {
    this.ref = Objects.requireNonNull(ref, "ref 不能为空");
    this.type = Objects.requireNonNull(type, "type 不能为空");
    this.author = Objects.requireNonNull(author, "author 不能为空");
    this.snippet = Objects.requireNonNull(snippet, "snippet 不能为空");
    this.bold = bold;
    this.italic = italic;
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

  public boolean bold() {
    return bold;
  }

  public boolean italic() {
    return italic;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RevisionEntry)) return false;
    RevisionEntry that = (RevisionEntry) o;
    return ref.equals(that.ref)
        && type.equals(that.type)
        && author.equals(that.author)
        && snippet.equals(that.snippet)
        && bold == that.bold
        && italic == that.italic;
  }

  @Override
  public int hashCode() {
    return Objects.hash(ref, type, author, snippet, bold, italic);
  }
}
