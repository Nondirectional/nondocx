package com.non.docx.core.internal.util;

/**
 * 内部 API — 如有变更，恕不另行通知。
 *
 * <p>参数验证辅助。与 {@link java.util.Objects#requireNonNull} 不同，这会抛出 {@link IllegalArgumentException} 而不是
 * {@link NullPointerException}，符合 nondocx 的 错误模型：公开表面上的 {@code null} 参数报告为 {@code
 * IllegalArgumentException}， 而绝不作为 {@code NullPointerException}。
 */
public final class Objects {

  private Objects() {}

  /**
   * 验证给定引用非空。
   *
   * @param obj 要检查的引用
   * @param context 参数的简短描述（例如 {@code "file"} 或 {@code "delegate"}）， 用于异常消息；当为 {@code null} 时视为
   *     {@code "argument"}
   * @param <T> 引用类型
   * @return 非空引用
   * @throws IllegalArgumentException 如果 {@code obj} 为 {@code null}
   */
  public static <T> T requireNonNull(T obj, String context) {
    if (obj == null) {
      throw new IllegalArgumentException((context == null ? "argument" : context) + " 不能为 null");
    }
    return obj;
  }
}
