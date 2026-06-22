package com.non.docx.core.api.style;

/**
 * 单元格内容的垂直对齐方式（对应 OOXML {@code <w:vAlign>}）。
 *
 * <p><b>WPS/Word 兼容性提示</b>：当单元格设了<b>固定（exact）行高</b>时，{@link #CENTER} 与 {@link #BOTTOM} 在 WPS
 * 里的表现可能与 Microsoft Word 不一致（部分 WPS 版本在 exact 行高下忽略 vAlign）。跨引擎要求严格的场景 建议使用 {@link #TOP}（也是 OOXML
 * 的默认行为）。详见 {@code .trellis/spec/backend/renderer-compatibility.md} 的 {@code #exact-row-valign} 规则。
 *
 * <p>这是一个无 POI 依赖的值对象；与 Apache POI 的 {@code STVerticalJc} / {@code XWPFVertAlign} 的映射发生在 {@code
 * internal} 桥接层。
 */
public enum VerticalAlign {
  /** 顶部对齐（OOXML 默认行为）。跨引擎最安全的取值。 */
  TOP,
  /** 垂直居中。在 exact 行高下 WPS 可能不生效。 */
  CENTER,
  /** 底部对齐。在 exact 行高下 WPS 可能不生效。 */
  BOTTOM
}
