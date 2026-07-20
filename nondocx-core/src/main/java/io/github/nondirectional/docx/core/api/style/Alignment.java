package io.github.nondirectional.docx.core.api.style;

/**
 * 段落级别的水平对齐方式。
 *
 * <p>这是一个无 POI 依赖的值对象；与 Apache POI 的 {@code ParagraphAlignment} 的映射发生在 {@code internal} 桥接层。
 */
public enum Alignment {
  /** 左对齐文本（大多数正文的默认对齐方式）。 */
  LEFT,
  /** 居中对齐文本。 */
  CENTER,
  /** 右对齐文本。 */
  RIGHT,
  /** 两端对齐文本（左右边缘对齐）。 */
  JUSTIFY
}
