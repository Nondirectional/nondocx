package com.non.docx.toolkit.ref;

import java.util.Objects;

abstract class AbstractElementRef implements ElementRef {

  private final DocumentRef documentRef;
  private final RefStability stability;
  private final String elementId;

  AbstractElementRef(DocumentRef documentRef, RefStability stability, String elementId) {
    this.documentRef = Objects.requireNonNull(documentRef, "documentRef 不能为空");
    this.stability = Objects.requireNonNull(stability, "stability 不能为空");
    if (elementId == null || elementId.isBlank()) {
      throw new IllegalArgumentException("elementId 不能为空");
    }
    this.elementId = elementId;
  }

  @Override
  public final DocumentRef documentRef() {
    return documentRef;
  }

  @Override
  public final RefStability stability() {
    return stability;
  }

  @Override
  public final String elementId() {
    return elementId;
  }

  @Override
  public final String canonical() {
    return ElementRefs.format(this);
  }

  @Override
  public final boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AbstractElementRef that = (AbstractElementRef) o;
    return documentRef.equals(that.documentRef)
        && stability == that.stability
        && elementId.equals(that.elementId);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(getClass(), documentRef, stability, elementId);
  }

  @Override
  public final String toString() {
    return canonical();
  }
}
