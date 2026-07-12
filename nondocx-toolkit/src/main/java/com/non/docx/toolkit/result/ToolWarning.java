package com.non.docx.toolkit.result;

import java.util.Objects;

/**
 * 非致命提示。不影响 {@link ToolResult#success()}，但调用方应感知。
 *
 * <p>不可变值对象。
 */
public final class ToolWarning {

  private final String code;
  private final String message;
  private final String ref;

  private ToolWarning(String code, String message, String ref) {
    this.code = code;
    this.message = message == null ? "" : message;
    this.ref = ref;
  }

  /**
   * 创建警告。
   *
   * @param code 警告码（自由字符串，不强制 {@link ToolResultCode}，因为 warning 语义更宽）
   * @param message 中文消息
   */
  public static ToolWarning of(String code, String message) {
    return new ToolWarning(code, message, null);
  }

  /**
   * 创建带关联元素的警告。
   *
   * @param code 警告码
   * @param message 中文消息
   * @param ref 关联元素的 canonical ref，可选
   */
  public static ToolWarning of(String code, String message, String ref) {
    return new ToolWarning(code, message, ref);
  }

  /** 警告码。 */
  public String code() {
    return code;
  }

  /** 中文消息。 */
  public String message() {
    return message;
  }

  /** 关联元素 canonical ref，无则为 null。 */
  public String ref() {
    return ref;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ToolWarning)) return false;
    ToolWarning that = (ToolWarning) o;
    return Objects.equals(code, that.code)
        && message.equals(that.message)
        && Objects.equals(ref, that.ref);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, message, ref);
  }

  @Override
  public String toString() {
    if (ref == null) {
      return "警告[" + code + "]：" + message;
    }
    return "警告[" + code + "]：" + message + "（ref=" + ref + "）";
  }
}
