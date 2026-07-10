package com.non.docx.toolkit.ref;

/** 页眉或页脚引用。第一版为 SESSION 稳定。 */
public final class HeaderFooterRef extends AbstractElementRef {

  public HeaderFooterRef(DocumentRef documentRef, RefStability stability, String elementId) {
    super(documentRef, stability, elementId);
  }

  @Override
  public ElementKind kind() {
    return ElementKind.HEADER_FOOTER;
  }
}
