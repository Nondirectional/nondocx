package io.github.nondirectional.docx.toolkit.ref;

/** 表格单元格引用。第一版为 SESSION 稳定。 */
public final class CellRef extends AbstractElementRef {

  public CellRef(DocumentRef documentRef, RefStability stability, String elementId) {
    super(documentRef, stability, elementId);
  }

  @Override
  public ElementKind kind() {
    return ElementKind.CELL;
  }
}
