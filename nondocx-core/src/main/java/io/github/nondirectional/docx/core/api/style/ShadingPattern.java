package io.github.nondirectional.docx.core.api.style;

/**
 * 单元格/段落的底纹图案类型（对应 OOXML {@code <w:shd w:val="...">}）。
 *
 * <p><b>故意剔除 {@code SOLID}</b>：OOXML 的 {@code STShd.SOLID}（{@code w:val="solid"}）在 Microsoft Word
 * 里显示为指定颜色的实心填充，但在 <b>WPS</b> 里会被解释为「前景色（默认黑） 100% 覆盖」，渲染为纯黑块、盖住文字。这是两个渲染引擎对同一属性的不同解释。
 *
 * <p>nondocx 的公开底纹 API（{@link Shading} / {@code Cell.shading(...)} / {@code
 * Paragraph.shading(...)}） 只暴露本枚举列出的<b>跨引擎安全</b>图案；用户若确实需要 SOLID 语义，请走 {@code raw()} 直接操纵 {@code
 * CTShd}，并自行承担 WPS 渲染风险。
 *
 * <p>详见 {@code .trellis/spec/backend/renderer-compatibility.md} 的 {@code #shading-solid} 规则。
 *
 * <p>这是一个无 POI 依赖的值对象；与 Apache POI 的 {@code STShd.Enum} 的映射发生在 {@code internal} 桥接层。
 */
public enum ShadingPattern {
  /**
   * 清除图案（纯背景色填充）——最常用的「给单元格/段落上一个纯色底」的形态。
   *
   * <p>OOXML {@code w:val="clear"}：用 {@code w:fill} 指定的颜色填充背景，无图案叠加。Word 与 WPS
   * 行为一致，是跨引擎安全的默认选择。{@code Cell.shading("F1F5F9")} 等便捷方法默认使用此图案。
   */
  CLEAR,
  /** 无底纹（清除现有底纹）。OOXML {@code w:val="nil"}。读取时若文档里出现未在本枚举建模的图案， 也归并为此值以确保文档永不加载失败。 */
  NIL
}
