package io.github.nondirectional.docx.core.api.track;

/**
 * 修订(tracked change)的<b>粗粒度分组</b>。
 *
 * <p>每个 {@link TrackedChange} 同时携带一个细粒度 {@link TrackedChangeType type} 与一个本粗粒度 family。 family
 * 用于「按类别概览」这类不需要细到具体 OOXML kind 的场景:例如 UI 里把所有「移动」类修订归到一起, 或在日志里用一个词概括某条修订。
 *
 * <p><b>与 type 的关系。</b> 一个 family 对应若干 type;family 是 type 的超集分组,不是替代。详见 {@link TrackedChangeType}
 * 的映射表。
 *
 * <p><b>OOXML 对应。</b> OOXML 没有直接表达「family」的概念,family 是 nondocx 在多个具体 OOXML 修订元素之上 自行归类的分组:
 *
 * <ul>
 *   <li>{@link #TEXT} —— 文本类插入/删除,对应 {@code <w:ins>} / {@code <w:del>}。
 *   <li>{@link #MOVE} —— 移动类,对应 {@code <w:moveFrom>} / {@code <w:moveTo>}。
 *   <li>{@link #PROPERTY} —— 属性类修订(运行/段落/节属性变更),对应 {@code rPrChange} 等各类 {@code *PrChange}。
 *   <li>{@link #CELL} —— 表格单元格级插入/删除/合并,对应 {@code <w:cellIns>} / {@code <w:cellDel>} / {@code
 *       <w:cellMerge>}。
 * </ul>
 *
 * <p>当前 read 子任务稳定覆盖 {@link #TEXT}(其余 family 的完整建模留给 {@code advanced-types} 子任务)。
 */
public enum TrackedChangeFamily {
  /** 文本类修订:插入/删除文本。对应 OOXML {@code <w:ins>} / {@code <w:del>}。 */
  TEXT,
  /** 移动类修订:文本从一处移到另一处。对应 OOXML {@code <w:moveFrom>} / {@code <w:moveTo>}。 */
  MOVE,
  /** 属性类修订:运行/段落/节等属性变更。对应 OOXML 各类 {@code *PrChange}。 */
  PROPERTY,
  /**
   * 表格单元格级修订:单元格插入/删除/合并。对应 OOXML {@code <w:cellIns>} / {@code <w:cellDel>} / {@code
   * <w:cellMerge>}。
   */
  CELL,
}
