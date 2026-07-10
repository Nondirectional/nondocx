package com.non.docx.toolkit.ref;

/** 段落引用。已有 {@code w14:paraId} 时可为 PERSISTENT，否则为 SESSION。 */
public final class ParagraphRef extends AbstractElementRef {

  public ParagraphRef(DocumentRef documentRef, RefStability stability, String elementId) {
    super(documentRef, stability, elementId);
  }

  public static ParagraphRef session(DocumentRef documentRef, String opaqueId) {
    return new ParagraphRef(documentRef, RefStability.SESSION, opaqueId);
  }

  public static ParagraphRef persistent(DocumentRef documentRef, String paraId) {
    return new ParagraphRef(documentRef, RefStability.PERSISTENT, paraId);
  }

  @Override
  public ElementKind kind() {
    return ElementKind.PARAGRAPH;
  }
}
