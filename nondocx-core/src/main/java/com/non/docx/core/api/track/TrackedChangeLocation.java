package com.non.docx.core.api.track;

import java.util.Collections;
import java.util.List;

/**
 * 一条修订在文档里的<b>结构化位置</b>——一条从 body 逐层下钻的 path(由若干 {@link TrackedChangeSegment segment} 组成)。
 *
 * <p><b>为什么是 path / segment 层级结构(design §5.2)。</b> OOXML 与 POI 中的位置天然是层级导航:文档正文顺序、
 * 表格/行/单元格嵌套、段落/run 嵌套。把这种嵌套<b>诚实地</b>表达成一条 path,比把一组可空字段平铺成「伪简单对象」 更准确,也更利于测试断言与未来扩展。
 *
 * <p><b>示例。</b>
 *
 * <ul>
 *   <li>正文第 2 段、第 1 个 run 上的插入修订:{@code body[0] > paragraph[1] > run[0]}
 *   <li>第 1 个表格、第 2 行、第 3 个单元格里的删除修订:{@code body[0] > table[0] > row[1] > cell[2]}
 * </ul>
 *
 * <p><b>不可变值对象。</b> location 不持有 POI 委托,它是位置导航的纯值。{@code segments()} 返回不可修改列表; {@code equals} /
 * {@code hashCode} 比较整条 segment 序列。
 *
 * <p><b>只负责结构位置。</b> location 回答「这个修订挂在文档的哪一层结构上」;对于属性类修订,具体属性目标(如 {@code rPr} / {@code pPr} /
 * {@code sectPr})由 {@code details()} 表达,不进入 location。
 *
 * <p><b>{@code toString()} 不是稳定公共契约。</b> 路径的可读显示便于人与日志理解,但调用者不应依赖其字符串格式、 长度或分隔符;真正稳定的信息来自 {@link
 * #segments()} 的结构化二元组。
 */
public final class TrackedChangeLocation {

  private final List<TrackedChangeSegment> segments;

  /**
   * 用给定的有序 segment 列表构造一个位置。内部拷贝并冻结为不可修改视图,因此调用方后续改动不影响本对象。
   *
   * @param segments 有序段(不能为 {@code null};允许为空列表,表示「无已知结构位置」,极少见)
   * @throws IllegalArgumentException 如果 {@code segments} 为 {@code null} 或含 {@code null} 元素
   */
  public TrackedChangeLocation(List<TrackedChangeSegment> segments) {
    com.non.docx.core.internal.util.Objects.requireNonNull(segments, "segments");
    // 防御性拷贝 + 冻结;逐个校验非 null。
    List<TrackedChangeSegment> copy = new java.util.ArrayList<>(segments.size());
    for (int i = 0; i < segments.size(); i++) {
      TrackedChangeSegment s = segments.get(i);
      if (s == null) {
        throw new IllegalArgumentException("segments[" + i + "] 不能为 null");
      }
      copy.add(s);
    }
    this.segments = Collections.unmodifiableList(copy);
  }

  /**
   * 返回构成此位置的有序 segment 列表(从 body 起,逐层下钻)。
   *
   * @return 不可修改的 segment 列表(从不为 {@code null})
   */
  public List<TrackedChangeSegment> segments() {
    return segments;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TrackedChangeLocation)) {
      return false;
    }
    TrackedChangeLocation that = (TrackedChangeLocation) o;
    return java.util.Objects.equals(this.segments, that.segments);
  }

  @Override
  public int hashCode() {
    return segments.hashCode();
  }

  @Override
  public String toString() {
    return TrackedChangeSegment.toPathString(segments);
  }
}
