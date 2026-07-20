package io.github.nondirectional.docx.toolkit.ref;

/** 修订引用。第一版复用修订门面的进程内稳定 id。 */
public final class RevisionRef extends AbstractElementRef {

  public RevisionRef(DocumentRef documentRef, RefStability stability, String elementId) {
    super(documentRef, stability, elementId);
  }

  @Override
  public ElementKind kind() {
    return ElementKind.REVISION;
  }
}
