package com.non.docx.toolkit.view.dto;

import java.util.List;
import java.util.Objects;

/**
 * annotated 视图的段落级明细：ref + 索引 + 文本 + run 级格式（按需展开）。
 *
 * @param ref canonical ParagraphRef 字符串
 * @param index 段落投影索引（跳过表格）
 * @param bodyIndex body 顺序索引（含表格交错序列）
 * @param text 段落全文（截断）
 * @param runs run 级明细；{@code expandRuns=false} 时为空列表
 */
public final class AnnotatedParagraph {

  private final String ref;
  private final int index;
  private final int bodyIndex;
  private final String text;
  private final List<AnnotatedRun> runs;

  public AnnotatedParagraph(
      String ref, int index, int bodyIndex, String text, List<AnnotatedRun> runs) {
    this.ref = Objects.requireNonNull(ref, "ref 不能为空");
    this.index = index;
    this.bodyIndex = bodyIndex;
    this.text = Objects.requireNonNull(text, "text 不能为空");
    this.runs = List.copyOf(runs);
  }

  public String ref() {
    return ref;
  }

  public int index() {
    return index;
  }

  public int bodyIndex() {
    return bodyIndex;
  }

  public String text() {
    return text;
  }

  public List<AnnotatedRun> runs() {
    return runs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AnnotatedParagraph)) return false;
    AnnotatedParagraph that = (AnnotatedParagraph) o;
    return index == that.index
        && bodyIndex == that.bodyIndex
        && ref.equals(that.ref)
        && text.equals(that.text)
        && runs.equals(that.runs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ref, index, bodyIndex, text, runs);
  }
}
