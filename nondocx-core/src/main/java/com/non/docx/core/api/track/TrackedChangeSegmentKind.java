package com.non.docx.core.api.track;

/**
 * 修订位置 path 中一个 segment 的<b>结构段种类</b>。
 *
 * <p>每条修订的 {@link TrackedChangeLocation location} 是一条有序 path,由若干 segment 组成;每个 segment 用 {@code (本
 * kind, 该层级的 0-based index)} 表达「它在哪一层、是同层的第几个」。本枚举固定了第一版支持的 六类结构段——正好覆盖 read MVP 需要的正文顺序、表格层级与 run
 * 级定位。
 *
 * <p><b>OOXML 对应。</b> 这些 kind 直接对应 OOXML / POI 的嵌套结构:
 *
 * <ul>
 *   <li>{@link #BODY} —— 文档正文({@code <w:body>})。每条 location 的 path 都以此开头。
 *   <li>{@link #PARAGRAPH} —— 段落({@code <w:p>})。
 *   <li>{@link #TABLE} —— 表格({@code <w:tbl>})。
 *   <li>{@link #ROW} —— 表格行({@code <w:tr>})。只出现在 {@link #TABLE} 之下。
 *   <li>{@link #CELL} —— 表格单元格({@code <w:tc>})。只出现在 {@link #ROW} 之下。
 *   <li>{@link #RUN} —— 文本片段({@code <w:r>})。修订标记({@code <w:ins>} 等)可能直接挂在段落或 run 层级。
 * </ul>
 *
 * <p><b>segment 只负责结构位置。</b> 属性类修订的「属性目标」(如 {@code rPr} / {@code pPr} / {@code sectPr})
 * 不进入本枚举——那种语义由 {@code details()} 表达。location 只回答「这个修订挂在文档的哪一层结构上」。
 */
public enum TrackedChangeSegmentKind {
  /** 文档正文 {@code <w:body>}。每条 location 的 path 都以此开头。 */
  BODY,
  /** 段落 {@code <w:p>}。 */
  PARAGRAPH,
  /** 表格 {@code <w:tbl>}。 */
  TABLE,
  /** 表格行 {@code <w:tr>}。只出现在 {@link #TABLE} 之下。 */
  ROW,
  /** 表格单元格 {@code <w:tc>}。只出现在 {@link #ROW} 之下。 */
  CELL,
  /** 文本片段 {@code <w:r>}。 */
  RUN,
}
