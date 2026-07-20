package io.github.nondirectional.docx.toolkit.ref;

/**
 * 尚无具体活元素可绑定时的规范化操作目标引用。
 *
 * <p>用于插入位置、文档级质量检查等协议目标。它仍是强类型 {@link ElementRef}，避免 {@code ConflictKey} 保存自由字符串。
 */
public final class OperationTargetRef extends AbstractElementRef {

  public OperationTargetRef(DocumentRef documentRef, RefStability stability, String elementId) {
    super(documentRef, stability, elementId);
  }

  public static OperationTargetRef compatibility(String target) {
    return new OperationTargetRef(
        new DocumentRef("legacy-operation-target", 1L), RefStability.SESSION, target);
  }

  @Override
  public ElementKind kind() {
    return ElementKind.OPERATION_TARGET;
  }
}
