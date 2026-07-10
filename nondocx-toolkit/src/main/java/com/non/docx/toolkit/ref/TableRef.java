package com.non.docx.toolkit.ref;

/** 正文表格引用。第一版为 SESSION 稳定。 */
public final class TableRef extends AbstractElementRef {

  public TableRef(DocumentRef documentRef, RefStability stability, String elementId) {
    super(documentRef, stability, elementId);
  }

  public static TableRef session(DocumentRef documentRef, String opaqueId) {
    return new TableRef(documentRef, RefStability.SESSION, opaqueId);
  }

  @Override
  public ElementKind kind() {
    return ElementKind.TABLE;
  }
}
