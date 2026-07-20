package io.github.nondirectional.docx.core.api.track;

/**
 * 属性类修订的<b>目标属性树类型</b>——回答「这条属性变更改的是哪类属性」。
 *
 * <p>由 {@link PropertyChangeDetails#kind()} 返回。与细粒度 {@link TrackedChangeType type} 对应:
 *
 * <ul>
 *   <li>{@link #RUN_PROPERTIES} —— 运行属性,对应 {@code rPrChange}({@link TrackedChangeType#RPR_CHANGE})。
 *   <li>{@link #PARAGRAPH_PROPERTIES} —— 段落属性,对应 {@code pPrChange}({@link
 *       TrackedChangeType#PPR_CHANGE})。
 * </ul>
 *
 * <p>节/表格/行/单元格等更高层属性类({@code sectPrChange} 等)的建模留给后续子任务。
 */
public enum PropertyChangeKind {
  /** 运行属性(rPr)变更。 */
  RUN_PROPERTIES,
  /** 段落属性(pPr)变更。 */
  PARAGRAPH_PROPERTIES,
}
