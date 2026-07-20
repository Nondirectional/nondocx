package io.github.nondirectional.docx.toolkit.ref;

/** 段落内 run 引用。第一版为 SESSION 稳定。 */
public final class RunRef extends AbstractElementRef {

  public RunRef(DocumentRef documentRef, RefStability stability, String elementId) {
    super(documentRef, stability, elementId);
  }

  @Override
  public ElementKind kind() {
    return ElementKind.RUN;
  }
}
