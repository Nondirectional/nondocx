package com.non.docx.core.api.style;

/**
 * Paragraph-level horizontal alignment.
 *
 * <p>This is a POI-free value object; mapping to Apache POI's {@code ParagraphAlignment} happens in
 * the {@code internal} bridge layer.
 */
public enum Alignment {
  /** Left-aligned text (the default for most body text). */
  LEFT,
  /** Centered text. */
  CENTER,
  /** Right-aligned text. */
  RIGHT,
  /** Fully justified text (flush left and right). */
  JUSTIFY
}
