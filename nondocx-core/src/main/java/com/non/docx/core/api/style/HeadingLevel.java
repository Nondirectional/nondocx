package com.non.docx.core.api.style;

/**
 * Heading levels H1 through H6, mapping to Word's built-in heading styles.
 *
 * <p>This is a POI-free value object; mapping to Apache POI / OOXML heading style ids happens in
 * the {@code internal} bridge layer.
 */
public enum HeadingLevel {
  /** Level 1 heading (Word style {@code Heading1}). */
  H1,
  /** Level 2 heading (Word style {@code Heading2}). */
  H2,
  /** Level 3 heading (Word style {@code Heading3}). */
  H3,
  /** Level 4 heading (Word style {@code Heading4}). */
  H4,
  /** Level 5 heading (Word style {@code Heading5}). */
  H5,
  /** Level 6 heading (Word style {@code Heading6}). */
  H6
}
