package io.github.nondirectional.docx.core.api.track;

/**
 * 单元格结构类修订的<b>结构语义分类</b>——回答「这条 cell 修订对单元格做了什么」。
 *
 * <p>由 {@link CellChangeDetails#kind()} 返回。与细粒度 {@link TrackedChangeType type} 对应:
 *
 * <ul>
 *   <li>{@link #CELL_INSERTION} —— 单元格插入,对应 {@code cellIns}({@link TrackedChangeType#CELL_INS})。
 *   <li>{@link #CELL_DELETION} —— 单元格删除,对应 {@code cellDel}({@link TrackedChangeType#CELL_DEL})。
 *   <li>{@link #UNCONFIRMED_MERGE} —— 单元格合并/拆分,对应 {@code cellMerge}({@link
 *       TrackedChangeType#CELL_MERGE})。<b>仅读</b>:cellMerge 的 CT 类型({@code CTCellMergeTrackChange})
 *       在 POI 精简 schema 下缺失(编译期不可达),故只读出「存在一次未确认的合并」这一事实,不解析合并细节; accept/reject 对它抛 {@code
 *       UnsupportedFeatureException}。
 * </ul>
 */
public enum CellChangeKind {
  /** 单元格插入(cellIns):标记一个单元格是被插入的。 */
  CELL_INSERTION,
  /** 单元格删除(cellDel):标记一个单元格是被删除的。 */
  CELL_DELETION,
  /** 未确认的合并(cellMerge):标记两个单元格的合并/拆分。仅读——CT 类型缺失,合并细节不可解析,accept/reject 不支持。 */
  UNCONFIRMED_MERGE,
}
