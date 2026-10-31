package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.Objects;

/**
 * outline 视图批注概览里的单条批注明细：锚定段落 ref + 作者 + 内容片段。
 *
 * <p>复审核对「作者填了谁」「评了什么」「挂在哪段」的依据（与 {@link RevisionEntry} 同型）。ref 是完整 canonical ParagraphRef，可直通
 * {@code view_element} 查该段详情（含整条批注全文）。
 *
 * @param paragraph 锚定段落的 canonical ParagraphRef；批注锚在表格单元格内段落或孤儿批注时为 {@code null}
 * @param author 批注作者
 * @param snippet 批注正文片段（截断）
 */
public final class CommentEntry {

  private final String paragraph;
  private final String author;
  private final String snippet;

  public CommentEntry(String paragraph, String author, String snippet) {
    this.paragraph = paragraph;
    this.author = Objects.requireNonNull(author, "author 不能为空");
    this.snippet = Objects.requireNonNull(snippet, "snippet 不能为空");
  }

  public String paragraph() {
    return paragraph;
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
    if (!(o instanceof CommentEntry)) return false;
    CommentEntry that = (CommentEntry) o;
    return Objects.equals(paragraph, that.paragraph)
        && author.equals(that.author)
        && snippet.equals(that.snippet);
  }

  @Override
  public int hashCode() {
    return Objects.hash(paragraph, author, snippet);
  }
}
