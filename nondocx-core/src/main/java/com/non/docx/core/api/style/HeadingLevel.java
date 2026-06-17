package com.non.docx.core.api.style;

/**
 * 标题级别 H1 到 H6，映射到 Word 的内置标题样式。
 *
 * <p>这是一个无 POI 依赖的值对象；与 Apache POI / OOXML 标题样式 ID 的映射发生在 {@code internal} 桥接层。
 */
public enum HeadingLevel {
  /** 一级标题（Word 样式 {@code Heading1}）。 */
  H1,
  /** 二级标题（Word 样式 {@code Heading2}）。 */
  H2,
  /** 三级标题（Word 样式 {@code Heading3}）。 */
  H3,
  /** 四级标题（Word 样式 {@code Heading4}）。 */
  H4,
  /** 五级标题（Word 样式 {@code Heading5}）。 */
  H5,
  /** 六级标题（Word 样式 {@code Heading6}）。 */
  H6
}
