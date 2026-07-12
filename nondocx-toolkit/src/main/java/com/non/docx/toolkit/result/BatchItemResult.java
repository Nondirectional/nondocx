package com.non.docx.toolkit.result;

import java.util.Objects;

/**
 * 批量操作中的单项结果。
 *
 * <p>用于 {@link ToolResult#data()} 为 {@code List<BatchItemResult>} 的批量场景， 区分每个索引位置的成败。
 *
 * @param <T> 单项 data 类型
 */
public final class BatchItemResult<T> {

  private final int index;
  private final ToolResult<T> result;

  private BatchItemResult(int index, ToolResult<T> result) {
    this.index = index;
    this.result = result;
  }

  /**
   * 创建批量单项结果。
   *
   * @param index 在批量请求中的位置（0 起）
   * @param result 该项的结构化结果
   */
  public static <T> BatchItemResult<T> of(int index, ToolResult<T> result) {
    if (result == null) {
      throw new IllegalArgumentException("result 不能为空");
    }
    return new BatchItemResult<>(index, result);
  }

  /** 位置索引。 */
  public int index() {
    return index;
  }

  /** 该项的结构化结果。 */
  public ToolResult<T> result() {
    return result;
  }

  /** 该项是否成功。 */
  public boolean success() {
    return result.success();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BatchItemResult)) return false;
    BatchItemResult<?> that = (BatchItemResult<?>) o;
    return index == that.index && result.equals(that.result);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, result);
  }

  @Override
  public String toString() {
    return "[" + index + "] " + result;
  }
}
