package com.non.docx.core.api.style;

/**
 * The kind of list a paragraph belongs to.
 *
 * <p>This is a POI-free value object; mapping to OOXML numbering definitions happens in the {@code
 * internal} bridge layer.
 */
public enum ListKind {
  /** A bulleted (unordered) list. */
  BULLET,
  /** A numbered (ordered) list. */
  NUMBERED
}
