package com.non.docx.toolkit.orchestration;

import com.non.docx.toolkit.ref.ElementRef;
import com.non.docx.toolkit.ref.OperationTargetRef;
import java.util.Objects;

/**
 * 粗粒度冲突标识：用于第一层冲突检测——锁定「同一目标」。
 *
 * <p><b>OOXML 三层递进（冲突目标）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 里每个可编辑目标有自己的定位坐标——段落靠在 {@code <w:body>} 里的顺序索引、 run 靠段落内顺序、单元格靠 {@code
 *       (table, row, cell)} 三元组、修订靠 {@code id}。
 *   <li><b>POI</b>：{@code XWPFParagraph}/{@code XWPFRun}/{@code XWPFTableCell} 是活对象引用，
 *       靠对象身份区分，但跨子代理/跨会话无法靠引用判断「是不是改了同一处」。
 *   <li><b>nondocx</b>：在编排层引入 {@code ConflictKey}，用 {@code (toolGroup, kind, targetRef)} 三元组
 *       把「同一目标」做成值相等，供 RouterAgent 第一层粗筛候选冲突，第二层再按 operation 类型与字段级 意图判断是否可安全合并。
 * </ul>
 *
 * <p><b>不可变值对象。</b> 实现内容相等（不含任何活对象引用），可在合并/去重时安全用作 Map 键。
 *
 * <p><b>强类型目标。</b> {@code targetRef} 使用 {@link ElementRef}。旧字符串构造器只在兼容边界转换成 {@link
 * OperationTargetRef}，对象内部不保存自由字符串。
 */
public final class ConflictKey {

  private final String toolGroup;
  private final String kind;
  private final ElementRef targetRef;

  /**
   * @param toolGroup 工具组（如 {@code "body"} / {@code "table"} / {@code "revision"}）
   * @param kind operation 类型（如 {@code "replace_text"} / {@code "set_cell_text"}）
   * @param targetRef 规范化目标引用
   */
  public ConflictKey(String toolGroup, String kind, ElementRef targetRef) {
    this.toolGroup = Objects.requireNonNull(toolGroup, "toolGroup 不能为空");
    this.kind = Objects.requireNonNull(kind, "kind 不能为空");
    this.targetRef = Objects.requireNonNull(targetRef, "targetRef 不能为空");
  }

  /**
   * 旧字符串目标兼容入口。
   *
   * @deprecated 新代码应传入具体 {@link ElementRef}
   */
  @Deprecated
  public ConflictKey(String toolGroup, String kind, String targetRef) {
    this(toolGroup, kind, OperationTargetRef.compatibility(targetRef));
  }

  /** 工具组。 */
  public String toolGroup() {
    return toolGroup;
  }

  /** operation 类型。 */
  public String kind() {
    return kind;
  }

  /** 规范化目标引用。 */
  public ElementRef targetRef() {
    return targetRef;
  }

  /**
   * 粗略判断两 key 是否命中「同一目标」候选冲突。
   *
   * <p>当 toolGroup + targetRef 相同时即视为候选冲突（kind 可不同——例如同一段落先替换文本再改样式，
   * 仍可能冲突，需第二层判定）。第一层只负责「圈出候选」，不负责下结论。
   */
  public boolean sameTarget(ConflictKey other) {
    if (other == null) return false;
    return toolGroup.equals(other.toolGroup) && targetRef.equals(other.targetRef);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ConflictKey)) return false;
    ConflictKey that = (ConflictKey) o;
    return toolGroup.equals(that.toolGroup)
        && kind.equals(that.kind)
        && targetRef.equals(that.targetRef);
  }

  @Override
  public int hashCode() {
    return Objects.hash(toolGroup, kind, targetRef);
  }

  @Override
  public String toString() {
    return toolGroup + "/" + kind + "@" + targetRef.canonical();
  }
}
