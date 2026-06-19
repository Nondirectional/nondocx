package com.non.docx.core.api.track;

/**
 * 修订(tracked change)的<b>细粒度 kind</b>——与 OOXML 的具体修订元素一一对应。
 *
 * <p>每个 {@link TrackedChange} 携带一个本细粒度 type 与一个粗粒度 {@link TrackedChangeFamily family}。 type
 * 忠实表达「这条修订在 OOXML 里是哪一种元素」,是理解与调试的主要信息来源之一。
 *
 * <p><b>type → family 映射</b>(family 是 type 的超集分组):
 *
 * <ul>
 *   <li>{@link #INS} / {@link #DEL} → {@link TrackedChangeFamily#TEXT TEXT}
 *   <li>{@link #MOVE_FROM} / {@link #MOVE_TO} → {@link TrackedChangeFamily#MOVE MOVE}
 *   <li>{@link #RPR_CHANGE} / {@link #PPR_CHANGE} / {@link #SECT_PR_CHANGE} → {@link
 *       TrackedChangeFamily#PROPERTY PROPERTY}
 *   <li>{@link #CELL_INS} / {@link #CELL_DEL} / {@link #CELL_MERGE} → {@link
 *       TrackedChangeFamily#CELL CELL}
 * </ul>
 *
 * <p><b>OOXML 对应(已按精简 schema 验证)。</b> 文本与移动类四种元素在 POI 5.2.5 精简 schema 下统一由 {@code
 * CTRunTrackChange}({@code
 * org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange}) 承载——它们共享同一 Java 类型,区别只在
 * OOXML 元素本地名({@code ins} / {@code del} / {@code moveFrom} / {@code
 * moveTo})。本枚举的细粒度正是捕获这个本地名差异。属性类与单元格类修订由其它 CT 类型承载,本枚举为其 预留了 kind,完整建模由 {@code advanced-types}
 * 子任务补齐。
 *
 * <p><b>当前覆盖范围。</b> read 子任务稳定支持 {@link #INS} / {@link #DEL}(以及 {@link #MOVE_FROM} / {@link
 * #MOVE_TO}——它们与 ins/del 同型,解析路径天然覆盖);属性类与单元格类 kind 在 {@code list()} 中可能暂不出现。
 */
public enum TrackedChangeType {
  /** 插入文本:对应 OOXML {@code <w:ins>}。 */
  INS(TrackedChangeFamily.TEXT),
  /** 删除文本:对应 OOXML {@code <w:del>}。 */
  DEL(TrackedChangeFamily.TEXT),
  /** 移动的来源(被移走的位置):对应 OOXML {@code <w:moveFrom>}。 */
  MOVE_FROM(TrackedChangeFamily.MOVE),
  /** 移动的目标(移到的位置):对应 OOXML {@code <w:moveTo>}。 */
  MOVE_TO(TrackedChangeFamily.MOVE),
  /** 运行属性变更:对应 OOXML {@code rPrChange}。 */
  RPR_CHANGE(TrackedChangeFamily.PROPERTY),
  /** 段落属性变更:对应 OOXML {@code pPrChange}。 */
  PPR_CHANGE(TrackedChangeFamily.PROPERTY),
  /** 节属性变更:对应 OOXML {@code sectPrChange}。 */
  SECT_PR_CHANGE(TrackedChangeFamily.PROPERTY),
  /** 表格单元格插入:对应 OOXML {@code <w:cellIns>}。 */
  CELL_INS(TrackedChangeFamily.CELL),
  /** 表格单元格删除:对应 OOXML {@code <w:cellDel>}。 */
  CELL_DEL(TrackedChangeFamily.CELL),
  /** 表格单元格合并/拆分:对应 OOXML {@code <w:cellMerge>}。仅读(CT 类型缺失),accept/reject 不支持。 */
  CELL_MERGE(TrackedChangeFamily.CELL);

  private final TrackedChangeFamily family;

  TrackedChangeType(TrackedChangeFamily family) {
    this.family = family;
  }

  /**
   * 返回本 type 所属的粗粒度 family。
   *
   * @return 所属 family(从不为 {@code null})
   */
  public TrackedChangeFamily family() {
    return family;
  }
}
