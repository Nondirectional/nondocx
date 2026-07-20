package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.List;
import java.util.Objects;

/** annotated 视图：段落级文本 + 按需展开的 run 直接格式。 */
public final class AnnotatedView {

  private final ViewMeta meta;
  private final List<AnnotatedParagraph> paragraphs;

  public AnnotatedView(ViewMeta meta, List<AnnotatedParagraph> paragraphs) {
    this.meta = Objects.requireNonNull(meta, "meta 不能为空");
    this.paragraphs = List.copyOf(paragraphs);
  }

  public ViewMeta meta() {
    return meta;
  }

  public List<AnnotatedParagraph> paragraphs() {
    return paragraphs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AnnotatedView)) return false;
    AnnotatedView that = (AnnotatedView) o;
    return meta.equals(that.meta) && paragraphs.equals(that.paragraphs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(meta, paragraphs);
  }
}
