package io.github.nondirectional.docx.core.api.header;

/**
 * 页眉 / 页脚的变体类型。
 *
 * <p>OOXML 里一个章节可以有三套页眉页脚：默认（奇数页）、首页、偶数页。三者独立存在，由 OOXML 的 {@code STHdrFtr} 区分（POI 暴露为 {@code
 * XWPFHeaderFooterPolicy.DEFAULT/FIRST/EVEN} 常量）。
 *
 * <p>这是一个无 POI 依赖的值对象；与 POI 的映射发生在 {@code internal} 桥接层（{@code Mappers.toPoi}）。
 *
 * <p><b>开关依赖（重要）。</b>
 *
 * <ul>
 *   <li>{@link #FIRST} 需要 {@code <w:sectPr>} 的 {@code <w:titlePg/>} 才生效。POI 的 {@code
 *       createHeader(FIRST)} <b>不</b>自动写这个开关 —— nondocx 的 {@code ensureHeader(FIRST)} / {@code
 *       ensureFooter(FIRST)} 会显式补齐。
 *   <li>{@link #EVEN} 需要 {@code word/settings.xml} 的 {@code <w:evenAndOddHeaders/>} 才生效。POI 的
 *       {@code createHeader(EVEN)} <b>不</b>自动写这个开关 —— nondocx 的 {@code ensureHeader(EVEN)} / {@code
 *       ensureFooter(EVEN)} 会显式补齐。
 *   <li>{@link #DEFAULT} 无开关依赖。
 * </ul>
 *
 * <p><b>层级差异。</b> {@code titlePg} 是 <em>per-section</em>（住在章节的 {@code <w:sectPr>}，每个章节
 * 独立决定首页是否不同）；{@code evenAndOddHeaders} 是 <em>文档级</em>（住在 {@code settings.xml}， 全文档一个开关）。nondocx 遵循
 * OOXML 的这个层级差异。
 *
 * <p><b>WPS 兼容性。</b> {@link #FIRST} 依赖的 {@code titlePg} 在 WPS 的首页抑制不可靠（首页仍显示头尾， 或正文首页被误抑制），详见
 * {@code renderer-compatibility.md#title-page-suppress}。功能照常提供， 但跨引擎场景需注意该限制。
 */
public enum HeaderFooterVariant {
  /** 默认（奇数页）页眉 / 页脚。无开关依赖。这也是无参 {@code header()} / {@code ensureHeader()} 的默认值。 */
  DEFAULT,
  /**
   * 首页页眉 / 页脚。需要 {@code <w:sectPr>} 的 {@code <w:titlePg/>}（nondocx 自动补）；WPS 兼容性见 {@code
   * renderer-compatibility.md#title-page-suppress}。
   */
  FIRST,
  /** 偶数页页眉 / 页脚。需要 {@code settings.xml} 的 {@code <w:evenAndOddHeaders/>}（nondocx 自动补）。 */
  EVEN
}
