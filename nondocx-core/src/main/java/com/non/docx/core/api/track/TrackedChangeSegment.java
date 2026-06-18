package com.non.docx.core.api.track;

import com.non.docx.core.internal.util.Objects;
import java.util.List;

/**
 * 修订位置 path 里的<b>一个层级段</b>——一个 {@code (kind, index)} 二元组。
 *
 * <p>多个 segment 串成一条 {@link TrackedChangeLocation location path},从 body 逐层下钻到修订标记所挂的结构 节点。例如「正文第 2
 * 段、其内第 1 个 run 上的一条插入修订」,其 path 是:
 *
 * <pre>{@code
 * [ (BODY, 0), (PARAGRAPH, 1), (RUN, 0) ]
 * }</pre>
 *
 * <p>而「第 1 个表格、第 2 行、第 3 个单元格里的删除修订」,path 是:
 *
 * <pre>{@code
 * [ (BODY, 0), (PARAGRAPH/..., ...), (TABLE, 0), (ROW, 1), (CELL, 2) ]
 * }</pre>
 *
 * <p><b>不可变值对象。</b> segment 没有底层 POI 委托,它是位置导航的纯值。{@code equals} / {@code hashCode} 比较 {@code kind}
 * 与 {@code index}。
 *
 * <p><b>为什么用通用 {@code (kind, index)} 而非多个专用 segment 类型(poi-bridge.md design §5.5)。</b> 六类 segment
 * 当前共享相同的核心信息结构(kind + 该层级的索引),通用结构在 Java 11 下更轻量、利于比较/测试/序列化,且新增 segment kind 时只需扩枚举、不必膨胀类型数量。
 */
public final class TrackedChangeSegment {

  private final TrackedChangeSegmentKind kind;
  private final int index;

  /**
   * 构造一个层级段。
   *
   * @param kind 结构段种类(不能为 {@code null})
   * @param index 该层级中的 0-based 索引(不能为负)
   * @throws IllegalArgumentException 如果 {@code kind} 为 {@code null},或 {@code index} 为负
   */
  public TrackedChangeSegment(TrackedChangeSegmentKind kind, int index) {
    this.kind = Objects.requireNonNull(kind, "kind");
    if (index < 0) {
      throw new IllegalArgumentException("index 不能为负,was " + index);
    }
    this.index = index;
  }

  /**
   * 返回本段的结构种类。
   *
   * @return 结构段种类(从不为 {@code null})
   */
  public TrackedChangeSegmentKind kind() {
    return kind;
  }

  /**
   * 返回本段在其层级中的 0-based 索引。
   *
   * @return 0-based 索引(非负)
   */
  public int index() {
    return index;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TrackedChangeSegment)) {
      return false;
    }
    TrackedChangeSegment that = (TrackedChangeSegment) o;
    return index == that.index && kind == that.kind;
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(kind, index);
  }

  @Override
  public String toString() {
    return kind.name().toLowerCase() + "[" + index + "]";
  }

  /**
   * 把若干段拼接成可读 path 字符串(仅供显示/调试,不是稳定契约)。
   *
   * <p>例如 {@code body[0] > paragraph[1] > run[0]}。
   *
   * @param segments 有序段(不能为 {@code null})
   * @return 可读 path 字符串
   */
  static String toPathString(List<TrackedChangeSegment> segments) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size(); i++) {
      if (i > 0) {
        sb.append(" > ");
      }
      sb.append(segments.get(i).toString());
    }
    return sb.toString();
  }
}
