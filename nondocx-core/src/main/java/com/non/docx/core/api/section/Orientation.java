package com.non.docx.core.api.section;

/**
 * Page orientation for a {@link Section}.
 *
 * <p>This is a POI-free value object: it carries no {@code org.apache.poi.*} dependency. The
 * mapping to OOXML's {@code STPageOrientation} lives in the internal POI bridge.
 */
public enum Orientation {
  /** Portrait orientation (width &lt; height). Word's default. */
  PORTRAIT,
  /** Landscape orientation (width &gt; height). */
  LANDSCAPE
}
