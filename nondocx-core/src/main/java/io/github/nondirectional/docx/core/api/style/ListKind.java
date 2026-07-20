package io.github.nondirectional.docx.core.api.style;

/**
 * 段落所属的列表类型。
 *
 * <p>这是一个无 POI 依赖的值对象；与 OOXML 编号定义的映射发生在 {@code internal} 桥接层。
 */
public enum ListKind {
  /** 项目符号（无序）列表。 */
  BULLET,
  /** 编号（有序）列表。 */
  NUMBERED
}
