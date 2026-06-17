package com.non.docx.core.api.section;

/**
 * {@link Section} 的页面方向。
 *
 * <p>这是一个无 POI 依赖的值对象：它不携带任何 {@code org.apache.poi.*} 依赖。与 OOXML 的 {@code STPageOrientation}
 * 的映射位于内部 POI 桥接层。
 */
public enum Orientation {
  /** 纵向方向（宽度 &lt; 高度）。Word 的默认值。 */
  PORTRAIT,
  /** 横向方向（宽度 &gt; 高度）。 */
  LANDSCAPE
}
